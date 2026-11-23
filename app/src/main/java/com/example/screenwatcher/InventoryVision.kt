package com.example.screenwatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bộ nhận diện hành trang offline. AI ML Kit gắn nhãn ngữ nghĩa từng ô; bộ so khớp
 * ảnh mẫu vẫn là tầng quyết định GIỮ/JOB để tránh dùng nhãn phổ thông của game làm
 * quyết định phá huỷ. Cả hai xử lý trên thiết bị, không gửi ảnh ra ngoài.
 */
object InventoryVision {
    private const val VERSION = 2
    private const val SIDE = 12
    private const val COLOR_FEATURES = SIDE * SIDE * 3
    private const val EDGE_FEATURES = SIDE * (SIDE - 1) * 2
    private const val FEATURE_SIZE = COLOR_FEATURES + EDGE_FEATURES
    private const val DEFAULT_CELL_DP = 64
    private const val KEEP_THRESHOLD = 0.78f

    data class Item(val label: String, val score: Float, val x: Int, val y: Int, val w: Int = 0, val h: Int = 0, val aiLabels: List<String> = emptyList())
    private data class Sample(val feature: FloatArray)

    fun cellSizePx(c: Context): Int = (DEFAULT_CELL_DP * c.resources.displayMetrics.density).toInt().coerceAtLeast(32)
    fun file(c: Context, ruleId: String) = File(c.filesDir, "inv_samples_$ruleId.bin")

    @Synchronized fun count(c: Context, ruleId: String): Int = load(c, ruleId).size

    @Synchronized fun clear(c: Context, ruleId: String) { file(c, ruleId).delete() }

    @Synchronized fun addSample(c: Context, ruleId: String, frame: Bitmap, rect: Rect): Boolean {
        val l = rect.left.coerceIn(0, frame.width - 1)
        val t = rect.top.coerceIn(0, frame.height - 1)
        val r = rect.right.coerceIn(l + 1, frame.width)
        val b = rect.bottom.coerceIn(t + 1, frame.height)
        if (r - l < 12 || b - t < 12) return false
        val crop = Bitmap.createBitmap(frame, l, t, r - l, b - t)
        val f = describe(crop)
        crop.recycle()
        val samples = load(c, ruleId).toMutableList()
        if (samples.none { similarity(it.feature, f) >= 0.985f }) samples.add(Sample(f))
        save(c, ruleId, samples)
        return true
    }

    @Synchronized fun scan(c: Context, ruleId: String, frame: Bitmap, regions: List<Region>): List<Item> {
        val samples = load(c, ruleId)
        if (samples.isEmpty()) return emptyList()
        val size = cellSizePx(c)
        // Fail closed: bắt buộc người dùng khoanh vùng hành trang, không quét toàn màn hình.
        if (regions.isEmpty()) return emptyList()
        val regs = regions
        val out = ArrayList<Item>()
        for (rg in regs) {
            val l = rg.l.coerceIn(0, frame.width)
            val t = rg.t.coerceIn(0, frame.height)
            val r = rg.r.coerceIn(l, frame.width)
            val b = rg.b.coerceIn(t, frame.height)
            if (r - l < size || b - t < size) continue
            var y = t
            while (y + size <= b) {
                var x = l
                while (x + size <= r) {
                    val crop = Bitmap.createBitmap(frame, x, y, size, size)
                    val feature = describe(crop)
                    // AI gắn nhãn ngữ nghĩa trên máy; kết quả chỉ bổ sung thông tin,
                    // không được dùng để tự quyết định giữ/xóa nhằm tránh nhãn chung gây nhầm.
                    val aiLabels = if (visualActivity(feature) >= 0.018f) {
                        runCatching { OfflineImageAI.identify(crop) }.getOrDefault(emptyList())
                    } else emptyList()
                    crop.recycle()
                    // Bỏ ô gần như trống/đơn sắc. Người dùng nên khoanh đúng vùng lưới hành trang.
                    if (visualActivity(feature) >= 0.018f) {
                        val best = samples.maxOfOrNull { similarity(it.feature, feature) } ?: 0f
                        val keep = best >= KEEP_THRESHOLD
                        val label = if (keep) "GIU_${(best * 100).toInt()}" else "JOB"
                        out.add(Item(label, if (keep) best else 1f - best, x + size / 2, y + size / 2, size, size, aiLabels))
                    }
                    x += size
                }
                y += size
            }
        }
        return out.sortedWith(compareBy({ it.y / size }, { it.x }))
    }

    private fun describe(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, SIDE, SIDE, true)
        val px = IntArray(SIDE * SIDE)
        scaled.getPixels(px, 0, SIDE, 0, 0, SIDE, SIDE)
        if (scaled !== bitmap) scaled.recycle()
        val out = FloatArray(FEATURE_SIZE)
        val gray = FloatArray(SIDE * SIDE)
        for (i in px.indices) {
            val p = px[i]
            val r = ((p shr 16) and 255) / 255f
            val g = ((p shr 8) and 255) / 255f
            val b = (p and 255) / 255f
            out[i * 3] = r; out[i * 3 + 1] = g; out[i * 3 + 2] = b
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b)
        }
        // Gradient descriptor nhấn mạnh hình dáng/đường viền icon, giảm việc hai ô có cùng nền bị nhận nhầm.
        var k = COLOR_FEATURES
        for (y in 0 until SIDE) for (x in 0 until SIDE - 1) {
            val i = y * SIDE + x
            out[k++] = gray[i + 1] - gray[i]
        }
        for (y in 0 until SIDE - 1) for (x in 0 until SIDE) {
            val i = y * SIDE + x
            out[k++] = gray[i + SIDE] - gray[i]
        }
        return out
    }

    private fun visualActivity(f: FloatArray): Float {
        var mean = 0f
        for (i in 0 until COLOR_FEATURES step 3) mean += (f[i] + f[i + 1] + f[i + 2]) / 3f
        mean /= (SIDE * SIDE)
        var v = 0f
        for (i in 0 until COLOR_FEATURES step 3) {
            val g = (f[i] + f[i + 1] + f[i + 2]) / 3f
            v += abs(g - mean)
        }
        return v / (SIDE * SIDE)
    }

    /** 0..1: kết hợp màu và cạnh hình; cạnh có trọng số cao để phân biệt icon chung nền. */
    private fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != FEATURE_SIZE || b.size != FEATURE_SIZE) return 0f
        var colorError = 0f
        for (i in 0 until COLOR_FEATURES) colorError += max(0f, abs(a[i] - b[i]) - 0.06f)
        var edgeError = 0f
        for (i in COLOR_FEATURES until FEATURE_SIZE) edgeError += max(0f, abs(a[i] - b[i]) - 0.045f)
        val colorScore = 1f - colorError / COLOR_FEATURES
        val edgeScore = 1f - edgeError / EDGE_FEATURES
        return (0.38f * colorScore + 0.62f * edgeScore).coerceIn(0f, 1f)
    }

    private fun load(c: Context, id: String): List<Sample> {
        val f = file(c, id)
        if (!f.exists()) return emptyList()
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readInt() != VERSION) return@use emptyList()
                val n = input.readInt().coerceIn(0, 300)
                List(n) { Sample(FloatArray(FEATURE_SIZE) { input.readFloat() }) }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(c: Context, id: String, samples: List<Sample>) {
        val target = file(c, id)
        val tmp = File(c.filesDir, "inv_samples_$id.tmp")
        runCatching {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(VERSION); out.writeInt(samples.size)
                samples.forEach { s -> s.feature.forEach(out::writeFloat) }
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }.onFailure { tmp.delete() }
    }
}
