package com.fengshui.app

import com.google.ar.core.Camera
import kotlin.math.abs

/**
 * A1.4: 物体 3D 定位。
 * 将检测框中心像素 + ARCore 相机位姿 → 射线 → 与地板平面(y=floorHeight)交点 → 物体地面位置。
 */
object ObjectLocalizer {

    /**
     * @param u, v 检测框中心像素坐标
     * @param floorHeight 地板高度（世界 Y，由户型多边形算出）
     * @return 世界坐标 [x, floorHeight, z]；失败返回 null
     */
    fun projectToFloor(camera: Camera, u: Float, v: Float, floorHeight: Float): FloatArray? {
        val intr = camera.imageIntrinsics
        val fx = intr.focalLength[0]
        val fy = intr.focalLength[1]
        val cx = intr.principalPoint[0]
        val cy = intr.principalPoint[1]

        val pose = camera.displayOrientedPose
        val ox = pose.tx(); val oy = pose.ty(); val oz = pose.tz()

        // 相机空间射线方向（z 前向；ARCore 图像 v 向下）
        val dcx = (u - cx) / fx
        val dcy = (v - cy) / fy
        val dcz = 1f

        // 旋转到世界坐标（位姿四元数）
        val qx = pose.qx(); val qy = pose.qy(); val qz = pose.qz(); val qw = pose.qw()
        val (wx, wy, wz) = rotateQuat(dcx, dcy, dcz, qx, qy, qz, qw)

        // 射线与 y=floorHeight 求交
        if (abs(wy) < 1e-5f) return null
        val t = (floorHeight - oy) / wy
        if (t < 0) return null
        return floatArrayOf(ox + t * wx, floorHeight, oz + t * wz)
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
