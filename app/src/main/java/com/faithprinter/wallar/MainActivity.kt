package com.faithprinter.wallar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Size
import io.github.sceneview.node.ImageNode
import io.github.sceneview.rememberEngine
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.round

private val Ink = Color(0xFF080B0F)
private val Panel = Color(0xE9141820)
private val Blue = Color(0xFF157AF6)
private val Muted = Color(0xFF9AA7B8)

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
    val latestFrame = remember { AtomicReference<Frame?>(null) }
    val engine = rememberEngine()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        decodeBitmap(context, uri)?.let {
            anchor?.detach()
            anchor = null
            locked = false
            bitmap = it
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
                val found = session.getAllTrackables(Plane::class.java).any {
                    it.type == Plane.Type.VERTICAL && it.trackingState == TrackingState.TRACKING
                }
                if (found != wallVisible) wallVisible = found
                if (found && anchor == null) trackingMessage = "墙面已识别，将准星对准目标位置"
            },
            onTrackingFailureChanged = { reason ->
                trackingMessage = trackingHint(reason)
            },
            onSessionFailed = {
                trackingMessage = "无法启动 AR：请确认设备支持 Google Play AR 服务"
            }
        ) {
            val currentBitmap = bitmap
            val currentAnchor = anchor
            if (currentBitmap != null && currentAnchor != null) {
                val heightMeters = widthMeters * currentBitmap.height / currentBitmap.width.toFloat()
                AnchorNode(anchor = currentAnchor) {
                    ImageNode(
                        bitmap = currentBitmap,
                        size = Size(widthMeters, heightMeters),
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

        if (anchor == null) {
            Reticle(
                active = wallVisible,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Controls(
            bitmap = bitmap,
            widthMeters = widthMeters,
            status = if (bitmap == null) "请先导入需要打印的图片" else trackingMessage,
            canPlace = bitmap != null && wallVisible,
            placed = anchor != null,
            locked = locked,
            onImport = { imagePicker.launch("image/*") },
            onWidthChange = { widthMeters = it.coerceIn(0.2f, 10f) },
            onPlace = {
                val frame = latestFrame.get()
                if (frame != null && viewport != IntSize.Zero) {
                    val hit = frame.hitTest(
                        viewport.width / 2f,
                        viewport.height / 2f
).firstOrNull()
                    if (hit != null) {
                        anchor?.detach()
                        anchor = hit.createAnchor()
                        locked = true
                        trackingMessage = "已按真实尺寸固定在墙面"
                    } else {
                        trackingMessage = "准星没有对准已识别的墙面，请稍微移动设备"
                    }
                }
            },
            onReposition = {
                anchor?.detach()
                anchor = null
                locked = false
                trackingMessage = "重新将准星对准墙面"
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
    Column(
        modifier = modifier
            .background(Panel, RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.faith_printer_logo),
            contentDescription = "FAITH PRINTER",
            modifier = Modifier.size(width = 150.dp, height = 48.dp),
            contentScale = ContentScale.Fit
        )
        Text("WALL AR · TRUE SIZE", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
    }
}

@Composable
private fun Reticle(active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(54.dp)
            .border(2.dp, if (active) Blue else Color.White.copy(alpha = .55f), CircleShape),
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
    status: String,
    canPlace: Boolean,
    placed: Boolean,
    locked: Boolean,
    onImport: () -> Unit,
    onWidthChange: (Float) -> Unit,
    onPlace: () -> Unit,
    onReposition: () -> Unit,
    onLockToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val heightMeters = bitmap?.let { widthMeters * it.height / it.width.toFloat() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Text(status, color = Color.White, fontSize = 13.sp)

        if (bitmap != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("真实宽度", color = Muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallButton("−") { onWidthChange(round1(widthMeters - 0.1f)) }
                    Text(
                        "${format(widthMeters)} × ${format(heightMeters ?: 0f)} m",
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    SmallButton("+") { onWidthChange(round1(widthMeters + 0.1f)) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(if (bitmap == null) "导入图片" else "更换图片")
            }

            if (!placed) {
                Button(
                    onClick = onPlace,
                    enabled = canPlace,
                    modifier = Modifier.weight(1.25f),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)
                ) {
                    Text("放到墙上")
                }
            } else {
                Button(
                    onClick = onLockToggle,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (locked) Blue else Color(0xFF343C49)
                    )
                ) {
                    Text(if (locked) "已锁定" else "未锁定")
                }
                OutlinedButton(
                    onClick = onReposition,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("重放")
                }
            }
        }

        Text(
            "PRINT YOUR VISION. ON ANY WALL.",
            modifier = Modifier.fillMaxWidth(),
            color = Muted,
            fontSize = 9.sp,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
    ) {
        Text(label, fontSize = 19.sp)
    }
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
