package com.example.screenwatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** So khớp vật phẩm bằng embedding MobileCLIP2-S2; mọi suy luận chạy offline. */
object InventoryVision {
    private const val VERSION = 3
    private const val DEFAULT_CELL_DP = 64
    // Ngưỡng cosine cần kiểm thử thực tế trên game; có thể điều chỉnh sau khi đo dữ liệu.
    private const val KEEP_THRESHOLD = 0.86f
    private const val EMBEDDING_SIZE = 512

    data class Item(val label: String, val score: Float, val x: Int, val y: Int, val w: Int = 0, val h: Int = 0, val aiLabels: List<String> = emptyList())
    private data class Sample(val embedding: FloatArray)

    private fun prefs(c: Context) = c.getSharedPreferences("sw", Context.MODE_PRIVATE)
    fun cellDp(c: Context, ruleId: String): Int = prefs(c).getInt("invCellDp_$ruleId", DEFAULT_CELL_DP).coerceIn(24, 200)
    fun setCellDp(c: Context, ruleId: String, dp: Int) { prefs(c).edit().putInt("invCellDp_$ruleId", dp.coerceIn(24, 200)).apply() }
    /** Kích thước ô (px). Có ruleId thì dùng cỡ ô riêng của luật đó, không thì dùng mặc định 64dp. */
    fun cellSizePx(c: Context, ruleId: String? = null): Int {
        val dp = if (ruleId == null) DEFAULT_CELL_DP else cellDp(c, ruleId)
        return (dp * c.resources.displayMetrics.density).toInt().coerceAtLeast(32)
    }

    /** Chia các vùng thành lưới ô vuông; bỏ ô trùng quá 25% với ô đã có (khi nhiều vùng chồng lên nhau). */
    fun buildCells(frameW: Int, frameH: Int, regions: List<Region>, size: Int): List<Rect> {
        val out = ArrayList<Rect>()
        for (rg in regions) {
            val l = rg.l.coerceIn(0, frameW); val t = rg.t.coerceIn(0, frameH)
            val r = rg.r.coerceIn(l, frameW); val b = rg.b.coerceIn(t, frameH)
            var y = t
            while (y + size <= b) {
                var x = l
                while (x + size <= r) {
                    val cand = Rect(x, y, x + size, y + size)
                    if (out.none { overlapArea(it, cand) > size.toLong() * size / 4 }) out.add(cand)
                    x += size
                }
                y += size
            }
        }
        return out
    }
    private fun overlapArea(a: Rect, b: Rect): Long {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w > 0 && h > 0) w.toLong() * h else 0L
    }
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
        val embedding = try { MobileClip2.embed(c, crop) } finally { crop.recycle() }
        if (embedding.size != EMBEDDING_SIZE) return false
        val samples = load(c, ruleId).toMutableList()
        if (samples.none { MobileClip2.cosine(it.embedding, embedding) >= 0.995f }) samples.add(Sample(embedding))
        save(c, ruleId, samples)
        return true
    }

    @Synchronized fun scan(c: Context, ruleId: String, frame: Bitmap, regions: List<Region>): List<Item> {
        val samples = load(c, ruleId)
        if (samples.isEmpty() || regions.isEmpty()) return emptyList()
        val size = cellSizePx(c, ruleId)
        val out = ArrayList<Item>()
        for (rect in buildCells(frame.width, frame.height, regions, size)) {
            val crop = Bitmap.createBitmap(frame, rect.left, rect.top, size, size)
            val embedding = try { MobileClip2.embed(c, crop) } finally { crop.recycle() }
            val best = samples.maxOfOrNull { MobileClip2.cosine(it.embedding, embedding) } ?: -1f
            val keep = best >= KEEP_THRESHOLD
            val label = if (keep) "GIU_${(best * 100).toInt()}" else "JOB"
            out.add(Item(label, if (keep) best else 1f - best, rect.left + size / 2, rect.top + size / 2, size, size))
        }
        return out.sortedWith(compareBy({ it.y / size }, { it.x }))
    }

    private fun load(c: Context, id: String): List<Sample> {
        val f = file(c, id)
        if (!f.exists()) return emptyList()
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readInt() != VERSION) return@use emptyList()
                val n = input.readInt().coerceIn(0, 300)
                List(n) { Sample(FloatArray(EMBEDDING_SIZE) { input.readFloat() }) }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(c: Context, id: String, samples: List<Sample>) {
        val target = file(c, id)
        val tmp = File(c.filesDir, "inv_samples_$id.tmp")
        runCatching {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(VERSION); out.writeInt(samples.size)
                samples.forEach { sample -> sample.embedding.forEach(out::writeFloat) }
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }.onFailure { tmp.delete() }
    }
}
