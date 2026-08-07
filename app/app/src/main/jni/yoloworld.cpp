// YOLO-World (NCNN) JNI 实现：加载模型 + 检测（YOLOv8式解码 + NMS）
// 支持两种输入：RGBA 位图(nativeDetect) 与 ARCore YUV_420_888 直送(nativeDetectYuv)
#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include "net.h"
#include "mat.h"

static ncnn::Net g_net;
static bool g_loaded = false;
// 模型输入尺寸：yolov8l-worldv2 @ 960（后处理离线批量，时间充裕，取高分辨率提精度）
static const int TARGET = 960;

struct Object {
    int cls;
    float score;
    float x1, y1, x2, y2;
};

static inline float intersection_area(const Object& a, const Object& b) {
    float x1 = std::max(a.x1, b.x1);
    float y1 = std::max(a.y1, b.y1);
    float x2 = std::min(a.x2, b.x2);
    float y2 = std::min(a.y2, b.y2);
    if (x2 - x1 < 0 || y2 - y1 < 0) return 0.f;
    return (x2 - x1) * (y2 - y1);
}

static void nms(std::vector<Object>& objects, float iou_thr) {
    std::sort(objects.begin(), objects.end(),
              [](const Object& a, const Object& b) { return a.score > b.score; });
    std::vector<Object> kept;
    for (const auto& o : objects) {
        bool ok = true;
        for (const auto& k : kept) {
            float inter = intersection_area(o, k);
            float uni = (o.x2 - o.x1) * (o.y2 - o.y1) + (k.x2 - k.x1) * (k.y2 - k.y1) - inter;
            if (uni <= 0.f) { ok = true; break; }
            if (inter / uni > iou_thr) { ok = false; break; }
        }
        if (ok) kept.push_back(o);
    }
    objects.swap(kept);
}

// YUV(420) -> RGB，系数与 YuvUtils 一致
static inline void yuv2rgb(unsigned char y, unsigned char u, unsigned char v,
                           unsigned char& r, unsigned char& g, unsigned char& b) {
    int rr = (int)(y + 1.402f * ((int)v - 128));
    int gg = (int)(y - 0.344f * ((int)u - 128) - 0.714f * ((int)v - 128));
    int bb = (int)(y + 1.772f * ((int)u - 128));
    r = (unsigned char)(rr < 0 ? 0 : (rr > 255 ? 255 : rr));
    g = (unsigned char)(gg < 0 ? 0 : (gg > 255 ? 255 : gg));
    b = (unsigned char)(bb < 0 ? 0 : (bb > 255 ? 255 : bb));
}

// YUV_420_888 平面 -> TARGETxTARGET RGB Mat（一步完成 YUV→RGB + 双线性缩放）
static ncnn::Mat yuvToMat(const unsigned char* y, const unsigned char* u, const unsigned char* v,
                          int w, int h, int yRowStride, int uvRowStride, int uvPixelStride) {
    std::vector<unsigned char> rgb(TARGET * TARGET * 3);
    for (int ty = 0; ty < TARGET; ty++) {
        float syf = ty * h / (float)TARGET;
        int sy0 = (int)syf;
        int sy1 = sy0 + 1 < h ? sy0 + 1 : sy0;
        float fy = syf - sy0;
        for (int tx = 0; tx < TARGET; tx++) {
            float sxf = tx * w / (float)TARGET;
            int sx0 = (int)sxf;
            int sx1 = sx0 + 1 < w ? sx0 + 1 : sx0;
            float fx = sxf - sx0;

            unsigned char r0, g0, b0, r1, g1, b1, r2, g2, b2, r3, g3, b3;
            auto uvAt = [&](int xx, int yy, const unsigned char* p) {
                return p[(yy / 2) * uvRowStride + (xx / 2) * uvPixelStride];
            };
            yuv2rgb(y[sy0 * yRowStride + sx0], uvAt(sx0, sy0, u), uvAt(sx0, sy0, v), r0, g0, b0);
            yuv2rgb(y[sy0 * yRowStride + sx1], uvAt(sx1, sy0, u), uvAt(sx1, sy0, v), r1, g1, b1);
            yuv2rgb(y[sy1 * yRowStride + sx0], uvAt(sx0, sy1, u), uvAt(sx0, sy1, v), r2, g2, b2);
            yuv2rgb(y[sy1 * yRowStride + sx1], uvAt(sx1, sy1, u), uvAt(sx1, sy1, v), r3, g3, b3);

            float r = (r0 * (1 - fx) + r1 * fx) * (1 - fy) + (r2 * (1 - fx) + r3 * fx) * fy;
            float g = (g0 * (1 - fx) + g1 * fx) * (1 - fy) + (g2 * (1 - fx) + g3 * fx) * fy;
            float b = (b0 * (1 - fx) + b1 * fx) * (1 - fy) + (b2 * (1 - fx) + b3 * fx) * fy;

            unsigned char* dst = &rgb[(ty * TARGET + tx) * 3];
            dst[0] = (unsigned char)(r + 0.5f);
            dst[1] = (unsigned char)(g + 0.5f);
            dst[2] = (unsigned char)(b + 0.5f);
        }
    }
    return ncnn::Mat::from_pixels(rgb.data(), ncnn::Mat::PIXEL_RGB, TARGET, TARGET);
}

// 共享检测：网络 forward + [17,8400] 解码 + NMS，返回 float[]（原图坐标）
static jfloatArray detectFromMat(JNIEnv* env, ncnn::Mat in,
                                 int src_w, int src_h, float conf_thr, float iou_thr, float margin_thr) {
    // 输入归一化到 [0,1]：模型按 [0,1] 导出/训练（喂 [0,255] 会致类别饱和误判）
    {
        float* p = (float*)in.data;
        const int n = in.total();
        const float k = 0.00392156862745098f;   // 1/255
        for (int i = 0; i < n; i++) p[i] *= k;
    }
    ncnn::Extractor ex = g_net.create_extractor();
    ex.input("in0", in);
    ncnn::Mat out;
    if (ex.extract("out0", out) != 0) return nullptr;

    // 输出布局：[h=17行, w=8400列]；行0-3=bbox(cx,cy,w,h)，行4..=各分类分数（含 sigmoid）
    int num_classes = out.h - 4;
    if (num_classes <= 0) return nullptr;
    int num_boxes = out.w;

    std::vector<Object> objects;
    for (int i = 0; i < num_boxes; i++) {
        float s1 = 0.f, s2 = 0.f;
        int c1 = 0;
        for (int c = 0; c < num_classes; c++) {
            float s = out.row(4 + c)[i];
            if (s > s1) { s2 = s1; s1 = s; c1 = c; }
            else if (s > s2) s2 = s;
        }
        if (s1 < conf_thr) continue;
        // 分类置信度 margin：top1-top2 过小 → 模型不确定 → 标记"未识别"(cls=-1)
        int cls = (s1 - s2) < margin_thr ? -1 : c1;
        float x = out.row(0)[i];
        float y = out.row(1)[i];
        float bw = out.row(2)[i];
        float bh = out.row(3)[i];
        objects.push_back(Object{cls, s1, x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2});
    }

    nms(objects, iou_thr);

    float sx = src_w / (float)TARGET;
    float sy = src_h / (float)TARGET;
    jfloatArray res = env->NewFloatArray(1 + (jint)objects.size() * 6);
    jfloat* r = env->GetFloatArrayElements(res, nullptr);
    r[0] = (float)objects.size();
    int idx = 1;
    for (const auto& o : objects) {
        r[idx++] = (float)o.cls;
        r[idx++] = o.score;
        r[idx++] = o.x1 * sx;
        r[idx++] = o.y1 * sy;
        r[idx++] = o.x2 * sx;
        r[idx++] = o.y2 * sy;
    }
    env->ReleaseFloatArrayElements(res, r, 0);
    return res;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fengshui_app_YOLOWorldNcnn_nativeLoadModel(
    JNIEnv* env, jobject, jstring paramPath, jstring binPath) {
    const char* p = env->GetStringUTFChars(paramPath, 0);
    const char* b = env->GetStringUTFChars(binPath, 0);

    g_net.opt.use_vulkan_compute = false;
    g_net.opt.use_fp16_storage = false;      // 保持 fp32，与验证数值一致
    g_net.opt.use_fp16_arithmetic = false;
    g_net.opt.num_threads = 4;               // 已去除 Einsum，多线程安全
    setenv("OMP_STACKSIZE", "128M", 1);      // 加大 OpenMP 栈

    int r1 = g_net.load_param(p);
    int r2 = g_net.load_model(b);

    env->ReleaseStringUTFChars(paramPath, p);
    env->ReleaseStringUTFChars(binPath, b);

    g_loaded = (r1 == 0 && r2 == 0);
    return g_loaded ? JNI_TRUE : JNI_FALSE;
}

// 返回 float[]：[数量, cls, score, x1,y1,x2,y2, ...]（原图坐标）；输入 RGBA 位图
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_fengshui_app_YOLOWorldNcnn_nativeDetect(
    JNIEnv* env, jobject, jbyteArray rgba, jint w, jint h,
    jfloat conf_thr, jfloat iou_thr, jfloat margin_thr) {
    if (!g_loaded) return nullptr;

    jbyte* buf = env->GetByteArrayElements(rgba, nullptr);
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
        (const unsigned char*)buf, ncnn::Mat::PIXEL_RGBA2RGB, w, h, TARGET, TARGET);
    env->ReleaseByteArrayElements(rgba, buf, JNI_ABORT);

    return detectFromMat(env, in, w, h, conf_thr, iou_thr, margin_thr);
}

// 返回 float[]：[数量, cls, score, x1,y1,x2,y2, ...]（原图坐标）；输入 YUV_420_888 平面直送
// 各平面 ByteBuffer 需为 DirectBuffer；yPos/uPos/vPos 为缓冲区内部偏移（通常 0）
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_fengshui_app_YOLOWorldNcnn_nativeDetectYuv(
    JNIEnv* env, jobject,
    jobject yBuf, jobject uBuf, jobject vBuf,
    jint w, jint h,
    jint yRowStride, jint uvRowStride, jint uvPixelStride,
    jint yPos, jint uPos, jint vPos,
    jfloat conf_thr, jfloat iou_thr, jfloat margin_thr) {
    if (!g_loaded) return nullptr;

    const unsigned char* y = (const unsigned char*)env->GetDirectBufferAddress(yBuf);
    const unsigned char* u = (const unsigned char*)env->GetDirectBufferAddress(uBuf);
    const unsigned char* v = (const unsigned char*)env->GetDirectBufferAddress(vBuf);
    if (!y || !u || !v) return nullptr;

    ncnn::Mat in = yuvToMat(y + yPos, u + uPos, v + vPos, w, h,
                            yRowStride, uvRowStride, uvPixelStride);
    return detectFromMat(env, in, w, h, conf_thr, iou_thr, margin_thr);
}
