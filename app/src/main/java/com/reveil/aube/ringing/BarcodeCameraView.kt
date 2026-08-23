package com.reveil.aube.ringing

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.reveil.aube.R
import java.util.concurrent.Executors

private const val TAG = "AubeBarcodeCameraView"

/** Live camera preview that reports every decoded barcode's raw payload as it finds one. */
@Composable
fun BarcodeCameraView(modifier: Modifier = Modifier, onBarcodeDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Owned by this composable's lifetime, not the AndroidView factory call, so it can be shut
    // down exactly once when this screen leaves composition instead of leaking a thread on
    // every recomposition that re-runs the factory (e.g. a "wrong code" retry that briefly
    // hides and re-shows this view) — the factory itself has no matching teardown callback.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    if (hasCameraPermission) {
        Box(modifier = modifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = CameraPreview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analysisExecutor, ZXingCodeAnalyzer(onBarcodeDetected))
                        try {
                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                            )
                            // This scan happens exactly when the room is likely dark — dawn
                            // simulation means it's still early. Without a light, a scan can
                            // fail on a perfectly correct code just because the camera can't
                            // see it, which reads as "the code isn't recognized" when it's
                            // really just too dark to scan at all.
                            if (camera.cameraInfo.hasFlashUnit()) {
                                camera.cameraControl.enableTorch(true)
                            }
                        } catch (e: Exception) {
                            // Camera unavailable on this device/state — caller should offer a fallback.
                            // Logged so a device where the scan screen consistently can't bind the
                            // camera is diagnosable, since the user-visible symptom (a blank/frozen
                            // preview) gives no clue why on its own.
                            Log.w(TAG, "camera bind/torch failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
            ScanOverlay()
        }
    } else {
        Column {
            Text(stringResource(R.string.camera_permission_required), textAlign = TextAlign.Center, color = Color.White)
            TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.camera_permission_allow))
            }
        }
    }
}
