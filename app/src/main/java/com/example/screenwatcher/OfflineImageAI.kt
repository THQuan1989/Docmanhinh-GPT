package com.example.screenwatcher

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.TimeUnit

/**
 * Nhận diện ngữ nghĩa ảnh hoàn toàn trên thiết bị bằng ML Kit Image Labeling.
 * Dùng model bundled (com.google.mlkit:image-labeling), không gọi API mạng và
 * không gửi bitmap ra khỏi điện thoại. Nhãn là các khái niệm phổ thông, không
 * đảm bảo đặt đúng tên riêng của vật phẩm độc quyền trong từng game.
 */
object OfflineImageAI {
    @Volatile private var labeler: ImageLabeler? = null

    private fun getLabeler(): ImageLabeler = labeler ?: synchronized(this) {
        labeler ?: ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.35f)
                .build()
        ).also { labeler = it }
    }

    /** Trả về tối đa 5 nhãn có độ tin cậy cao nhất. Chỉ gọi trên worker thread. */
    fun identify(bitmap: Bitmap): List<String> {
        if (bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) return emptyList()
        val task = getLabeler().process(InputImage.fromBitmap(bitmap, 0))
        return Tasks.await(task, 12, TimeUnit.SECONDS)
            .sortedByDescending { it.confidence }
            .take(5)
            .map { "${it.text} ${(it.confidence * 100).toInt()}%" }
    }

    fun close() {
        synchronized(this) {
            labeler?.close()
            labeler = null
        }
    }
}
