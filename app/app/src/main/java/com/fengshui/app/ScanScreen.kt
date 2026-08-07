package com.fengshui.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.fengshui.solver.Pt
import io.github.sceneview.ar.ARScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val TAG = "A1Scan"

@Composable
fun ScanScreen(onBack: () -> Unit, onAnalyze: () -> Unit) {
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
    var unknownCount by remember { mutableIntStateOf(0) }
    var azimuthDeg by remember { mutableStateOf("--") }
    var northSet by remember { mutableStateOf(AppState.hasNorth()) }
    val recorder = remember { PointCloudRecorder() }
    val detector = remember { YOLOWorldNcnn(context) }
    val northHelper = remember { NorthHelper(context) }
    val scope = rememberCoroutineScope()
    var detectorLoaded by remember { mutableStateOf(false) }
    val detFrameCounter = remember { intArrayOf(0) }
    // AR 线程最近一帧相机快照（供校北读取；校北时须把手机指向磁北）
    val latestSnap = remember { arrayOfNulls<ObjectLocalizer.CameraSnapshot>(1) }

    // A1.4：检测专用后台线程（取帧/推理不阻塞 AR 会话回调线程，扫描画面无感）
    val detectThread = remember { HandlerThread("fengshui-detector").apply { start() } }
    val detectHandler = remember { Handler(detectThread.looper) }
    val detectBusy = remember { AtomicBoolean(false) }

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
                            // A1.4：选帧检测（每 15 帧一次），异步到后台线程，不阻塞 AR 回调
                            detFrameCounter[0]++
                            if (detectorLoaded && detFrameCounter[0] % 15 == 0) {
                                val img = try {
                                    frame.acquireCameraImage()
                                } catch (e: Exception) {
                                    null
                                }
                                if (img != null) {
                                    // 回调内快照相机位姿/内参 + 地面高度（纯数据，跨线程安全）
                                    val snap = ObjectLocalizer.snapshot(frame.camera)
                                    latestSnap[0] = snap
                                    val floorH = recorder.floorY() ?: recorder.minY
                                    if (detectBusy.compareAndSet(false, true)) {
                                        try {
                                            detectHandler.post {
                                                try {
                                                    img.use {
                                                        val dets = detector.detectYuv(it)
                                                        if (dets.isNotEmpty()) {
                                                            objCount = dets.size
                                                            dets.take(6).forEach { d ->
                                                                // 用框底边中心（物体立脚点）向地面投影，避免水平射线无法求交
                                                                val pos = if (floorH.isFinite())
                                                                    ObjectLocalizer.projectToFloor(snap, d.cx, d.bottom, floorH)
                                                                else null
                                                                if (pos != null) {
                                                                    if (d.cls == "未识别") {
                                                                        AppState.recordUnknown()   // 不在类别内，只计数不录入规则
                                                                    } else {
                                                                        // 尺寸估计：bbox×焦距×水平距离 → 占地宽钳制到类型范围
                                                                        val dist = Math.hypot(
                                                                            pos[0].toDouble() - snap.ox.toDouble(),
                                                                            pos[2].toDouble() - snap.oz.toDouble()
                                                                        )
                                                                        val (w, _) = SizeEstimator.estimate(
                                                                            d.right - d.left, d.bottom - d.top,
                                                                            snap.fx, snap.fy, dist
                                                                        )
                                                                        val (ddx, ddz) = FactsBuilder.defaultDims(d.cls)
                                                                        val dimX = SizeEstimator.footprintWidth(w, ddx)
                                                                        AppState.recordObject(d.cls, pos[0].toDouble(), pos[2].toDouble(), dimX, ddz)
                                                                    }
                                                                    val p = "%.1f,%.1f".format(pos[0], pos[2])
                                                                    Log.i(TAG, "检测 ${d.cls} score=${"%.2f".format(d.score)} @($p)")
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "检测帧处理失败: ${e.message}")
                                                } finally {
                                                    detectBusy.set(false)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // post 失败（线程已退出）：释放资源
                                            img.close()
                                            detectBusy.set(false)
                                        }
                                    } else {
                                        img.close() // 上一帧仍在推理，丢弃本帧
                                    }
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
                        Text(
                            "磁北: $azimuthDeg° ${if (northSet) "·已校准" else "·未校准"}",
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Color.White
                        )
                        Text(
                            "未识别: $unknownCount",
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
                    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
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
                        Button(
                            onClick = {
                                val snap = latestSnap[0]
                                if (snap != null) {
                                    AppState.northAngle = ObjectLocalizer.cameraForwardHeading(snap)
                                    AppState.save(context)
                                    northSet = true
                                    status = "北向已校准（沿当前朝向前方为磁北）"
                                } else {
                                    status = "等待 AR 帧：请把手机朝向磁北（罗盘读数 0°）"
                                }
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        ) { Text(if (northSet) "重新校准北向" else "校准北向（朝向磁北后点击）") }
                        Button(
                            onClick = {
                                val objects = AppState.snapshotObjects()
                                if (objects.isEmpty()) {
                                    status = "未识别到物体，请先扫描"
                                } else if (AppState.northAngle == null) {
                                    status = "请先校准北向"
                                } else if (AppState.guaInfo == null) {
                                    status = "请先设置生辰（首页）"
                                } else {
                                    status = "分析中..."
                                    scope.launch(Dispatchers.Default) {
                                        try {
                                            val poly: List<Pt> = RoomPolygon.buildPolygon(recorder.getPoints())
                                                ?.vertices?.map { Pt(it[0].toDouble(), it[1].toDouble()) }
                                                ?: FactsBuilder.boundingPolygon(objects)
                                            val facts = FactsBuilder.build(objects, poly, AppState.northAngle!!)
                                            val rules = RuleEngine.loadRules(context)
                                            val hits = RuleEngine.analyze(facts, rules, AppState.guaInfo)
                                            val badHits = hits.filter { it.severity != "吉" }
                                            val plan = solveRemediation(facts, rules, badHits, AppState.guaInfo!!)
                                            val infos = sectorInfo(objects, poly, AppState.northAngle!!)
                                            AppState.analysisResult = AnalysisResult(
                                                AppState.guaInfo, true, objects.size, hits, infos, plan, AppState.unknownCount, poly
                                            )
                                            withContext(Dispatchers.Main) {
                                                status = "分析完成：命中 ${hits.size} 条规则"
                                                onAnalyze()
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "分析失败: ${e.message}", e)
                                            withContext(Dispatchers.Main) { status = "分析失败: ${e.message}" }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        ) { Text("完成扫描并分析") }
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
            unknownCount = AppState.unknownCount
            kotlinx.coroutines.delay(500)
        }
    }

    // 加载 K3 检测模型（assets/yolo_world.param/.bin），后台线程避免 UI 冻结
    LaunchedEffect(Unit) {
        AppState.resetScan()   // 新扫描会话
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            detector.load()
        }
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

    // 离开扫描页时停止检测线程、释放资源
    DisposableEffect(Unit) {
        onDispose {
            detectBusy.set(true)
            detectHandler.removeCallbacksAndMessages(null)
            detectThread.quitSafely()
            northHelper.stop()
        }
    }

    // 罗盘方位角轮询（磁北引导）
    LaunchedEffect(Unit) {
        northHelper.start()
        while (true) {
            azimuthDeg = (((northHelper.azimuth() * 180 / Math.PI).roundToInt() + 360) % 360).toString()
            kotlinx.coroutines.delay(200)
        }
    }
}
