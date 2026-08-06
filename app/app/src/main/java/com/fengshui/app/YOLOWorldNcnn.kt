package com.fengshui.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * K3/A1.4: 基于 NCNN(JNI) 的 YOLO-World 检测器门面。
 * 模型文件: assets/yolo_world.param + yolo_world.bin（NCNN 格式）。
 */
class YOLOWorldNcnn(private val context: Context) {

    data class Detection(
        val cls: String,
        val score: Float,
        val left: Float, val top: Float, val right: Float, val bottom: Float
    ) {
        val cx: Float get() = (left + right) / 2f
        val cy: Float get() = (top + bottom) / 2f
    }

    private val vocab = listOf(
        "bed", "sofa", "dining table", "refrigerator", "potted plant", "toilet",
        "wardrobe", "door", "window", "stove", "pillar", "desk", "front desk"
    )

    var isReady: Boolean = false
        private set

    /** 从 assets 复制模型并加载。缺失时 isReady=false。 */
    fun load(paramFile: String = "yolo_world.param", binFile: String = "yolo_world.bin") {
        try {
            val p = copyAssetToFiles(paramFile)
            val b = copyAssetToFiles(binFile)
            isReady = nativeLoadModel(p, b)
            Log.i(TAG, "NCNN 模型加载结果: isReady=$isReady")
        } catch (e: Exception) {
            isReady = false
            Log.w(TAG, "NCNN 模型未就绪: ${e.message}（待生成模型后检测可用）")
        }
    }

    fun detect(bitmap: Bitmap, confThr: Float = 0.35f, iouThr: Float = 0.45f): List<Detection> {
        if (!isReady) return emptyList()
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val bytes = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val c = pixels[i]
            bytes[i * 4] = ((c shr 16) and 0xFF).toByte()
            bytes[i * 4 + 1] = ((c shr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = (c and 0xFF).toByte()
            bytes[i * 4 + 3] = 0xFF.toByte()
        }
        val res = nativeDetect(bytes, w, h, confThr, iouThr) ?: return emptyList()
        val n = res[0].toInt()
        val dets = ArrayList<Detection>()
        var idx = 1
        repeat(n) {
            val cls = res[idx].toInt()
            val score = res[idx + 1]
            val l = res[idx + 2]; val t = res[idx + 3]; val r = res[idx + 4]; val b = res[idx + 5]
            idx += 6
            dets.add(Detection(vocab.getOrElse(cls) { "obj$cls" }, score, l, t, r, b))
        }
        return dets
    }

    private fun copyAssetToFiles(asset: String): String {
        val out = File(context.filesDir, asset)
        context.assets.open(asset).use { ins ->
            FileOutputStream(out).use { ous -> ins.copyTo(ous) }
        }
        return out.absolutePath
    }

    private external fun nativeLoadModel(paramPath: String, binPath: String): Boolean
    private external fun nativeDetect(
        rgba: ByteArray, w: Int, h: Int, confThr: Float, iouThr: Float
    ): FloatArray?

    companion object {
        init {
            System.loadLibrary("yoloworld")
        }
        private const val TAG = "K3Ncnn"
    }
}
