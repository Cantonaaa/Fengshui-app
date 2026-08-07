package com.fengshui.app

/**
 * 物体尺寸估计（MVP 单帧透视近似）：
 * 物理宽 ≈ bbox宽(px) / 焦距(px) × 水平距离；物理高同理。
 * 占地宽 dimX = 物理宽钳制到类型合理范围；进深 dimZ 用类型默认（单帧无法测进深）。
 */
object SizeEstimator {

    /**
     * @param bboxWidthPx  检测框宽（源图像素）
     * @param bboxHeightPx 检测框高（源图像素）
     * @param fx, fy       相机焦距（像素）
     * @param distance     相机到物体地面点的水平距离（米）
     * @return 物理宽高 [width, height]（米）；无效输入返回 0.0
     */
    fun estimate(bboxWidthPx: Float, bboxHeightPx: Float, fx: Float, fy: Float, distance: Double): Pair<Double, Double> {
        if (distance <= 0.0 || !distance.isFinite() || fx <= 0 || fy <= 0) return 0.0 to 0.0
        return (bboxWidthPx / fx * distance).toDouble() to
            (bboxHeightPx / fy * distance).toDouble()
    }

    /** 占地宽钳制到类型默认值的 [0.6, 1.8] 倍，避免离群。 */
    fun footprintWidth(apparentWidth: Double, defaultDimX: Double): Double =
        if (apparentWidth <= 0 || !apparentWidth.isFinite()) defaultDimX
        else apparentWidth.coerceIn(defaultDimX * 0.6, defaultDimX * 1.8)
}
