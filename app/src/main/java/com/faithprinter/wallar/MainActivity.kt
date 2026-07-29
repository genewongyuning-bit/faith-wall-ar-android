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
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Size
import io.github.sceneview.node.ImageNode
import io.github.sceneview.rememberEngine
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.round
import kotlin.math.sqrt

private val Ink = Color(0xFF080B0F)
private val Panel = Color(0xE9141820)
private val Blue = Color(0xFF157AF6)
private val Muted = Color(0xFF9AA7B8)

private enum class SurfaceMode(val label: String) {
    WALL("墙面"),
    FLOOR("地面")
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
    var surfaceMode by remember { mutableStateOf(SurfaceMode.WALL) }
    var measuredWidth by remember { mutableFloatStateOf(0f) }
    var measuredHeight by remember { mutableFloatStateOf(0f) }
    var overlayPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val latestFrame = remember { AtomicReference<Frame?>(null) }
    val latestVerticalWall = remember { AtomicReference<Plane?>(null) }
    val engine = rememberEngine()

    fun resetWallSelection(message: String) {
        anchor?.detach()
        anchor = null
        locked = false
        cornerPoses = emptyList()
        selectedWall = null
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
                "图片已导入，并已固定在确认区域"
            } else {
                "图片已导入，现在可以确认四个角"
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
            planeRenderer = !locked,
            onSessionUpdated = { session, frame ->
                latestFrame.set(frame)
                val detectedWall = session.getAllTrackables(Plane::class.java).firstOrNull {
                    planeMatchesMode(it, surfaceMode) &&
                        it.trackingState == TrackingState.TRACKING
                }
                latestVerticalWall.set(detectedWall)
                overlayPoints = cornerPoses.mapNotNull {
                    projectPoseToScreen(it, frame, viewport)
                }
                val found = detectedWall != null
                if (found != wallVisible) wallVisible = found
                if (found && anchor == null && cornerPoses.isEmpty()) {
                    trackingMessage = "${surfaceMode.label}已识别，请把准星对准左上角"
                }
            },
            onTrackingFailureChanged = { reason ->
                if (cornerPoses.isEmpty()) trackingMessage = trackingHint(reason)
            },
            onSessionFailed = {
                trackingMessage = "无法启动 AR：请确认设备支持 Google Play AR 服务"
            }
        ) {
            val currentBitmap = bitmap
            val currentAnchor = anchor
            if (currentBitmap != null && currentAnchor != null) {
                val previewWidth = measuredWidth.takeIf { it > 0f } ?: widthMeters
                val previewHeight = measuredHeight.takeIf { it > 0f }
                    ?: (previewWidth * currentBitmap.height / currentBitmap.width.toFloat())
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
            closed = cornerPoses.size == 4,
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
                "可以先确认四角，也可以先导入图片"
            } else {
                trackingMessage
            },
            canConfirmCorner = true,
            confirmedCorners = cornerPoses.size,
            placed = anchor != null,
            locked = locked,
            surfaceMode = surfaceMode,
            onImport = { imagePicker.launch("image/*") },
            onSurfaceModeChange = { mode ->
                if (mode != surfaceMode) {
                    surfaceMode = mode
                    resetWallSelection("已切换到${mode.label}模式，请确认左上角")
                }
            },
            onWidthChange = { widthMeters = it.coerceIn(0.2f, 10f) },
            onConfirmCorner = {
                val frame = latestFrame.get()
                if (frame != null && viewport != IntSize.Zero) {
                    val hits = frame.hitTest(
                        viewport.width / 2f,
                        viewport.height / 2f
                    )
                    val verticalPlaneHit = hits.firstOrNull { result ->
                        val plane = result.trackable as? Plane
                        plane != null &&
                            planeMatchesMode(plane, surfaceMode) &&
                            plane.trackingState == TrackingState.TRACKING
                    }
                    val plane = selectedWall
                        ?: (verticalPlaneHit?.trackable as? Plane)
                        ?: latestVerticalWall.get()
                    val positionHit = verticalPlaneHit ?: hits.firstOrNull()

                    if (plane == null) {
                        trackingMessage = "尚未识别${surfaceMode.label}，请缓慢移动设备后再次点击"
                    } else if (positionHit == null) {
                        trackingMessage = "这个位置暂时没有深度信息，请稍微移动设备后再次点击"
                    } else {
                        if (selectedWall == null) selectedWall = plane
                        val cornerOnWall = projectPoseToPlane(positionHit.hitPose, plane)
                        val updatedCorners = cornerPoses + cornerOnWall
                        cornerPoses = updatedCorners

                        if (updatedCorners.size == 4) {
                            val centerPose = wallCenterPose(updatedCorners)
                            val dimensions = measuredDimensions(updatedCorners)
                            measuredWidth = dimensions.first
                            measuredHeight = dimensions.second
                            anchor?.detach()
                            anchor = plane.createAnchor(centerPose)
                            locked = true
                            trackingMessage = "测量完成：${format(measuredWidth)} × ${format(measuredHeight)} m"
                            if (bitmap == null) imagePicker.launch("image/*")
                        } else {
                            trackingMessage = cornerInstruction(updatedCorners.size)
                        }
                    }
                }
            },
            onReposition = {
                resetWallSelection("请重新确认墙面的左上角")
            },
            onUndoCorner = {
                if (anchor == null && cornerPoses.isNotEmpty()) {
                    cornerPoses = cornerPoses.dropLast(1)
                    trackingMessage = if (cornerPoses.isEmpty()) {
                        "请把准星对准左上角"
                    } else {
                        cornerInstruction(cornerPoses.size)
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
    onImport: () -> Unit,
    onSurfaceModeChange: (SurfaceMode) -> Unit,
    onWidthChange: (Float) -> Unit,
    onConfirmCorner: () -> Unit,
    onReposition: () -> Unit,
    onUndoCorner: () -> Unit,
    onLockToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (measuredWidth > 0f) {
                "${format(measuredWidth)} × ${format(measuredHeight)} m"
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

        SurfaceModeSelector(surfaceMode, onSurfaceModeChange)

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
                Text(if (placed) "重新测量" else "撤销", fontSize = 12.sp)
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
                    if (placed) "锁定" else "+",
                    fontSize = if (placed) 12.sp else 38.sp
                )
            }

            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.size(width = 92.dp, height = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(if (bitmap == null) "选择图片" else "更换图片", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SurfaceModeSelector(
    selectedMode: SurfaceMode,
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
                Text(mode.label, fontSize = 12.sp, maxLines = 1)
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
    return Pose(translation, corners.first().rotationQuaternion)
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

private fun cornerInstruction(confirmedCount: Int): String = when (confirmedCount) {
    1 -> "左上角已确认，请对准右上角"
    2 -> "右上角已确认，请对准右下角"
    3 -> "右下角已确认，请对准左下角"
    else -> "请确认墙面的四个角"
}

private fun cornerButtonLabel(confirmedCount: Int): String = when (confirmedCount) {
    0 -> "确认左上角"
    1 -> "确认右上角"
    2 -> "确认右下角"
    3 -> "确认左下角"
    else -> "四角已确认"
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

private fun trackingHint(reason: TrackingFailureReason?): String = when (reason) {
    TrackingFailureReason.INSUFFICIENT_LIGHT -> "光线不足，请打开灯或移到更亮的位置"
    TrackingFailureReason.EXCESSIVE_MOTION -> "移动过快，请放慢速度"
    TrackingFailureReason.INSUFFICIENT_FEATURES -> "墙面纹理太少，请从斜角缓慢扫描"
    TrackingFailureReason.CAMERA_UNAVAILABLE -> "摄像头不可用，请关闭其他相机应用"
    TrackingFailureReason.BAD_STATE -> "AR 状态异常，请重新打开应用"
    else -> "移动设备，缓慢扫描墙面"
}

private fun round1(value: Float): Float = round(value * 10f) / 10f
private fun format(value: Float): String = "%.2f".format(value)
