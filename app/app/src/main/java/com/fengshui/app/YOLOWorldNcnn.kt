package com.fengshui.app

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * K3/A1.4: 基于 NCNN(JNI) 的 YOLO-World 检测器门面。
 * 模型文件: assets/yolo_world.param + yolo_world.bin（NCNN 格式）。
 *
 * 两种输入路径：
 *  - [detect]：RGBA Bitmap（兼容旧路径）
 *  - [detectYuv]：ARCore YUV_420_888 平面直送（省去 Kotlin 逐像素转换，速度快）
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
        "bed", "sofa", "dining table", "refrigerator", "potted plant",
        "wardrobe", "door", "window", "stove", "pillar", "desk", "front desk",
        "water", "shrine", "bookshelf", "safe", "book"
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

    fun detect(bitmap: Bitmap, confThr: Float = 0.35f, iouThr: Float = 0.45f, marginThr: Float = 0.15f): List<Detection> {
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
        val res = nativeDetect(bytes, w, h, confThr, iouThr, marginThr) ?: return emptyList()
        return parseResult(res)
    }

    /**
     * ARCore YUV_420_888 平面直送。Image 须来自 [android.media.ImageReader]/ARCore acquireCameraImage。
     * 在后台线程调用；JNI 内一步完成 YUV→RGB+缩放，不经过 Bitmap。
     */
    fun detectYuv(image: Image, confThr: Float = 0.35f, iouThr: Float = 0.45f, marginThr: Float = 0.15f): List<Detection> {
        if (!isReady) return emptyList()
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val res = nativeDetectYuv(
            yPlane.buffer, uPlane.buffer, vPlane.buffer,
            image.width, image.height,
            yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride,
            yPlane.buffer.position(), uPlane.buffer.position(), vPlane.buffer.position(),
            confThr, iouThr, marginThr
        ) ?: return emptyList()
        return parseResult(res)
    }

    private fun parseResult(res: FloatArray): List<Detection> {
        val n = res[0].toInt()
        val dets = ArrayList<Detection>(n)
        var idx = 1
        repeat(n) {
            val cls = res[idx].toInt()
            val score = res[idx + 1]
            val l = res[idx + 2]; val t = res[idx + 3]; val r = res[idx + 4]; val b = res[idx + 5]
            idx += 6
            val label = if (cls < 0) UNKNOWN else vocab.getOrElse(cls) { "obj$cls" }
            dets.add(Detection(label, score, l, t, r, b))
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
        rgba: ByteArray, w: Int, h: Int, confThr: Float, iouThr: Float, marginThr: Float
    ): FloatArray?
    private external fun nativeDetectYuv(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        w: Int, h: Int,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        yPos: Int, uPos: Int, vPos: Int,
        confThr: Float, iouThr: Float, marginThr: Float
    ): FloatArray?

    companion object {
        init {
            System.loadLibrary("yoloworld")
        }
        private const val TAG = "K3Ncnn"
        private const val UNKNOWN = "未识别"
    }
}
