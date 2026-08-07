package com.fengshui.app

import org.junit.Test

class ScanFrameBufferTest {

    private fun snap(ox: Float, oz: Float): ObjectLocalizer.CameraSnapshot =
        ObjectLocalizer.CameraSnapshot(500f, 500f, 320f, 240f, ox, 1.5f, oz, 0f, 0f, 0f, 1f)

    @Test
    fun shouldCapture_firstAlwaysTrue() {
        val buf = ScanFrameBuffer()
        // 首帧必然缓存（lastPose 为空）
        assert(buf.shouldCapture(snap(0f, 0f)))
        // 连续未捕获（无 capture 推进 lastPose）仍视为待缓存 —— 语义一致
        assert(buf.shouldCapture(snap(0.05f, 0f)))
    }

    @Test
    fun cumulativeYaw_notFullCircleByDefault() {
        val buf = ScanFrameBuffer()
        assert(!buf.isFullCircle)
    }
}
