package com.faithprinter.wallar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.opengl.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Size
import io.github.sceneview.node.ImageNode
import io.github.sceneview.rememberEngine
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

private val Ink = Color(0xFF080B0F)
private val Panel = Color(0xE9141820)
private val Blue = Color(0xFF157AF6)
private val Muted = Color(0xFF9AA7B8)

private enum class SurfaceMode { WALL, FLOOR }
private enum class AppLanguage { ZH, EN }
private enum class UnitSystem(val metersPerUnit: Float) {
    METRIC(1f),
    IMPERIAL(0.3048f)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = Ink) {
                    FaithWallAR()
                }
            }
        }
    }
}

@Composable
private fun FaithWallAR() {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var widthMeters by remember { mutableFloatStateOf(1.2f) }
    var wallVisible by remember { mutableStateOf(false) }
    var trackingMessage by remember { mutableStateOf("移动设备，缓慢扫描墙面") }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var locked by remember { mutableStateOf(false) }
    var cornerPoses by remember { mutableStateOf<List<Pose>>(emptyList()) }
    var selectedWall by remember { mutableStateOf<Plane?>(null) }
    var selectionUsesDepth by remember { mutableStateOf(false) }
    var depthSupported by remember { mutableStateOf(false) }
    var depthReady by remember { mutableStateOf(false) }
    var surfaceMode by remember { mutableStateOf(SurfaceMode.WALL) }
    var language by remember { mutableStateOf(AppLanguage.ZH) }
    var unitSystem by remember { mutableStateOf(UnitSystem.METRIC) }
    var widthInput by remember { mutableStateOf("1.20") }
    var measuredWidth by remember { mutableFloatStateOf(0f) }
    var measuredHeight by remember { mutableFloatStateOf(0f) }
    var overlayPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val latestFrame = remember { AtomicReference<Frame?>(null) }
    val latestSession = remember { AtomicReference<Session?>(null) }
    val latestVerticalWall = remember { AtomicReference<Plane?>(null) }
    val recentDepthPoses = remember { ArrayDeque<Pose>() }
    val engine = rememberEngine()

    fun resetWallSelection(message: String) {
        anchor?.detach()
        anchor = null
        locked = false
        cornerPoses = emptyList()
        selectedWall = null
        selectionUsesDepth = false
        recentDepthPoses.clear()
        depthReady = false
        measuredWidth = 0f
        measuredHeight = 0f
        overlayPoints = emptyList()
        trackingMessage = message
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        decodeBitmap(context, uri)?.let {
            bitmap?.recycle()
            bitmap = it
            trackingMessage = if (anchor != null) {
                tr(language, "图片已导入，已按真实尺寸固定", "Image imported at true size")
            } else {
                tr(language, "图片已导入，现在可以确认四个角", "Image imported. Confirm four corners")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            anchor?.detach()
            bitmap?.recycle()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .onSizeChanged { viewport = it }
    ) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            depthMode = Config.DepthMode.AUTOMATIC,
            planeRenderer = !locked,
            onSessionCreated = { session ->
                latestSession.set(session)
                depthSupported =
                    session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            },
            onSessionUpdated = { session, frame ->
                latestSession.set(session)
                latestFrame.set(frame)
                val detectedWall = session.getAllTrackables(Plane::class.java).firstOrNull {
                    planeMatchesMode(it, surfaceMode) &&
                        it.trackingState == TrackingState.TRACKING
                }
                latestVerticalWall.set(detectedWall)
                val centerDepthPose = if (
                    depthSupported &&
                    viewport != IntSize.Zero &&
                    anchor == null
                ) {
                    frame.hitTest(viewport.width / 2f, viewport.height / 2f)
                        .firstOrNull {
                            it.trackable is DepthPoint &&
                                poseMatchesMode(it.hitPose, surfaceMode)
                        }
                        ?.hitPose
                } else {
                    null
                }
                if (centerDepthPose != null) {
                    recentDepthPoses.addLast(centerDepthPose)
                    while (recentDepthPoses.size > 8) recentDepthPoses.removeFirst()
                } else {
                    recentDepthPoses.clear()
                }
                val stableNow = depthSamplesStable(recentDepthPoses)
                if (stableNow != depthReady) depthReady = stableNow
                val currentBitmap = bitmap
                val currentAnchor = anchor
                val boundaryPoses = if (currentBitmap != null && currentAnchor != null) {
                    val imageHeight =
                        widthMeters * currentBitmap.height / currentBitmap.width.toFloat()
                    imageBoundaryPoses(currentAnchor.pose, widthMeters, imageHeight)
                } else {
                    cornerPoses
                }
                overlayPoints = boundaryPoses.mapNotNull {
                    projectPoseToScreen(it, frame, viewport)
                }
                val found = detectedWall != null || depthReady
                if (found != wallVisible) wallVisible = found
                if (found && anchor == null && cornerPoses.isEmpty()) {
                    trackingMessage = tr(
                        language,
                        "${surfaceModeLabel(surfaceMode, language)}已识别，请把准星对准左上角",
                        "${surfaceModeLabel(surfaceMode, language)} detected. Aim at top-left"
                    )
                }
            },
            onTrackingFailureChanged = { reason ->
                if (cornerPoses.isEmpty()) trackingMessage = trackingHint(reason, language, surfaceMode)
            },
            onSessionFailed = {
                trackingMessage = tr(
                    language,
                    "无法启动 AR：请确认设备支持 Google Play AR 服务",
                    "AR could not start. Check Google Play Services for AR"
                )
            }
        ) {
            val currentBitmap = bitmap
            val currentAnchor = anchor
            if (currentBitmap != null && currentAnchor != null) {
                val previewWidth = widthMeters
                val previewHeight =
                    previewWidth * currentBitmap.height / currentBitmap.width.toFloat()
                AnchorNode(anchor = currentAnchor) {
                    ImageNode(
                        bitmap = currentBitmap,
                        size = Size(previewWidth, previewHeight),
                        apply = {
                            isShadowCaster = false
                            isShadowReceiver = false
                        }
                    )
                }
            }
        }

        BrandHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 18.dp)
        )

        MeasurementOverlay(
            points = overlayPoints,
            closed = anchor != null || cornerPoses.size == 4,
            modifier = Modifier.fillMaxSize()
        )

        if (anchor == null) {
            Reticle(
                active = wallVisible,
                cornerNumber = cornerPoses.size + 1,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Controls(
            bitmap = bitmap,
            widthMeters = widthMeters,
            measuredWidth = measuredWidth,
            measuredHeight = measuredHeight,
            status = if (bitmap == null && cornerPoses.isEmpty()) {
                tr(
                    language,
                    if (depthSupported) {
                        "深度增强模式 · 可以先确认四角或导入图片"
                    } else {
                        "兼容模式 · 可以先确认四角或导入图片"
                    },
                    if (depthSupported) {
                        "Depth enhanced · Measure corners or choose an image"
                    } else {
                        "Compatibility mode · Measure corners or choose an image"
                    }
                )
            } else {
                trackingMessage
            },
            canConfirmCorner = if (cornerPoses.isEmpty()) {
                depthReady || wallVisible
            } else {
                true
            },
            confirmedCorners = cornerPoses.size,
            placed = anchor != null,
            locked = locked,
            surfaceMode = surfaceMode,
            language = language,
            unitSystem = unitSystem,
            widthInput = widthInput,
            onImport = { imagePicker.launch("image/*") },
            onSurfaceModeChange = { mode ->
                if (mode != surfaceMode) {
                    surfaceMode = mode
                    resetWallSelection(
                        tr(
                            language,
                            "已切换到${surfaceModeLabel(mode, language)}模式，请确认左上角",
                            "${surfaceModeLabel(mode, language)} mode. Confirm top-left"
                        )
                    )
                }
            },
            onLanguageChange = { next ->
                language = next
                trackingMessage = if (cornerPoses.isEmpty()) {
                    tr(
                        next,
                        "移动设备，缓慢扫描${surfaceModeLabel(surfaceMode, next)}",
                        "Move slowly to scan the ${surfaceModeLabel(surfaceMode, next).lowercase()}"
                    )
                } else {
                    cornerInstruction(cornerPoses.size, next, surfaceMode)
                }
            },
            onUnitSystemChange = { next ->
                if (next != unitSystem) {
                    unitSystem = next
                    widthInput = format(widthMeters / next.metersPerUnit)
                }
            },
            onWidthInputChange = { text ->
                val cleaned = sanitizeDecimal(text)
                widthInput = cleaned
                cleaned.toFloatOrNull()?.takeIf { it > 0f }?.let { value ->
                    widthMeters = (value * unitSystem.metersPerUnit).coerceIn(0.05f, 30f)
                }
            },
            onConfirmCorner = {
                val frame = latestFrame.get()
                if (frame != null && viewport != IntSize.Zero) {
                    val hits = frame.hitTest(
                        viewport.width / 2f,
                        viewport.height / 2f
                    )
                    val lockedPlane = selectedWall
                    val firstSurfacePose = cornerPoses.firstOrNull()
                    val exactDepthHit = if (
                        depthSupported &&
                        (
                            (firstSurfacePose == null && depthReady) ||
                                (firstSurfacePose != null && selectionUsesDepth)
                            )
                    ) {
                        hits.firstOrNull { result ->
                            result.trackable is DepthPoint &&
                                poseMatchesMode(result.hitPose, surfaceMode) &&
                                (
                                    firstSurfacePose == null ||
                                        poseBelongsToSurface(result.hitPose, firstSurfacePose)
                                    )
                        }
                    } else {
                        null
                    }
                    val exactPlaneHit = hits.firstOrNull { result ->
                        val plane = result.trackable as? Plane
                        plane != null &&
                            planeMatchesMode(plane, surfaceMode) &&
                            plane.trackingState == TrackingState.TRACKING &&
                            when {
                                firstSurfacePose == null ->
                                    lockedPlane == null || plane === lockedPlane
                                selectionUsesDepth ->
                                    poseBelongsToSurface(result.hitPose, firstSurfacePose)
                                else ->
                                    lockedPlane == null || plane === lockedPlane
                            }
                    }
                    val chosenHit = when {
                        firstSurfacePose == null -> exactDepthHit ?: exactPlaneHit
                        selectionUsesDepth -> exactDepthHit ?: exactPlaneHit
                        else -> exactPlaneHit
                    }

                    if (chosenHit == null && firstSurfacePose == null) {
                        trackingMessage = tr(
                            language,
                            if (depthSupported) {
                                "深度尚未稳定，请缓慢移动设备扫描${surfaceModeLabel(surfaceMode, language)}"
                            } else {
                                "尚未识别${surfaceModeLabel(surfaceMode, language)}，请继续缓慢扫描"
                            },
                            if (depthSupported) {
                                "Depth is not stable. Slowly scan the ${surfaceModeLabel(surfaceMode, language).lowercase()}"
                            } else {
                                "No ${surfaceModeLabel(surfaceMode, language).lowercase()} detected. Keep scanning slowly"
                            }
                        )
                    } else if (chosenHit == null) {
                        trackingMessage = tr(
                            language,
                            "当前点与第一个点不在同一平面，请重新瞄准",
                            "This point is not on the first surface. Aim again"
                        )
                    } else {
                        val hitPlane = chosenHit.trackable as? Plane
                        if (cornerPoses.isEmpty()) {
                            selectionUsesDepth = chosenHit.trackable is DepthPoint
                            selectedWall = hitPlane
                        }
                        val cornerOnWall = if (hitPlane != null) {
                            projectPoseToPlane(chosenHit.hitPose, hitPlane)
                        } else {
                            chosenHit.hitPose
                        }
                        val updatedCorners = cornerPoses + cornerOnWall
                        cornerPoses = updatedCorners

                        if (updatedCorners.size == 4) {
                            val centerPose = wallCenterPose(updatedCorners)
                            val fittedCorners = snapCornersToPlane(updatedCorners, centerPose)
                            cornerPoses = fittedCorners
                            val dimensions = measuredDimensions(fittedCorners)
                            measuredWidth = dimensions.first
                            measuredHeight = dimensions.second
                            anchor?.detach()
                            anchor = latestSession.get()?.createAnchor(centerPose)
                            locked = true
                            trackingMessage = tr(
                                language,
                                "平面已确认，请输入图片真实宽度",
                                "Surface confirmed. Enter the real image width"
                            )
                            if (bitmap == null) imagePicker.launch("image/*")
                        } else {
                            trackingMessage =
                                cornerInstruction(updatedCorners.size, language, surfaceMode)
                        }
                    }
                }
            },
            onReposition = {
                resetWallSelection(
                    tr(
                        language,
                        "请重新确认${surfaceModeLabel(surfaceMode, language)}的左上角",
                        "Confirm the top-left of the ${surfaceModeLabel(surfaceMode, language).lowercase()}"
                    )
                )
            },
            onUndoCorner = {
                if (anchor == null && cornerPoses.isNotEmpty()) {
                    cornerPoses = cornerPoses.dropLast(1)
                    trackingMessage = if (cornerPoses.isEmpty()) {
                        tr(language, "请把准星对准左上角", "Aim at the top-left")
                    } else {
                        cornerInstruction(cornerPoses.size, language, surfaceMode)
                    }
                }
            },
            onLockToggle = { locked = !locked },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun BrandHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Panel, RoundedCornerShape(22.dp))
            .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(22.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.faith_app_icon_v2),
            contentDescription = "FAITH PRINTER",
            modifier = Modifier.size(34.dp),
            contentScale = ContentScale.Fit
        )
        Text(
            "FAITH  MEASURE AR",
            modifier = Modifier.padding(horizontal = 9.dp),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun MeasurementOverlay(
    points: List<Offset>,
    closed: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        points.zipWithNext().forEach { (start, end) ->
            drawLine(Blue, start, end, strokeWidth = 5f)
        }
        if (closed && points.size == 4) {
            drawLine(Blue, points.last(), points.first(), strokeWidth = 5f)
        }
        points.forEach { point ->
            drawCircle(Color.White, radius = 13f, center = point)
            drawCircle(Blue, radius = 8f, center = point)
        }
    }
}

@Composable
private fun Reticle(
    active: Boolean,
    cornerNumber: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(46.dp)
            .border(2.dp, if (active) Blue else Color.White.copy(alpha = .70f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(if (active) Blue else Color.White, CircleShape)
        )
    }
}

@Composable
private fun Controls(
    bitmap: Bitmap?,
    widthMeters: Float,
    measuredWidth: Float,
    measuredHeight: Float,
    status: String,
    canConfirmCorner: Boolean,
    confirmedCorners: Int,
    placed: Boolean,
    locked: Boolean,
    surfaceMode: SurfaceMode,
    language: AppLanguage,
    unitSystem: UnitSystem,
    widthInput: String,
    onImport: () -> Unit,
    onSurfaceModeChange: (SurfaceMode) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onWidthInputChange: (String) -> Unit,
    onConfirmCorner: () -> Unit,
    onReposition: () -> Unit,
    onUndoCorner: () -> Unit,
    onLockToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayUnit = if (unitSystem == UnitSystem.METRIC) "m" else "ft"
    val measuredWidthDisplay = measuredWidth / unitSystem.metersPerUnit
    val measuredHeightDisplay = measuredHeight / unitSystem.metersPerUnit
    val imageHeightMeters = bitmap?.let {
        widthMeters * it.height / it.width.toFloat()
    } ?: 0f
    val imageHeightDisplay = imageHeightMeters / unitSystem.metersPerUnit

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (measuredWidth > 0f) {
                tr(language, "已确认区域 ", "Selected area ") +
                    "${format(measuredWidthDisplay)} × ${format(measuredHeightDisplay)} $displayUnit"
            } else {
                status
            },
            modifier = Modifier
                .background(Panel, RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(18.dp))
                .padding(horizontal = 15.dp, vertical = 9.dp),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (measuredWidth > 0f) FontWeight.Bold else FontWeight.Normal
        )

        SurfaceModeSelector(surfaceMode, language, onSurfaceModeChange)

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactToggle(
                first = "中文",
                second = "EN",
                firstSelected = language == AppLanguage.ZH,
                onFirst = { onLanguageChange(AppLanguage.ZH) },
                onSecond = { onLanguageChange(AppLanguage.EN) }
            )
            CompactToggle(
                first = "m",
                second = "ft",
                firstSelected = unitSystem == UnitSystem.METRIC,
                onFirst = { onUnitSystemChange(UnitSystem.METRIC) },
                onSecond = { onUnitSystemChange(UnitSystem.IMPERIAL) }
            )
        }

        if (bitmap != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(22.dp))
                    .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = widthInput,
                    onValueChange = onWidthInputChange,
                    modifier = Modifier.size(width = 152.dp, height = 58.dp),
                    label = {
                        Text(tr(language, "真实宽度", "Print width"), fontSize = 11.sp)
                    },
                    suffix = { Text(displayUnit) },
                    singleLine = true
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        tr(language, "自动高度", "Auto height"),
                        color = Muted,
                        fontSize = 11.sp
                    )
                    Text(
                        "${format(imageHeightDisplay)} $displayUnit",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        tr(language, "比例锁定 · 不拉伸", "Aspect locked · No stretch"),
                        color = Blue,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(30.dp))
                .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(30.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = if (placed) onReposition else onUndoCorner,
                enabled = placed || confirmedCorners > 0,
                modifier = Modifier.size(width = 92.dp, height = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(
                    if (placed) tr(language, "重新测量", "Re-measure")
                    else tr(language, "撤销", "Undo"),
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = if (placed) onLockToggle else onConfirmCorner,
                enabled = placed || canConfirmCorner,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (placed && !locked) Color(0xFF343C49) else Blue
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text(
                    if (placed) tr(language, "锁定", "Lock") else "+",
                    fontSize = if (placed) 12.sp else 38.sp
                )
            }

            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.size(width = 92.dp, height = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(
                    if (bitmap == null) tr(language, "选择图片", "Image")
                    else tr(language, "更换图片", "Replace"),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SurfaceModeSelector(
    selectedMode: SurfaceMode,
    language: AppLanguage,
    onModeChange: (SurfaceMode) -> Unit
) {
    Row(
        modifier = Modifier
            .background(Panel, RoundedCornerShape(22.dp))
            .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(22.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SurfaceMode.entries.forEach { mode ->
            Button(
                onClick = { onModeChange(mode) },
                modifier = Modifier.size(width = 88.dp, height = 38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == selectedMode) Blue else Color.Transparent
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 7.dp,
                    vertical = 8.dp
                )
            ) {
                Text(surfaceModeLabel(mode, language), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CompactToggle(
    first: String,
    second: String,
    firstSelected: Boolean,
    onFirst: () -> Unit,
    onSecond: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(Panel, RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(18.dp))
            .padding(3.dp)
    ) {
        listOf(first to onFirst, second to onSecond).forEachIndexed { index, item ->
            val selected = if (index == 0) firstSelected else !firstSelected
            Button(
                onClick = item.second,
                modifier = Modifier.size(width = 42.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Blue else Color.Transparent
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text(item.first, fontSize = 10.sp)
            }
        }
    }
}

private fun wallCenterPose(corners: List<Pose>): Pose {
    val translation = floatArrayOf(
        corners.map { it.tx() }.average().toFloat(),
        corners.map { it.ty() }.average().toFloat(),
        corners.map { it.tz() }.average().toFloat()
    )
    val xAxis = normalize(
        floatArrayOf(
            corners[1].tx() - corners[0].tx(),
            corners[1].ty() - corners[0].ty(),
            corners[1].tz() - corners[0].tz()
        )
    )
    val roughZ = normalize(
        floatArrayOf(
            corners[3].tx() - corners[0].tx(),
            corners[3].ty() - corners[0].ty(),
            corners[3].tz() - corners[0].tz()
        )
    )
    var yAxis = normalize(cross(roughZ, xAxis))
    var zAxis = normalize(cross(xAxis, yAxis))
    val planeNormal = corners.first().rotateVector(floatArrayOf(0f, 1f, 0f))
    if (dot(yAxis, planeNormal) < 0f) {
        yAxis = floatArrayOf(-yAxis[0], -yAxis[1], -yAxis[2])
        zAxis = floatArrayOf(-zAxis[0], -zAxis[1], -zAxis[2])
    }
    return Pose(translation, quaternionFromAxes(xAxis, yAxis, zAxis))
}

private fun normalize(vector: FloatArray): FloatArray {
    val length = sqrt(
        vector[0] * vector[0] +
            vector[1] * vector[1] +
            vector[2] * vector[2]
    )
    if (length < 0.0001f) return floatArrayOf(1f, 0f, 0f)
    return floatArrayOf(vector[0] / length, vector[1] / length, vector[2] / length)
}

private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0]
)

private fun dot(a: FloatArray, b: FloatArray): Float =
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

private fun quaternionFromAxes(
    x: FloatArray,
    y: FloatArray,
    z: FloatArray
): FloatArray {
    val m00 = x[0]
    val m01 = y[0]
    val m02 = z[0]
    val m10 = x[1]
    val m11 = y[1]
    val m12 = z[1]
    val m20 = x[2]
    val m21 = y[2]
    val m22 = z[2]
    val trace = m00 + m11 + m22
    val quaternion = FloatArray(4)
    if (trace > 0f) {
        val s = sqrt(trace + 1f) * 2f
        quaternion[3] = 0.25f * s
        quaternion[0] = (m21 - m12) / s
        quaternion[1] = (m02 - m20) / s
        quaternion[2] = (m10 - m01) / s
    } else if (m00 > m11 && m00 > m22) {
        val s = sqrt(1f + m00 - m11 - m22) * 2f
        quaternion[3] = (m21 - m12) / s
        quaternion[0] = 0.25f * s
        quaternion[1] = (m01 + m10) / s
        quaternion[2] = (m02 + m20) / s
    } else if (m11 > m22) {
        val s = sqrt(1f + m11 - m00 - m22) * 2f
        quaternion[3] = (m02 - m20) / s
        quaternion[0] = (m01 + m10) / s
        quaternion[1] = 0.25f * s
        quaternion[2] = (m12 + m21) / s
    } else {
        val s = sqrt(1f + m22 - m00 - m11) * 2f
        quaternion[3] = (m10 - m01) / s
        quaternion[0] = (m02 + m20) / s
        quaternion[1] = (m12 + m21) / s
        quaternion[2] = 0.25f * s
    }
    return quaternion
}

private fun projectPoseToPlane(sourcePose: Pose, plane: Plane): Pose {
    val planePose = plane.centerPose
    val localPoint = planePose.inverse().transformPoint(
        floatArrayOf(sourcePose.tx(), sourcePose.ty(), sourcePose.tz())
    )
    localPoint[1] = 0f
    val pointOnWall = planePose.transformPoint(localPoint)
    return Pose(pointOnWall, planePose.rotationQuaternion)
}

private fun snapCornersToPlane(corners: List<Pose>, fittedPlanePose: Pose): List<Pose> =
    corners.map { corner ->
        val local = fittedPlanePose.inverse().transformPoint(
            floatArrayOf(corner.tx(), corner.ty(), corner.tz())
        )
        local[1] = 0f
        val snapped = fittedPlanePose.transformPoint(local)
        Pose(snapped, fittedPlanePose.rotationQuaternion)
    }

private fun imageBoundaryPoses(
    centerPose: Pose,
    widthMeters: Float,
    heightMeters: Float
): List<Pose> {
    val halfWidth = widthMeters / 2f
    val halfHeight = heightMeters / 2f
    return listOf(
        floatArrayOf(-halfWidth, 0f, -halfHeight),
        floatArrayOf(halfWidth, 0f, -halfHeight),
        floatArrayOf(halfWidth, 0f, halfHeight),
        floatArrayOf(-halfWidth, 0f, halfHeight)
    ).map { localPoint ->
        Pose(
            centerPose.transformPoint(localPoint),
            centerPose.rotationQuaternion
        )
    }
}

private fun poseMatchesMode(pose: Pose, mode: SurfaceMode): Boolean {
    val normal = normalize(pose.rotateVector(floatArrayOf(0f, 1f, 0f)))
    return when (mode) {
        SurfaceMode.WALL -> abs(normal[1]) < 0.45f
        SurfaceMode.FLOOR -> abs(normal[1]) > 0.72f
    }
}

private fun poseBelongsToSurface(candidate: Pose, reference: Pose): Boolean {
    val referenceNormal = normalize(reference.rotateVector(floatArrayOf(0f, 1f, 0f)))
    val candidateNormal = normalize(candidate.rotateVector(floatArrayOf(0f, 1f, 0f)))
    val normalAgreement = abs(dot(referenceNormal, candidateNormal))
    val delta = floatArrayOf(
        candidate.tx() - reference.tx(),
        candidate.ty() - reference.ty(),
        candidate.tz() - reference.tz()
    )
    val distanceFromPlane = abs(dot(delta, referenceNormal))
    return normalAgreement > 0.80f && distanceFromPlane < 0.15f
}

private fun depthSamplesStable(samples: Collection<Pose>): Boolean {
    if (samples.size < 5) return false
    val center = Pose.makeTranslation(
        samples.map { it.tx() }.average().toFloat(),
        samples.map { it.ty() }.average().toFloat(),
        samples.map { it.tz() }.average().toFloat()
    )
    val maxPositionJitter = samples.maxOf { poseDistance(it, center) }
    val referenceNormal =
        normalize(samples.first().rotateVector(floatArrayOf(0f, 1f, 0f)))
    val lowestNormalAgreement = samples.minOf {
        abs(
            dot(
                referenceNormal,
                normalize(it.rotateVector(floatArrayOf(0f, 1f, 0f)))
            )
        )
    }
    return maxPositionJitter < 0.035f && lowestNormalAgreement > 0.90f
}

private fun projectPoseToScreen(
    pose: Pose,
    frame: Frame,
    viewport: IntSize
): Offset? {
    if (viewport == IntSize.Zero) return null
    val view = FloatArray(16)
    val projection = FloatArray(16)
    val viewProjection = FloatArray(16)
    val clip = FloatArray(4)
    frame.camera.getViewMatrix(view, 0)
    frame.camera.getProjectionMatrix(projection, 0, 0.1f, 100f)
    Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    Matrix.multiplyMV(
        clip,
        0,
        viewProjection,
        0,
        floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1f),
        0
    )
    if (clip[3] <= 0f) return null
    val normalizedX = clip[0] / clip[3]
    val normalizedY = clip[1] / clip[3]
    return Offset(
        x = (normalizedX + 1f) * .5f * viewport.width,
        y = (1f - normalizedY) * .5f * viewport.height
    )
}

private fun measuredDimensions(corners: List<Pose>): Pair<Float, Float> {
    val top = poseDistance(corners[0], corners[1])
    val right = poseDistance(corners[1], corners[2])
    val bottom = poseDistance(corners[3], corners[2])
    val left = poseDistance(corners[0], corners[3])
    return Pair((top + bottom) / 2f, (left + right) / 2f)
}

private fun poseDistance(a: Pose, b: Pose): Float {
    val dx = a.tx() - b.tx()
    val dy = a.ty() - b.ty()
    val dz = a.tz() - b.tz()
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun planeMatchesMode(plane: Plane, mode: SurfaceMode): Boolean = when (mode) {
    SurfaceMode.WALL -> plane.type == Plane.Type.VERTICAL
    SurfaceMode.FLOOR -> plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING
}

private fun tr(language: AppLanguage, zh: String, en: String): String =
    if (language == AppLanguage.ZH) zh else en

private fun surfaceModeLabel(mode: SurfaceMode, language: AppLanguage): String = when (mode) {
    SurfaceMode.WALL -> tr(language, "墙面", "Wall")
    SurfaceMode.FLOOR -> tr(language, "地面", "Floor")
}

private fun cornerInstruction(
    confirmedCount: Int,
    language: AppLanguage,
    mode: SurfaceMode
): String = when (confirmedCount) {
    1 -> tr(language, "左上角已确认，请对准右上角", "Top-left confirmed. Aim at top-right")
    2 -> tr(language, "右上角已确认，请对准右下角", "Top-right confirmed. Aim at bottom-right")
    3 -> tr(language, "右下角已确认，请对准左下角", "Bottom-right confirmed. Aim at bottom-left")
    else -> tr(
        language,
        "请确认${surfaceModeLabel(mode, language)}的四个角",
        "Confirm four corners on the ${surfaceModeLabel(mode, language).lowercase()}"
    )
}

private fun decodeBitmap(context: android.content.Context, uri: Uri): Bitmap? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) {
                    decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()

private fun trackingHint(
    reason: TrackingFailureReason?,
    language: AppLanguage,
    mode: SurfaceMode
): String = when (reason) {
    TrackingFailureReason.INSUFFICIENT_LIGHT ->
        tr(language, "光线不足，请打开灯或移到更亮的位置", "Not enough light. Move somewhere brighter")
    TrackingFailureReason.EXCESSIVE_MOTION ->
        tr(language, "移动过快，请放慢速度", "Moving too fast. Slow down")
    TrackingFailureReason.INSUFFICIENT_FEATURES ->
        tr(language, "表面纹理太少，请从斜角缓慢扫描", "Not enough surface detail. Scan slowly at an angle")
    TrackingFailureReason.CAMERA_UNAVAILABLE ->
        tr(language, "摄像头不可用，请关闭其他相机应用", "Camera unavailable. Close other camera apps")
    TrackingFailureReason.BAD_STATE ->
        tr(language, "AR 状态异常，请重新打开应用", "AR error. Reopen the app")
    else -> tr(
        language,
        "移动设备，缓慢扫描${surfaceModeLabel(mode, language)}",
        "Move slowly to scan the ${surfaceModeLabel(mode, language).lowercase()}"
    )
}

private fun sanitizeDecimal(value: String): String {
    val filtered = value.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    return if (firstDot < 0) {
        filtered.take(6)
    } else {
        filtered.substring(0, firstDot + 1) +
            filtered.substring(firstDot + 1).replace(".", "").take(2)
    }
}

private fun round1(value: Float): Float = round(value * 10f) / 10f
private fun format(value: Float): String = "%.2f".format(value)
