package com.fengshui.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 录像后处理：对缓冲关键帧批量检测 → 多帧融合（类别投票 + 位置/尺寸中值）→ 器物清单。
 * 每物体需 ≥2 帧确认（更稳，去单帧误检）；位置/尺寸取中值抗单帧误差。
 */
object PostScanProcessor {

    /** 单帧检测原始记录。 */
    private data class RawDet(
        val cls: String,
        val score: Float,
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
            val bmp = decodeCapped(fr.jpeg)
            if (bmp != null) {
                try {
                    // 基础置信放低到 0.15 让 JNI 放行低分框，再按类别分档过滤：
                    // 衣柜 0.20 / 门窗 0.25 / 其余 0.35（嵌入式低对比物召回更好，多帧融合兜底误检）
                    val dets = detector.detect(bmp, confThr = 0.15f).filter { accept(it) }
                    for (d in dets) {
                        if (d.cls == "未识别") continue   // 未辨不入器物
                        val pos = ObjectLocalizer.projectToFloor(fr.snap, d.cx, d.bottom, fr.floorH) ?: continue
                        val dist = Math.hypot(
                            pos[0].toDouble() - fr.snap.ox.toDouble(),
                            pos[2].toDouble() - fr.snap.oz.toDouble()
                        )
                        val (w, h) = SizeEstimator.estimate(
                            d.right - d.left, d.bottom - d.top, fr.snap.fx, fr.snap.fy, dist
                        )
                        // A2 高度一致性：只框到局部/异常合并框过滤，防位置与尺寸污染
                        if (!heightConsistent(h, d.cls)) continue
                        val (ddx, ddz) = FactsBuilder.defaultDims(d.cls)
                        raws.add(RawDet(d.cls, d.score, pos[0].toDouble(), pos[2].toDouble(),
                            SizeEstimator.footprintWidth(w, ddx), ddz))
                    }
                } finally {
                    bmp.recycle()   // 及时回收，防 l@960 推理+大位图 OOM
                }
            }
            onProgress(i + 1, frames.size)
        }
        return canonicalizeScan(fuse(raws))
    }

    /** 限幅解码：长边 ≤ maxSide（默认 1280），降低位图内存；JNI 仍缩放到模型输入。 */
    private fun decodeCapped(jpeg: ByteArray, maxSide: Int = 1280): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w > 0 && h > 0) {
            val longSide = maxOf(w, h)
            while (longSide / sample > maxSide) sample *= 2
        }
        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opt)
    }

    /** 按类别分档置信阈值（嵌入式/低对比物放低门槛）。 */
    private fun accept(d: YOLOWorldNcnn.Detection): Boolean {
        val min = when (d.cls) {
            "wardrobe" -> 0.20f
            "door", "window" -> 0.25f
            "book" -> 0.30f   // 书为小物，放宽（作书柜验证器）
            else -> 0.35f
        }
        return d.score >= min
    }

    /**
     * 归一化 + 书柜双验证（融合后）：
     *  bookshelf 邻近 book（<2m）→ 映射 study（激活书房规则），否则丢弃（防衣柜误判成书柜）；
     *  book 仅作验证器，丢弃不进报告；safe → finance_room。
     */
    fun canonicalizeScan(objects: List<ScanObject>): List<ScanObject> {
        val books = objects.filter { it.type == "book" }
        return objects.mapNotNull { o ->
            when (o.type) {
                "book" -> null
                "bookshelf" -> {
                    if (books.any { Math.hypot(it.x - o.x, it.z - o.z) < 2.0 }) o.copy(type = "study") else null
                }
                "safe" -> o.copy(type = "finance_room")
                else -> o
            }
        }
    }

    /** 同类聚类（2.5m）+ 中值；≥2 帧确认，或单帧高置信（≥0.6）直收（D1）。 */
    private fun fuse(raws: List<RawDet>): List<ScanObject> {
        val result = mutableListOf<ScanObject>()
        for (type in raws.map { it.cls }.distinct()) {
            val ofType = raws.filter { it.cls == type }
            val clusters = greedyCluster(ofType, 2.5)
            for (cl in clusters) {
                if (cl.size < 2 && !(cl.size == 1 && cl[0].score >= 0.6f)) continue
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
