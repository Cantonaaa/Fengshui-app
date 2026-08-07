package com.fengshui.app

import android.graphics.Bitmap
import android.media.Image
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 扫描关键帧缓冲（录像后处理用）。
 * 按相机位移触发缓存一帧：YUV→Bitmap→JPEG（内存）+ 相机位姿快照 + 地面高度。
 * 不即时检测；扫描完成后由 [PostScanProcessor] 统一批量处理。
 */
class ScanFrameBuffer {

    /** 一帧缓冲：JPEG 图像 + 位姿快照 + 地面高度。 */
    data class FrameRec(
        val jpeg: ByteArray,
        val snap: ObjectLocalizer.CameraSnapshot,
        val floorH: Float
    )

    private val frames = ArrayList<FrameRec>()
    private var lastPose: ObjectLocalizer.CameraSnapshot? = null

    /** 累计航向（用于自动检测"环绕一周"）。 */
    var cumulativeYaw: Float = 0f
        private set
    private var lastYaw: Float? = null

    val size: Int get() = frames.size
    val isFullCircle: Boolean get() = cumulativeYaw >= 300f

    /** 相机移动是否足够（触发新关键帧）。 */
    fun shouldCapture(snap: ObjectLocalizer.CameraSnapshot): Boolean {
        val lp = lastPose ?: return true
        val moved = hypot((snap.ox - lp.ox).toDouble(), (snap.oz - lp.oz).toDouble()) > 0.12
        val yaw = snap.qw
        return moved
    }

    /** 缓存一帧（须在会话回调内调用；image 由调用方 close）。 */
    @Synchronized
    fun capture(image: Image, snap: ObjectLocalizer.CameraSnapshot, floorH: Float) {
        val bmp = YuvUtils.toBitmap(image)
        if (bmp != null) {
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            frames.add(FrameRec(bos.toByteArray(), snap, floorH))
        }
        lastPose = snap
        updateYaw(snap)
    }

    /** 相机移动足够则缓存，否则跳过。 */
    @Synchronized
    fun tryCapture(image: Image, snap: ObjectLocalizer.CameraSnapshot, floorH: Float): Boolean {
        if (!shouldCapture(snap)) {
            updateYaw(snap)
            return false
        }
        capture(image, snap, floorH)
        return true
    }

    private fun updateYaw(snap: ObjectLocalizer.CameraSnapshot) {
        // 用位姿的航向（cameraForwardHeading 与校北同源）
        val yaw = Math.toDegrees(ObjectLocalizer.cameraForwardHeading(snap)).toFloat()
        val ly = lastYaw
        if (ly != null) {
            var d = yaw - ly
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            cumulativeYaw += abs(d)
        }
        lastYaw = yaw
    }

    @Synchronized
    fun all(): List<FrameRec> = frames.toList()

    @Synchronized
    fun clear() {
        frames.clear()
        lastPose = null
        lastYaw = null
        cumulativeYaw = 0f
    }
}
