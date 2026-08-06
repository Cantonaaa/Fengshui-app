#!/usr/bin/env python3
"""K3：YOLO-World 固定词表 TFLite 模型生成管线。

依赖环境（需在可联网、可装大依赖的机器上运行）：
    pip install ultralytics tensorflow onnx onnxruntime --index-url <清华/镜像>

步骤：
    1. 加载 YOLO-World 预训练权重（ultralytics 格式 yolov8s-worldv2.pt）
    2. 设置固定词表（= rules/dependency_matrix 的识别范围）
    3. 导出 TFLite（含 YOLOv8 解码结构）
    4. 产物放入 app/app/src/main/assets/yolo_world.tflite

注意：app 端 ObjectDetector 按 YOLOv8 式输出 [1,4+nc,8400] 解码（无 objectness）。
      若 ultralytics 导出结构不同，需在 ObjectDetector 中适配解码。
"""
import subprocess
import sys

# 与 dependency_matrix v3 一致的固定词表
VOCAB = [
    "bed", "sofa", "dining table", "refrigerator", "potted plant", "toilet",
    "wardrobe", "door", "window", "stove", "pillar", "desk", "front desk",
]

MODEL_NAME = "yolov8s-worldv2.pt"  # ultralytics 官方预训练
ASSET_OUT = "app/app/src/main/assets/yolo_world.tflite"


def main():
    print("== K3 模型管线 ==")
    print(f"词表({len(VOCAB)}): {VOCAB}")

    print("[1/3] 加载 YOLO-World...")
    from ultralytics import YOLOWorld
    model = YOLOWorld(MODEL_NAME)

    print("[2/3] 设置固定词表...")
    model.set_classes(VOCAB)

    print("[3/3] 导出 TFLite...")
    # imgsz 640, 固定词表后输出 [1, 4+len(VOCAB), 8400]
    model.export(format="tflite", imgsz=640, nms=False)
    # ultralytics 产物通常在 runs/detect/export/ 下
    print("导出完成，请将 .tflite 复制到:", ASSET_OUT)
    print("提示: 若导出结构非 [1,4+nc,8400]，请适配 ObjectDetector 解码。")


if __name__ == "__main__":
    main()
