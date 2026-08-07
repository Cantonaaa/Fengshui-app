package com.fengshui.app

import android.content.Context
import android.util.Log
import com.google.ar.core.Frame
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.jvm.Synchronized

private const val TAG = "A1PointCloud"

/**
 * A1.2: 点云采集器。
 * 每 N 帧从 ARCore 获取特征点云，降采样累积为房间 3D 数据，可导出 PLY。
 */
class PointCloudRecorder(
    private val maxPoints: Int = 300_000
) {
    private val points = ArrayList<FloatArray>()
    private var frameCount = 0

    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

    val size: Int get() = points.size

    /** 返回点云副本（供户型多边形计算）。 */
    @Synchronized
    fun getPoints(): List<FloatArray> = points.toList()

    /**
     * 稳健地面高度：中位数 + MAD 剔除离群点后的 5 分位数。
     * 抗 -99m 等特征点云垃圾点；点不足返回 null。用于物体定位的 y=floorHeight。
     */
    @Synchronized
    fun floorY(): Float? {
        if (points.size < 50) return null
        val ys = points.map { it[1] }
        val sorted = ys.sorted()
        val med = sorted[sorted.size / 2]
        if (!med.isFinite()) return null
        // MAD = 中位绝对偏差 * 1.4826（近似正态标准差）
        val devs = ys.map { abs(it - med) }.sorted()
        val mad = devs[devs.size / 2] * 1.4826f
        if (mad <= 1e-3f) return null
        val clean = ys.filter { abs(it - med) < 6f * mad }.sorted()
        if (clean.size < 30) return null
        val idx = minOf(clean.size - 1, (clean.size * 0.05f).toInt())
        val p = clean[idx]
        return if (p.isFinite()) p else null
    }

    /** 每帧调用；内部按 sampleEveryNFrames 与 stride 降采样。 */
    @Synchronized
    fun onFrame(frame: Frame, sampleEveryNFrames: Int = 5, stride: Int = 3) {
        frameCount++
        if (frameCount % sampleEveryNFrames != 0) return
        if (points.size >= maxPoints) return
        val cloud = frame.acquirePointCloud()
        try {
            val buf = cloud.points
            val n = buf.remaining() / 3
            var i = 0
            while (i < n) {
                if (points.size >= maxPoints) break
                buf.position(i * 3)
                val x = buf.get(); val y = buf.get(); val z = buf.get()
                points.add(floatArrayOf(x, y, z))
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                i += stride
            }
        } finally {
            cloud.release()
        }
    }

    /** 导出为 ASCII PLY（x,y,z），返回文件路径。 */
    @Synchronized
    fun exportPly(context: Context): File? {
        if (points.isEmpty()) return null
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "room_points_${System.currentTimeMillis()}.ply")
        FileOutputStream(file).bufferedWriter().use { w ->
            w.write("ply\n")
            w.write("format ascii 1.0\n")
            w.write("element vertex ${points.size}\n")
            w.write("property float x\nproperty float y\nproperty float z\n")
            w.write("end_header\n")
            for (p in points) {
                w.write("${p[0]} ${p[1]} ${p[2]}\n")
            }
        }
        Log.i(TAG, "导出 ${points.size} 点 -> ${file.absolutePath}")
        Log.i(TAG, "包围盒 x:[${minX},${maxX}] y:[${minY},${maxY}] z:[${minZ},${maxZ}]")
        return file
    }

    @Synchronized
    fun reset() {
        points.clear(); frameCount = 0
        minX = Float.MAX_VALUE; maxX = -Float.MAX_VALUE
        minY = Float.MAX_VALUE; maxY = -Float.MAX_VALUE
        minZ = Float.MAX_VALUE; maxZ = -Float.MAX_VALUE
    }
}
