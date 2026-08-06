package com.fengshui.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image

/** ARCore 相机图像(YUV_420_888) → RGB Bitmap。 */
object YuvUtils {

    fun toBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.size < 3) return null
        val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
        val w = image.width; val h = image.height
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        var uvOffset = 0
        var yOffset = 0
        for (j in 0 until h) {
            yBuf.position(j * yRowStride)
            uvOffset = (j / 2) * uvRowStride
            for (i in 0 until w) {
                val y = yBuf.get().toInt() and 0xFF
                val uvIndex = (i / 2) * uvPixelStride
                val u = uBuf.get(uvOffset + uvIndex).toInt() and 0xFF
                val v = vBuf.get(uvOffset + uvIndex).toInt() and 0xFF
                pixels[j * w + i] = Color.rgb(
                    (y + 1.402 * (v - 128)).toInt().coerceIn(0, 255),
                    (y - 0.344 * (u - 128) - 0.714 * (v - 128)).toInt().coerceIn(0, 255),
                    (y + 1.772 * (u - 128)).toInt().coerceIn(0, 255)
                )
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }
}
