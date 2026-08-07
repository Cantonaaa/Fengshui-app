package com.fengshui.app

import android.graphics.BitmapFactory

/**
 * 录像后处理：对缓冲关键帧批量检测 → 多帧融合（类别投票 + 位置/尺寸中值）→ 器物清单。
 * 每物体需 ≥2 帧确认（更稳，去单帧误检）；位置/尺寸取中值抗单帧误差。
 */
object PostScanProcessor {

    /** 单帧检测原始记录。 */
    private data class RawDet(
        val cls: String,
        val x: Double, val z: Double,
        val dimX: Double?, val dimZ: Double?
    )

    /**
     * @param frames     缓冲关键帧
     * @param detector   检测器（复用 YOLOWorldNcnn）
     * @param onProgress (完成帧数, 总帧数)
     * @return 融合后的器物列表（类同 AppState.recordObject 产物）
     */
    fun process(
        frames: List<ScanFrameBuffer.FrameRec>,
        detector: YOLOWorldNcnn,
        onProgress: (Int, Int) -> Unit
    ): List<ScanObject> {
        val raws = ArrayList<RawDet>()
        frames.forEachIndexed { i, fr ->
            val bmp = BitmapFactory.decodeByteArray(fr.jpeg, 0, fr.jpeg.size)
            if (bmp != null) {
                val dets = detector.detect(bmp)
                for (d in dets) {
                    if (d.cls == "未识别") continue   // 未辨不入器物
                    val pos = ObjectLocalizer.projectToFloor(fr.snap, d.cx, d.bottom, fr.floorH) ?: continue
                    val dist = Math.hypot(
                        pos[0].toDouble() - fr.snap.ox.toDouble(),
                        pos[2].toDouble() - fr.snap.oz.toDouble()
                    )
                    val (w, _) = SizeEstimator.estimate(
                        d.right - d.left, d.bottom - d.top, fr.snap.fx, fr.snap.fy, dist
                    )
                    val (ddx, ddz) = FactsBuilder.defaultDims(d.cls)
                    raws.add(RawDet(d.cls, pos[0].toDouble(), pos[2].toDouble(),
                        SizeEstimator.footprintWidth(w, ddx), ddz))
                }
            }
            onProgress(i + 1, frames.size)
        }
        return fuse(raws)
    }

    /** 同类聚类（2.5m）+ ≥2 帧 + 中值。 */
    private fun fuse(raws: List<RawDet>): List<ScanObject> {
        val result = mutableListOf<ScanObject>()
        for (type in raws.map { it.cls }.distinct()) {
            val ofType = raws.filter { it.cls == type }
            val clusters = greedyCluster(ofType, 2.5)
            for (cl in clusters) {
                if (cl.size < 2) continue
                val xs = cl.map { it.x }.sorted()
                val zs = cl.map { it.z }.sorted()
                result.add(ScanObject(
                    type,
                    xs[xs.size / 2],
                    zs[zs.size / 2],
                    cl.mapNotNull { it.dimX }.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else null },
                    cl.mapNotNull { it.dimZ }.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else null }
                ))
            }
        }
        return result
    }

    private fun greedyCluster(pts: List<RawDet>, radius: Double): List<List<RawDet>> {
        val clusters = mutableListOf<MutableList<RawDet>>()
        for (p in pts) {
            val c = clusters.firstOrNull { cl ->
                val cx = cl.map { it.x }.average()
                val cz = cl.map { it.z }.average()
                Math.hypot(p.x - cx, p.z - cz) <= radius
            }
            if (c != null) c.add(p) else clusters.add(mutableListOf(p))
        }
        return clusters
    }
}
