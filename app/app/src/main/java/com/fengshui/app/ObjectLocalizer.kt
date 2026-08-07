package com.fengshui.app

import com.google.ar.core.Camera
import kotlin.math.abs

/**
 * A1.4: 物体 3D 定位。
 * 将检测框中心像素 + ARCore 相机位姿 → 射线 → 与地板平面(y=floorHeight)交点 → 物体地面位置。
 *
 * 定位在后台检测线程执行，ARCore 的 [Camera] 只在会话回调内有效，故先快照成
 * [CameraSnapshot]（纯数据，可跨线程）。
 */
object ObjectLocalizer {

    /** 相机内参与位姿快照（跨线程安全）。 */
    data class CameraSnapshot(
        val fx: Float, val fy: Float, val cx: Float, val cy: Float,
        val ox: Float, val oy: Float, val oz: Float,
        val qx: Float, val qy: Float, val qz: Float, val qw: Float
    )

    /** 在回调内调用：拷贝相机内参与 displayOrientedPose。 */
    fun snapshot(camera: Camera): CameraSnapshot {
        val intr = camera.imageIntrinsics
        val pose = camera.displayOrientedPose
        return CameraSnapshot(
            fx = intr.focalLength[0], fy = intr.focalLength[1],
            cx = intr.principalPoint[0], cy = intr.principalPoint[1],
            ox = pose.tx(), oy = pose.ty(), oz = pose.tz(),
            qx = pose.qx(), qy = pose.qy(), qz = pose.qz(), qw = pose.qw()
        )
    }

    /**
     * @param u, v 检测框中心像素坐标
     * @param floorHeight 地板高度（世界 Y，由点云估算）
     * @return 世界坐标 [x, floorHeight, z]；失败返回 null
     */
    fun projectToFloor(cam: CameraSnapshot, u: Float, v: Float, floorHeight: Float): FloatArray? {
        if (!floorHeight.isFinite()) return null
        val fx = cam.fx; val fy = cam.fy; val cx = cam.cx; val cy = cam.cy
        val ox = cam.ox; val oy = cam.oy; val oz = cam.oz

        // ARCore 相机帧：+x 右，+y 上，+z 朝观看者（向后）；图像 v 向下 → 射线指向场景为 -z、图像下方为 -y
        val dcx = (u - cx) / fx
        val dcy = -(v - cy) / fy
        val dcz = -1f

        // 旋转到世界坐标（位姿四元数）
        val (wx, wy, wz) = rotateQuat(dcx, dcy, dcz, cam.qx, cam.qy, cam.qz, cam.qw)

        // 射线与 y=floorHeight 求交
        if (abs(wy) < 1e-5f) return null
        val t = (floorHeight - oy) / wy
        if (t < 0) return null
        if (!t.isFinite()) return null
        val rx = ox + t * wx
        val rz = oz + t * wz
        if (!rx.isFinite() || !rz.isFinite()) return null
        return floatArrayOf(rx, floorHeight, rz)
    }

    /** 相机朝前的世界航向（atan2(dx,dz) 约定，弧度），供北向校准。 */
    fun cameraForwardHeading(cam: CameraSnapshot): Double {
        // ARCore 相机帧 +z 朝观看者 → 朝前 = -z
        val (fx, _, fz) = rotateQuat(0f, 0f, -1f, cam.qx, cam.qy, cam.qz, cam.qw)
        return Math.atan2(fx.toDouble(), fz.toDouble())
    }

    /** 四元数旋转向量。 */
    private fun rotateQuat(
        x: Float, y: Float, z: Float,
        qx: Float, qy: Float, qz: Float, qw: Float
    ): Triple<Float, Float, Float> {
        // 用旋转矩阵：R = I + 2w[v]x + 2[v]x[v]x
        val ux = qx; val uy = qy; val uz = qz
        val s = qw
        val m00 = 1 - 2 * (uy * uy + uz * uz)
        val m01 = 2 * (ux * uy - s * uz)
        val m02 = 2 * (ux * uz + s * uy)
        val m10 = 2 * (ux * uy + s * uz)
        val m11 = 1 - 2 * (ux * ux + uz * uz)
        val m12 = 2 * (uy * uz - s * ux)
        val m20 = 2 * (ux * uz - s * uy)
        val m21 = 2 * (uy * uz + s * ux)
        val m22 = 1 - 2 * (ux * ux + uy * uy)
        return Triple(
            m00 * x + m01 * y + m02 * z,
            m10 * x + m11 * y + m12 * z,
            m20 * x + m21 * y + m22 * z
        )
    }
}
