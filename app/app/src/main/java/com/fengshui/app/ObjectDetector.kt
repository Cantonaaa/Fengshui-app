package com.fengshui.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * K3/A1.4: 基于 TFLite 的 YOLO 检测器（运行 ultralytics 导出的 YOLO-World 固定词表模型）。
 * 模型为 YOLOv8 式输出：[1, 4+num_classes, num_anchors]，无 objectness，需在端上解码 + NMS。
 */
class ObjectDetector(private val context: Context) {

    data class Detection(
        val cls: String,
        val score: Float,
        val left: Float, val top: Float, val right: Float, val bottom: Float
    ) {
        val cx: Float get() = (left + right) / 2f
        val cy: Float get() = (top + bottom) / 2f
    }

    private var interpreter: Interpreter? = null
    private val inputSize = 640
    private var numClasses = 0
    private var classNames: List<String> = emptyList()

    /** 固定词表（与 rules/dependency_matrix 识别范围一致）。 */
    private val defaultVocab = listOf(
        "bed", "sofa", "dining table", "refrigerator", "potted plant", "toilet",
        "wardrobe", "door", "window", "stove", "pillar", "desk", "front desk"
    )

    /** 是否已加载模型。 */
    val isReady: Boolean get() = interpreter != null

    /** 加载 assets 中的 yolo_world.tflite。模型缺失时 isReady=false（不崩溃）。 */
    fun load(modelFile: String = "yolo_world.tflite") {
        try {
            val bytes = context.assets.open(modelFile).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buffer.put(bytes)
            buffer.rewind()
            interpreter = Interpreter(buffer)
            // 从模型输入推断输入尺寸（若与默认 640 不同则用模型值）
            try {
                interpreter?.getInputTensor(0)?.shape()?.let { s ->
                    if (s.size == 4 && s[2] > 0) {
                        // 保持 inputSize 为 640 默认；模型若不同在此可调整
                    }
                }
            } catch (_: Exception) {}
            setClasses(defaultVocab)
            Log.i(TAG, "检测模型已加载: $modelFile (${numClasses} 类)")
        } catch (e: Exception) {
            Log.w(TAG, "检测模型未就绪: ${e.message}（K3 模型待生成，检测暂不可用）")
            interpreter = null
        }
    }

    /** 设置固定词表（决定输出类数）。 */
    fun setClasses(vocab: List<String>) {
        classNames = vocab
        numClasses = vocab.size
    }

    /**
     * 在 Bitmap 上检测。返回按置信度排序的检测列表。
     */
    fun detect(bitmap: Bitmap, confThr: Float = 0.35f, iouThr: Float = 0.45f): List<Detection> {
        val interp = interpreter ?: return emptyList()
        // 1) 缩放到输入尺寸
        val scaled = scaleBitmap(bitmap, inputSize, inputSize)
        // 2) 转 RGB float [1,640,640,3]
        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val input = FloatArray(1 * inputSize * inputSize * 3)
        for (i in pixels.indices) {
            val c = pixels[i]
            input[i * 3] = ((c shr 16) and 0xFF) / 255f
            input[i * 3 + 1] = ((c shr 8) and 0xFF) / 255f
            input[i * 3 + 2] = (c and 0xFF) / 255f
        }
        // 3) 推理
        val output = Array(1) { Array(4 + numClasses) { FloatArray(8400) } }
        runCatching {
            interp.run(input, output)
        }.onFailure {
            Log.w(TAG, "推理失败: ${it.message}")
            return emptyList()
        }
        // 4) YOLOv8 式解码
        val preds = output[0]
        val boxes = ArrayList<Detection>()
        for (i in 0 until 8400) {
            var bestCls = 0
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val sc = preds[4 + c][i]
                if (sc > bestScore) { bestScore = sc; bestCls = c }
            }
            if (bestScore < confThr) continue
            val x = preds[0][i]; val y = preds[1][i]
            val w = preds[2][i]; val h = preds[3][i]
            boxes.add(Detection(
                cls = classNames.getOrElse(bestCls) { "obj$bestCls" },
                score = bestScore,
                left = x - w / 2f, top = y - h / 2f,
                right = x + w / 2f, bottom = y + h / 2f
            ))
        }
        // 5) 缩放到原图坐标
        val sx = bitmap.width.toFloat() / inputSize
        val sy = bitmap.height.toFloat() / inputSize
        val scaledBoxes = boxes.map {
            it.copy(left = it.left * sx, right = it.right * sx, top = it.top * sy, bottom = it.bottom * sy)
        }
        // 6) NMS
        return nms(scaledBoxes, iouThr).sortedByDescending { it.score }
    }

    private fun nms(boxes: List<Detection>, iouThr: Float): List<Detection> {
        val kept = ArrayList<Detection>()
        val sorted = boxes.sortedByDescending { it.score }
        for (b in sorted) {
            var ok = true
            for (k in kept) {
                if (iou(b, k) > iouThr) { ok = false; break }
            }
            if (ok) kept.add(b)
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = maxOf(a.left, b.left); val y1 = maxOf(a.top, b.top)
        val x2 = minOf(a.right, b.right); val y2 = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        if (inter <= 0f) return 0f
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return inter / (areaA + areaB - inter)
    }

    private fun scaleBitmap(src: Bitmap, w: Int, h: Int): Bitmap =
        if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)

    companion object {
        private const val TAG = "K3Detector"
    }
}
