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
    var debugRevealed by remember { mutableStateOf(false) }   // 导出调试按钮（连续点击版本标签 7 次呼出）
    var versionTapCount by remember { mutableIntStateOf(0) }
    var northSet by remember { mutableStateOf(false) }   // 每次入宅须定向正盘（北向不跨会话）
    val recorder = remember { PointCloudRecorder() }
    val wallSegs = remember { java.util.concurrent.CopyOnWriteArrayList<FloatArray>() }  // A: ARCore 竖墙段 [x1,z1,x2,z2]
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
    // 小地图 / 覆盖度
    var coveragePct by remember { mutableIntStateOf(0) }
    var livePolygon by remember { mutableStateOf<List<Pt>?>(null) }
    var liveFrames by remember { mutableStateOf<List<Triple<Double, Double, Double>>>(emptyList()) }
    var currentHeading by remember { mutableStateOf(0.0) }
    var pendingConfirm by remember { mutableStateOf(false) }

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
                    if (processing) {
                        // 起批中：隐藏摄像头，用首页背景 + 轮播古文
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                TheoryCarousel()
                                Text(
                                    "起批中 $procProgress%",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                            }
                        }
                    } else {
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
                            // A: 收集竖墙平面 → 多边形顶点投影到地板 → 墙段证据（解决边界内缩）
                            var vCount = 0
                            for (p in planes) {
                                if (p.type == Plane.Type.VERTICAL &&
                                    p.trackingState == com.google.ar.core.TrackingState.TRACKING &&
                                    wallSegs.size < 2000
                                ) {
                                    vCount++
                                    val pose = p.centerPose
                                    val poly = p.polygon
                                    var prev: FloatArray? = null
                                    // ARCore 多边形缓冲剩余可能非 3 的倍数：剩余 ≥3 才读一个顶点，异常跳过该平面
                                    while (poly.remaining() >= 3) {
                                        try {
                                            val w = FloatArray(3)
                                            val l = floatArrayOf(poly.get(), poly.get(), poly.get())
                                            pose.transformPoint(l, 0, w, 0)
                                            // 位姿异常可能产生 NaN：跳过并重置段起点
                                            if (!w[0].isFinite() || !w[2].isFinite()) { prev = null; continue }
                                            val pt = floatArrayOf(w[0], w[2])
                                            if (prev != null) wallSegs.add(floatArrayOf(prev[0], prev[1], pt[0], pt[1]))
                                            prev = pt
                                        } catch (e: Exception) {
                                            break
                                        }
                                    }
                                }
                            }
                            if (vCount > 0) Log.i(TAG, "A: 竖墙平面 $vCount 累计段 ${wallSegs.size}")
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
                    if (!northSet) {
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
                    // 小地图（右下角，相机朝向上）
                    MiniMapView(
                        keyframes = liveFrames,
                        polygon = livePolygon,
                        cameraPos = latestSnap[0]?.let { it.ox.toDouble() to it.oz.toDouble() }
                            ?: (0.0 to 0.0),
                        headingRad = currentHeading,
                        coveragePct = coveragePct,
                        coverageRadius = 3.0,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 10.dp)
                    )
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
                            "先沿墙绕行一周（覆边缘器物），再室内穿行（覆中心器物）",
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
                                // 覆盖不足：提示一次，再点确认起批
                                if (coveragePct < 75 && !pendingConfirm) {
                                    pendingConfirm = true
                                    status = "覆盖仅 $coveragePct%（中心/边角或有未及），建议补扫；再点一次确认起批"
                                    return@Button
                                }
                                pendingConfirm = false
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
                                        val poly: List<Pt> = RoomPolygon.buildPolygon(
                                            recorder.getPoints(),
                                            frameBuffer.all().map { it.snap.ox to it.snap.oz },
                                            wallSegs.toList()
                                        )?.vertices?.map { Pt(it[0].toDouble(), it[1].toDouble()) }
                                            ?: FactsBuilder.boundingPolygon(objects)
                                        // 书柜双验证 + safe→finance_room 归一化（book 作验证器丢弃）
                                        val canon = PostScanProcessor.canonicalizeScan(objects)
                                        // C1 墙吸附 + A1 进深测量（贴墙类，多边形确定后）
                                        val snapped = WallAdjust.apply(canon, poly)
                                        val facts = FactsBuilder.build(snapped, poly, AppState.northAngle!!)
                                        val rules = RuleEngine.loadRules(context)
                                        val hits = RuleEngine.analyze(facts, rules, gua)
                                        val badHits = hits.filter { it.severity != "吉" }
                                        val plan = solveRemediation(facts, rules, badHits, gua)
                                        val infos = sectorInfo(snapped, poly, AppState.northAngle!!)
                                        AppState.analysisResult = AnalysisResult(
                                            gua, true, snapped.size, hits, infos, plan, 0, poly
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
                        if (debugRevealed) {
                            Button(
                            onClick = {
                                scope.launch(Dispatchers.Default) {
                                    try {
                                        val pts = recorder.getPoints()
                                        val track = frameBuffer.all().map { it.snap.ox to it.snap.oz }
                                        val dir = context.getExternalFilesDir(null) ?: context.filesDir
                                        val ts = System.currentTimeMillis()
                                        val plyFile = java.io.File(dir, "room_points_$ts.ply")
                                        plyFile.bufferedWriter().use { w ->
                                            w.write("ply\n")
                                            w.write("format ascii 1.0\n")
                                            w.write("element vertex ${pts.size}\n")
                                            w.write("property float x\nproperty float y\nproperty float z\n")
                                            w.write("end_header\n")
                                            for (p in pts) w.write("${p[0]} ${p[1]} ${p[2]}\n")
                                        }
                                        val trkFile = java.io.File(dir, "room_track_$ts.txt")
                                        trkFile.bufferedWriter().use { w ->
                                            for ((ox, oz) in track) w.write("$ox $oz\n")
                                        }
                                        Log.i(TAG, "导出: ${pts.size}点 ${track.size}帧 -> $dir")
                                        withContext(Dispatchers.Main) {
                                            status = "已导出点云 ${pts.size} / 轨迹 ${track.size} 帧"
                                            android.widget.Toast.makeText(
                                                context, "已导出点云+轨迹", android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "导出失败: ${e.message}", e)
                                        withContext(Dispatchers.Main) { status = "导出失败: ${e.message}" }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = btnShape
                        ) { Text("导出点云/轨迹(调试)", style = MaterialTheme.typography.bodySmall) }
                        }
                        TextButton(
                            onClick = {
                                versionTapCount++
                                if (versionTapCount >= 7) {
                                    debugRevealed = true
                                    status = "调试功能已开启"
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text(
                            "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        ) }
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

    // 小地图：后台节流重算墙线 + 覆盖度（不阻塞 AR 线程）
    LaunchedEffect(arcoreReady) {
        while (arcoreReady && !processing) {
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                // 任何异常不得崩扫描（LaunchedEffect 未捕获异常会闪退）
                try {
                    val snap = latestSnap[0]
                    currentHeading = if (snap != null) ObjectLocalizer.cameraForwardHeading(snap) else currentHeading
                    val poly = RoomPolygon.buildPolygon(
                        recorder.getPoints(),
                        frameBuffer.all().map { it.snap.ox to it.snap.oz },
                        wallSegs.toList()
                    )?.vertices?.map { Pt(it[0].toDouble(), it[1].toDouble()) }
                    livePolygon = poly
                    if (poly != null) {
                        val frames = frameBuffer.all()
                        liveFrames = frames.map {
                            Triple(it.snap.ox.toDouble(), it.snap.oz.toDouble(), ObjectLocalizer.cameraForwardHeading(it.snap))
                        }
                        coveragePct = ScanCoverage.coveragePct(liveFrames, poly, 3.0)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "小地图重算异常: ${e.message}")
                }
            }
            kotlinx.coroutines.delay(1000)  // 小地图 1s 刷新（原 2.5s）
        }
    }
}
