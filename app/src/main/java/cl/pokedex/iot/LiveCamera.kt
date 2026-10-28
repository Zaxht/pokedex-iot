package cl.pokedex.iot

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Un visor para ambos modos. Nunca acumula fotogramas ni guarda imágenes en disco. */
@Composable
fun LiveCamera(qr: Boolean, ready: Boolean = true, onCode: (String) -> Unit = {},
               onFrame: (ByteArray) -> Unit = {}, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var error by remember { mutableStateOf("") }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
        error = if (it) "" else "Permite el acceso a la cámara para usar el visor. Si lo bloqueaste, habilítalo en Ajustes de Android."
    }
    val codeCallback by rememberUpdatedState(onCode)
    val frameCallback by rememberUpdatedState(onFrame)
    val enabled = remember { AtomicBoolean(ready) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!permission && !permissionRequested) { permissionRequested = true; request.launch(Manifest.permission.CAMERA) }
    }
    SideEffect { enabled.set(ready) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!permission) Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text("Permitir cámara") }
        else {
            val view = remember { PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            } }
            AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(300.dp))
            DisposableEffect(owner, view, qr) {
                val disposed = AtomicBoolean(false)
                val executor = Executors.newSingleThreadExecutor()
                val main = ContextCompat.getMainExecutor(context)
                val future = ProcessCameraProvider.getInstance(context)
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                var provider: ProcessCameraProvider? = null
                var lastFrame = 0L
                analysis.setAnalyzer(executor) { frame ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (!disposed.get() && enabled.get() && now - lastFrame >= if (qr) 350L else 2000L) {
                            lastFrame = now
                            val raw = frame.toBitmap()
                            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                                Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }, true)
                            try {
                                if (qr) {
                                    val pixels = IntArray(rotated.width * rotated.height)
                                    rotated.getPixels(pixels, 0, rotated.width, 0, 0, rotated.width, rotated.height)
                                    val code = try { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
                                        RGBLuminanceSource(rotated.width, rotated.height, pixels)))).text }
                                    catch (_: com.google.zxing.ReaderException) { null }
                                    if (code != null) main.execute {
                                        if (!disposed.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) codeCallback(code)
                                    }
                                } else {
                                    val scale = minOf(1f, 768f / maxOf(rotated.width, rotated.height))
                                    val resized = Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true)
                                    val jpeg = try { ByteArrayOutputStream().use { resized.compress(Bitmap.CompressFormat.JPEG, 78, it); it.toByteArray() } }
                                    finally { if (resized !== rotated) resized.recycle() }
                                    main.execute {
                                        if (!disposed.get() && enabled.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) frameCallback(jpeg)
                                    }
                                }
                            } finally { if (rotated !== raw) rotated.recycle(); raw.recycle() }
                        }
                    } catch (_: Exception) {
                        main.execute { if (!disposed.get()) error = "No se pudo procesar la cámara. Cierra el visor e intenta otra vez." }
                    } finally { frame.close() }
                }
                future.addListener({
                    if (!disposed.get()) try {
                        provider = future.get()
                        provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    } catch (_: Exception) { error = "No se pudo abrir la cámara trasera. Comprueba el permiso y que otra app no la esté usando." }
                }, main)
                onDispose {
                    disposed.set(true); analysis.clearAnalyzer()
                    provider?.unbind(preview, analysis)
                    executor.shutdown()
                }
            }
        }
        if (error.isNotEmpty()) Text(error)
        OutlinedButton(onClick = onClose) { Text(if (qr) "Cerrar lector" else "Detener reconocimiento") }
    }
}
