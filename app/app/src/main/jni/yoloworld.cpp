// YOLO-World (NCNN) JNI 实现：加载模型 + 检测（YOLOv8式解码 + NMS）
#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include "net.h"
#include "mat.h"

static ncnn::Net g_net;
static bool g_loaded = false;

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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fengshui_app_YOLOWorldNcnn_nativeLoadModel(
    JNIEnv* env, jobject, jstring paramPath, jstring binPath) {
    const char* p = env->GetStringUTFChars(paramPath, 0);
    const char* b = env->GetStringUTFChars(binPath, 0);

    g_net.opt.use_vulkan_compute = false;
    g_net.opt.use_fp16_storage = true;
    g_net.opt.use_fp16_arithmetic = true;

    int r1 = g_net.load_param(p);
    int r2 = g_net.load_model(b);

    env->ReleaseStringUTFChars(paramPath, p);
    env->ReleaseStringUTFChars(binPath, b);

    g_loaded = (r1 == 0 && r2 == 0);
    return g_loaded ? JNI_TRUE : JNI_FALSE;
}

// 返回 float[]：[数量, cls, score, x1,y1,x2,y2, ...]（原图坐标）
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_fengshui_app_YOLOWorldNcnn_nativeDetect(
    JNIEnv* env, jobject, jbyteArray rgba, jint w, jint h,
    jfloat conf_thr, jfloat iou_thr) {
    if (!g_loaded) return nullptr;

    const int target = 640;
    jbyte* buf = env->GetByteArrayElements(rgba, nullptr);
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
        (const unsigned char*)buf, ncnn::Mat::PIXEL_RGBA2RGB, w, h, target, target);
    env->ReleaseByteArrayElements(rgba, buf, JNI_ABORT);

    ncnn::Extractor ex = g_net.create_extractor();
    ex.input("in0", in);
    ncnn::Mat out;
    if (ex.extract("out0", out) != 0) return nullptr;

    int nc = out.c;
    int num_classes = nc - 4;
    if (num_classes <= 0) return nullptr;
    int stride = out.w * out.h;

    std::vector<Object> objects;
    for (int i = 0; i < stride; i++) {
        float max_s = 0.f;
        int best_c = 0;
        for (int c = 0; c < num_classes; c++) {
            float s = out.channel(4 + c).row(0)[i];
            if (s > max_s) { max_s = s; best_c = c; }
        }
        if (max_s < conf_thr) continue;
        float x = out.channel(0).row(0)[i];
        float y = out.channel(1).row(0)[i];
        float bw = out.channel(2).row(0)[i];
        float bh = out.channel(3).row(0)[i];
        Object o{best_c, max_s, x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2};
        objects.push_back(o);
    }

    nms(objects, iou_thr);

    float sx = w / (float)target;
    float sy = h / (float)target;
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
