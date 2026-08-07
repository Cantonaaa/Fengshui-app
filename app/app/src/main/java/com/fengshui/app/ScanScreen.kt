package com.fengshui.app

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    var azimuthDeg by remember { mutableStateOf("--") }
    var northSet by remember { mutableStateOf(false) }   // 每次入宅须定向正盘（北向不跨会话）
    val recorder = remember { PointCloudRecorder() }
    val detector = remember { YOLOWorldNcnn(context) }
    val northHelper = remember { NorthHelper(context) }
    val scope = rememberCoroutineScope()

    var detectorLoaded by remember { mutableStateOf(false) }
    // AR 线程最近一帧相机快照（供校北读取；校北时须把手机指向磁北）
    val latestSnap = remember { arrayOfNulls<ObjectLocalizer.CameraSnapshot>(1) }

    fun calibrateNorth() {
        val snap = latestSnap[0]
        if (snap != null) {
            AppState.northAngle = ObjectLocalizer.cameraForwardHeading(snap)
            AppState.save(context)
            northSet = true
            status = "罗盘已正（沿当前朝向前方为北）"
        } else {
            status = "候 AR 帧至：请徐转罗盘，使示数归于零度"
        }
    }

    // 录像后处理：关键帧缓冲（不即时检测）
    val frameBuffer = remember { ScanFrameBuffer() }
    var frameCount by remember { mutableIntStateOf(0) }
    var circleDone by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var procProgress by remember { mutableIntStateOf(0) }

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
                            // 录像后处理：相机移动足够则缓存关键帧（JPEG+位姿），不即时检测
                            val snap = ObjectLocalizer.snapshot(frame.camera)
                            latestSnap[0] = snap
                            val floorH = recorder.floorY() ?: recorder.minY
                            if (detectorLoaded && frameBuffer.shouldCapture(snap)) {
                                val img = try {
                                    frame.acquireCameraImage()
                                } catch (e: Exception) {
                                    null
                                }
                                if (img != null) {
                                    try {
                                        img.use { frameBuffer.capture(it, snap, floorH) }
                                        frameCount = frameBuffer.size
                                    } catch (e: Exception) {
                                        Log.w(TAG, "关键帧缓存失败: ${e.message}")
                                    }
                                }
                            }
                            // 自动提示：已绕宅一周
                            if (frameBuffer.isFullCircle && !circleDone) {
                                circleDone = true
                                status = "已绕宅一周，可勘毕起批"
                            }
                        }
                    )
                    if (processing) {
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            TheoryCarousel()
                            Text(
                                "起批中 $procProgress%",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .padding(top = 4.dp)
                            )
                        }
                    } else if (!northSet) {
                        CalibrationGuide(
                            azimuthText = azimuthDeg,
                            onCalibrate = { calibrateNorth() },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp, start = 12.dp, end = 12.dp)
                        )
                    } else {
                        Row(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp, start = 12.dp, end = 12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(Modifier.weight(1f)) {
                                OverlayChip("平面 $planeCount")
                                OverlayChip("星点 $pointCount")
                                OverlayChip("关键帧 $frameCount")
                                if (circleDone) OverlayChip("已绕宅一周", accent = androidx.compose.ui.graphics.Color(0xFFFFD54F))
                                OverlayChip("罗盘已正", accent = androidx.compose.ui.graphics.Color(0xFF8FD4A0))
                            }
                            CompassView(azimuthDeg, northSet)
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "徐行绕室，环顾一周",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val btnShape = RoundedCornerShape(12.dp)
                        Button(
                            onClick = { calibrateNorth() },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = btnShape
                        ) { Text(if (northSet) "重定罗盘" else "定向正盘（罗盘示数 0° 时点按）") }
                        Button(
                            onClick = {
                                if (processing) return@Button
                                val gua = AppState.guaInfo
                                if (AppState.northAngle == null) { status = "请先定向正盘"; return@Button }
                                if (gua == null) { status = "请先定生辰（首页）"; return@Button }
                                processing = true
                                procProgress = 0
                                status = "起批中..."
                                scope.launch(Dispatchers.Default) {
                                    try {
                                        val frames = frameBuffer.all()
                                        if (frames.isEmpty()) {
                                            withContext(Dispatchers.Main) {
                                                processing = false
                                                status = "尚无摄帧，请先绕宅勘察"
                                            }
                                            return@launch
                                        }
                                        val objects = PostScanProcessor.process(frames, detector) { done, total ->
                                            procProgress = done * 100 / maxOf(total, 1)
                                        }
                                        val poly: List<Pt> = RoomPolygon.buildPolygon(recorder.getPoints())
                                            ?.vertices?.map { Pt(it[0].toDouble(), it[1].toDouble()) }
                                            ?: FactsBuilder.boundingPolygon(objects)
                                        val facts = FactsBuilder.build(objects, poly, AppState.northAngle!!)
                                        val rules = RuleEngine.loadRules(context)
                                        val hits = RuleEngine.analyze(facts, rules, gua)
                                        val badHits = hits.filter { it.severity != "吉" }
                                        val plan = solveRemediation(facts, rules, badHits, gua)
                                        val infos = sectorInfo(objects, poly, AppState.northAngle!!)
                                        AppState.analysisResult = AnalysisResult(
                                            gua, true, objects.size, hits, infos, plan, 0, poly
                                        )
                                        withContext(Dispatchers.Main) {
                                            processing = false
                                            status = "批语已成：判得吉凶 ${hits.size} 则"
                                            onAnalyze()
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "起批失败: ${e.message}", e)
                                        withContext(Dispatchers.Main) {
                                            processing = false
                                            status = "起批未成: ${e.message}"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(if (processing) "起批中..." else "勘毕起批", style = MaterialTheme.typography.titleSmall) }
                        TextButton(
                            onClick = onBack,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text("← 返回首页") }
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

    // 离开扫描页时释放资源
    DisposableEffect(Unit) {
        onDispose {
            frameBuffer.clear()
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
