package com.fengshui.app

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config.PlaneFindingMode
import com.google.ar.core.Plane
import io.github.sceneview.ar.ARScene
import kotlinx.coroutines.delay

private const val TAG = "A1Scan"

@Composable
fun ScanScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var planeCount by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("检查 ARCore...") }
    var arcoreReady by remember { mutableStateOf(false) }
    var permissionRequested by remember { mutableStateOf(false) }
    var pointCount by remember { mutableIntStateOf(0) }
    var objCount by remember { mutableIntStateOf(0) }
    val recorder = remember { PointCloudRecorder() }
    val detector = remember { ObjectDetector(context) }
    var detectorLoaded by remember { mutableStateOf(false) }
    val detFrameCounter = remember { intArrayOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (granted) status = "相机权限已授权，正在启动 AR..."
        else status = "未授权相机权限，无法扫描"
    }

    // 检查 ARCore 可用性（已侧载 1.55 应返回 INSTALLED）
    if (!arcoreReady) {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        status = when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                arcoreReady = true
                "ARCore 就绪 (v1.55)"
            }
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "ARCore 未安装"
            else -> "此设备不支持 ARCore: $availability"
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (!cameraGranted) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(status)
                    Text(
                        "请授权相机权限以进行房间扫描",
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else if (!arcoreReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(status)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ARScene(
                        modifier = Modifier.fillMaxSize(),
                        sessionConfiguration = { session, config ->
                            config.planeFindingMode = PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                            Log.i(TAG, "session 配置完成")
                        },
                        onSessionFailed = { e ->
                            status = "AR 会话失败: ${e.message}"
                            Log.e(TAG, "session failed", e)
                        },
                        onSessionUpdated = { session, frame ->
                            val planes = session.getAllTrackables(Plane::class.java)
                            if (planes.size != planeCount) {
                                planeCount = planes.size
                                Log.i(TAG, "已检测平面: ${planes.size}")
                            }
                            recorder.onFrame(frame)
                            // A1.4：选帧检测（每 15 帧一次）+ 3D 定位
                            detFrameCounter[0]++
                            if (detectorLoaded && detFrameCounter[0] % 15 == 0) {
                                try {
                                    frame.acquireCameraImage().use { img ->
                                        val bmp = YuvUtils.toBitmap(img)
                                        if (bmp != null) {
                                            val dets = detector.detect(bmp)
                                            if (dets.isNotEmpty()) {
                                                objCount = dets.size
                                                val floorH = recorder.minY
                                                dets.take(6).forEach { d ->
                                                    val pos = ObjectLocalizer.projectToFloor(
                                                        frame.camera, d.cx, d.cy, floorH
                                                    )
                                                    val p = if (pos != null) "%.1f,%.1f".format(pos[0], pos[2]) else "?"
                                                    Log.i(TAG, "检测 ${d.cls} score=%.2f @($p)".format(d.score))
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "检测帧处理失败: ${e.message}")
                                }
                            }
                        }
                    )
                    Column(Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) {
                        Text(
                            "检测到平面: $planeCount",
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Color.White
                        )
                        Text(
                            "点云点数: $pointCount",
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Color.White
                        )
                        Text(
                            "识别物体: ${if (detectorLoaded) objCount.toString() else "模型未就绪"}",
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Color.White
                        )
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(status)
                        Text("缓慢移动手机，环绕房间完整走一圈（A1.2 点云采集）")
                        Button(
                            onClick = {
                                val f = recorder.exportPly(context)
                                status = if (f != null) "已导出: ${f.name}" else "尚无点云"
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text("导出点云 (PLY)") }
                        Button(
                            onClick = {
                                val poly = RoomPolygon.buildPolygon(recorder.getPoints())
                                status = if (poly != null) {
                                    "户型多边形: ${poly.vertices.size} 顶点, 面积 ${"%.1f".format(poly.area)} m²"
                                } else {
                                    "点云不足，无法生成户型"
                                }
                                Log.i(TAG, status)
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        ) { Text("生成户型 (面积)") }
                        Text(
                            "← 返回",
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    // 定时刷新点云计数显示
    LaunchedEffect(arcoreReady) {
        while (arcoreReady) {
            pointCount = recorder.size
            kotlinx.coroutines.delay(500)
        }
    }

    // 加载 K3 检测模型（assets/yolo_world.tflite）
    LaunchedEffect(Unit) {
        detector.load()
        detectorLoaded = detector.isReady
        if (detectorLoaded) Log.i(TAG, "K3 检测模型就绪") else Log.w(TAG, "K3 检测模型缺失，检测暂不可用")
    }

    // 首次进入且未授权时发起授权（LaunchedEffect：须在合成后调用 launcher，避免崩溃）
    LaunchedEffect(Unit) {
        if (!cameraGranted && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}
