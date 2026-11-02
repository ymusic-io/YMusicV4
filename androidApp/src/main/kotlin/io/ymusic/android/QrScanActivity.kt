package io.ymusic.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Rational
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_close
import io.ymusic.ui.resources.device_link_scan_hint
import kmpcommon.log.Logger
import org.jetbrains.compose.resources.stringResource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Full-screen camera preview that returns one QR payload (or cancels). Decode is on-device
 * ZXing and only the centered viewfinder square is read.
 */
class QrScanActivity : ComponentActivity() {
    private val log by lazy { (application as YMusicApplication).rootLogger.fork("qr-scan") }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val found = AtomicBoolean(false)
    private val reader = MultiFormatReader().apply { setHints(qrHints) }
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var previewView: PreviewView
    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) previewView.post { bindCamera() } else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        setContent {
            QrScanOverlay(previewView, onClose = { finish() })
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            previewView.post { bindCamera() }
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindCamera() {
        if (isDestroyed || isFinishing) return
        if (previewView.width == 0 || previewView.height == 0) {
            previewView.post { bindCamera() }
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (isDestroyed || isFinishing) return@addListener
                val provider = try {
                    future.get()
                } catch (e: Exception) {
                    log.log(Logger.Level.WARN, "camera provider unavailable", e)
                    finish()
                    return@addListener
                }
                cameraProvider = provider
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        finish()
                        return@addListener
                    }
                }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        if (found.get()) return@setAnalyzer
                        val text = decodeQr(image) ?: return@setAnalyzer
                        if (found.compareAndSet(false, true)) {
                            runOnUiThread {
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, text))
                                finish()
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
                try {
                    provider.unbindAll()
                    val viewPort = ViewPort.Builder(
                        Rational(previewView.width, previewView.height),
                        previewView.display.rotation,
                    ).setScaleType(ViewPort.FILL_CENTER).build()
                    val group = UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(analysis)
                        .build()
                    provider.bindToLifecycle(this, selector, group)
                } catch (t: Exception) {
                    log.log(Logger.Level.WARN, "bind failed", t)
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun decodeQr(image: ImageProxy): String? {
        val y = copyY(image) ?: return null
        val (rotated, rw, rh) = rotateY(y.data, y.width, y.height, image.imageInfo.rotationDegrees)
        val side = (min(rw, rh) * VIEWFINDER_FRACTION).toInt().coerceAtLeast(16)
        val left = ((rw - side) / 2).coerceAtLeast(0)
        val top = ((rh - side) / 2).coerceAtLeast(0)
        val width = side.coerceAtMost(rw - left)
        val height = side.coerceAtMost(rh - top)
        if (width < 16 || height < 16) return null
        val source = PlanarYUVLuminanceSource(rotated, rw, rh, left, top, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            reader.reset()
            try {
                reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
            } catch (_: ReaderException) {
                null
            }
        } finally {
            reader.reset()
        }
    }

    companion object {
        const val EXTRA_VALUE = "qr"
    }
}

private const val VIEWFINDER_FRACTION = 0.72f

private val qrHints: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.ALSO_INVERTED to true,
    DecodeHintType.CHARACTER_SET to "UTF-8",
)

@Composable
private fun QrScanOverlay(previewView: PreviewView, onClose: () -> Unit) {
    val closeCd = stringResource(Res.string.cd_close)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        ViewfinderMask(Modifier.fillMaxSize())
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(44.dp)
                .semantics { contentDescription = closeCd }
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Color.White, fontSize = 22.sp)
        }
        Text(
            stringResource(Res.string.device_link_scan_hint),
            style = TextStyle(fontSize = 14.sp, color = Color.White),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        )
    }
}

@Composable
private fun ViewfinderMask(modifier: Modifier = Modifier) {
    val overlay = Color(0x99000000)
    val stroke = 4.dp
    val corner = 28.dp
    Canvas(modifier) {
        val side = min(size.width, size.height) * VIEWFINDER_FRACTION
        val hole = Rect(
            offset = Offset((size.width - side) / 2f, (size.height - side) / 2f),
            size = androidx.compose.ui.geometry.Size(side, side),
        )
        clipPath(
            Path().apply { addRect(hole) },
            clipOp = ClipOp.Difference,
        ) {
            drawRect(overlay)
        }
        val len = corner.toPx()
        val w = stroke.toPx()
        val color = Color.White
        fun cornerLines(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = w, cap = StrokeCap.Square)
            drawLine(color, Offset(x2, y2), Offset(x3, y3), strokeWidth = w, cap = StrokeCap.Square)
        }
        cornerLines(hole.left, hole.top + len, hole.left, hole.top, hole.left + len, hole.top)
        cornerLines(hole.right, hole.top + len, hole.right, hole.top, hole.right - len, hole.top)
        cornerLines(hole.left, hole.bottom - len, hole.left, hole.bottom, hole.left + len, hole.bottom)
        cornerLines(hole.right, hole.bottom - len, hole.right, hole.bottom, hole.right - len, hole.bottom)
    }
}

private class YPlane(val data: ByteArray, val width: Int, val height: Int)

private fun copyY(image: ImageProxy): YPlane? {
    val y = image.planes[0]
    val crop = image.cropRect
    val width = crop.width()
    val height = crop.height()
    if (width <= 0 || height <= 0) return null
    val rowStride = y.rowStride
    val pixelStride = y.pixelStride
    val buffer = y.buffer
    val data = ByteArray(width * height)
    if (pixelStride == 1 && rowStride == width && crop.left == 0 && crop.top == 0) {
        buffer.rewind()
        buffer.get(data, 0, data.size.coerceAtMost(buffer.remaining()))
        return YPlane(data, width, height)
    }
    var dst = 0
    val row = if (pixelStride == 1) null else ByteArray(rowStride)
    for (rowIndex in 0 until height) {
        val rowStart = (crop.top + rowIndex) * rowStride + crop.left * pixelStride
        buffer.position(rowStart.coerceAtMost(buffer.limit()))
        if (pixelStride == 1) {
            buffer.get(data, dst, width.coerceAtMost(buffer.remaining()))
            dst += width
        } else {
            val packed = row!!
            buffer.get(packed, 0, min(rowStride, buffer.remaining()))
            var src = 0
            repeat(width) {
                data[dst++] = packed[src]
                src += pixelStride
            }
        }
    }
    return YPlane(data, width, height)
}

private fun rotateY(data: ByteArray, width: Int, height: Int, rotation: Int): Triple<ByteArray, Int, Int> {
    val r = ((rotation % 360) + 360) % 360
    if (r == 0) return Triple(data, width, height)
    val out = ByteArray(data.size)
    when (r) {
        90 -> {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[x * height + (height - 1 - y)] = data[y * width + x]
                }
            }
            return Triple(out, height, width)
        }
        180 -> {
            for (i in data.indices) out[data.size - 1 - i] = data[i]
            return Triple(out, width, height)
        }
        270 -> {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[(width - 1 - x) * height + y] = data[y * width + x]
                }
            }
            return Triple(out, height, width)
        }
        else -> return Triple(data, width, height)
    }
}
