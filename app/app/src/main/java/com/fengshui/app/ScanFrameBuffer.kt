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
    private var lastCapPos: Pair<Double, Double>? = null      // 上次已缓存帧位置（冗余抑制基准）
    private var lastCapHeading: Double? = null                // 上次已缓存帧航向（弧度）

    /** B4: 帧缓冲上限（~40MB JPEG），超限 FIFO 淘汰最旧帧，防超长扫描 OOM。 */
    private val MAX_FRAMES = 100

    /** 累计航向（用于自动检测"环绕一周"）。 */
    var cumulativeYaw: Float = 0f
        private set
    private var lastYaw: Float? = null

    val size: Int get() = frames.size
    val isFullCircle: Boolean get() = cumulativeYaw >= 300f

    /** 冗余帧抑制：距上次已缓存帧位移 ≥0.25m，或转角 ≥25° 才触发（保留多角度、剔除慢走近重复帧）。 */
    fun shouldCapture(snap: ObjectLocalizer.CameraSnapshot): Boolean {
        val lp = lastCapPos ?: return true
        val moved = hypot(snap.ox.toDouble() - lp.first, snap.oz.toDouble() - lp.second) >= 0.25
        if (moved) return true
        val h = ObjectLocalizer.cameraForwardHeading(snap)
        val lh = lastCapHeading ?: return true
        var d = Math.abs(h - lh)
        while (d > Math.PI) d -= 2 * Math.PI
        return Math.abs(d) >= Math.toRadians(25.0)
    }

    /** 保守模糊检测：Laplacian 方差 < 阈值视为明显模糊（仅剔除极模糊帧）。 */
    private fun isBlurry(bmp: Bitmap): Boolean {
        val s = 64
        val scaled = Bitmap.createScaledBitmap(bmp, s, s, true)
        val px = IntArray(s * s)
        scaled.getPixels(px, 0, s, 0, 0, s, s)
        scaled.recycle()
        val gray = FloatArray(s * s)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3f
        }
        var sum = 0.0; var sum2 = 0.0
        for (y in 1 until s - 1) for (x in 1 until s - 1) {
            val lap = -4 * gray[y * s + x] + gray[(y - 1) * s + x] + gray[(y + 1) * s + x] +
                gray[y * s + x - 1] + gray[y * s + x + 1]
            sum += lap; sum2 += lap * lap
        }
        val n = (s - 2) * (s - 2)
        val mean = sum / n
        return (sum2 / n - mean * mean) < 40f
    }

    /** 缓存一帧（须在会话回调内调用；image 由调用方 close）。 */
    @Synchronized
    fun capture(image: Image, snap: ObjectLocalizer.CameraSnapshot, floorH: Float) {
        val bmp = YuvUtils.toBitmap(image)
        if (bmp != null) {
            if (isBlurry(bmp)) {   // 模糊帧不缓存（不更新 lastCapPos，给同位置下次机会）
                bmp.recycle()
                lastPose = snap
                updateYaw(snap)
                return
            }
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            bmp.recycle()
            if (frames.size >= MAX_FRAMES) frames.removeAt(0)  // B4: FIFO 淘汰最旧帧
            frames.add(FrameRec(bos.toByteArray(), snap, floorH))
            lastCapPos = snap.ox.toDouble() to snap.oz.toDouble()
            lastCapHeading = ObjectLocalizer.cameraForwardHeading(snap)
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
        lastCapPos = null
        lastCapHeading = null
        lastYaw = null
        cumulativeYaw = 0f
    }
}
