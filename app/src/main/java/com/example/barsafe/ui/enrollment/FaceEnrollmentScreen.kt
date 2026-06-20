package com.example.barsafe.ui.enrollment

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.barsafe.ui.theme.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import android.Manifest
import android.os.Build

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun FaceEnrollmentScreen(
    onBack: () -> Unit,
    viewModel: FaceEnrollmentViewModel = viewModel()
) {
    val step by viewModel.step.collectAsState()
    val capturedBitmap by viewModel.capturedBitmap.collectAsState()
    val capturedBitmaps by viewModel.capturedBitmaps.collectAsState()
    val captureProgress by viewModel.captureProgress.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val permissionsList = remember {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list
    }
    val multiplePermissionsState = rememberMultiplePermissionsState(permissionsList)

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
            }
            Text("Daftarkan Wajah", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }

        when (step) {
            EnrollmentStep.INTRO -> IntroStep(
                isModelLoaded = viewModel.isModelLoaded,
                permissionsState = multiplePermissionsState,
                onStart = { viewModel.startCapture() }
            )
            EnrollmentStep.CAPTURE -> CaptureStep(
                captureProgress = captureProgress,
                totalCaptures = FaceEnrollmentViewModel.REQUIRED_CAPTURES,
                onFaceCaptured = { viewModel.onFaceCaptured(it) }
            )
            EnrollmentStep.CONFIRM -> ConfirmStep(
                bitmaps = capturedBitmaps,
                onRetake = { viewModel.retake() },
                onSave = { name -> viewModel.saveEnrollment(name) }
            )
            EnrollmentStep.SAVING -> SavingStep()
            EnrollmentStep.DONE -> DoneStep(onBack = { viewModel.reset(); onBack() })
            EnrollmentStep.ERROR -> ErrorStep(
                message = errorMessage ?: "Terjadi kesalahan",
                onRetry = { viewModel.startCapture() }
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun IntroStep(
    isModelLoaded: Boolean,
    permissionsState: com.google.accompanist.permissions.MultiplePermissionsState,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Face, null, tint = BlueNeon, modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(24.dp))
        Text("Daftarkan Wajah Baru", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Kami memerlukan 3 foto wajah Anda dari sudut berbeda untuk akurasi pengenalan yang lebih baik.",
            color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)

        if (!isModelLoaded) {
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = RedDanger.copy(0.15f)),
                shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, null, tint = RedDanger, modifier = Modifier.size(20.dp))
                    Text("Model mobilefacenet.tflite tidak ditemukan. Taruh file di folder assets.", color = RedDanger, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(40.dp))
        listOf(
            "1. Ambil 3 foto wajah dari posisi berbeda",
            "2. Pastikan pencahayaan ruangan cukup terang",
            "3. Lepaskan kacamata gelap atau masker wajah",
            "4. Sedikit ubah posisi kepala tiap foto"
        ).forEach { tip ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tip, color = TextSecondary, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(40.dp))
        Button(
            onClick = {
                if (permissionsState.allPermissionsGranted) {
                    onStart()
                } else {
                    permissionsState.launchMultiplePermissionRequest()
                }
            },
            enabled = isModelLoaded,
            colors = ButtonDefaults.buttonColors(containerColor = BlueNeon),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(if (permissionsState.allPermissionsGranted) Icons.Default.CameraAlt else Icons.Default.Security, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (permissionsState.allPermissionsGranted) "Mulai Pendaftaran" else "Berikan Izin Akses", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CaptureStep(
    captureProgress: Int,
    totalCaptures: Int,
    onFaceCaptured: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCapture by remember { mutableStateOf(false) }
    var faceDetectedMsg by remember { mutableStateOf("Posisikan wajah Anda") }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    val imageCaptureRef = remember { mutableStateOf<ImageCapture?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val faceDetector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
    }

    // Reset hasCapture when entering this step with new capture round
    LaunchedEffect(captureProgress) {
        hasCapture = false
    }

    LaunchedEffect(cameraPermissionState.status.isGranted) {
        if (!cameraPermissionState.status.isGranted) return@LaunchedEffect
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val imageCapture = ImageCapture.Builder().build()
                imageCaptureRef.value = imageCapture

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            val img = imageProxy.image
                            if (img != null && !hasCapture) {
                                val inputImage = InputImage.fromMediaImage(img, imageProxy.imageInfo.rotationDegrees)
                                faceDetector.process(inputImage)
                                    .addOnSuccessListener { faces ->
                                        faceDetectedMsg = if (faces.isEmpty()) "Posisikan wajah Anda" else "✓ Wajah terdeteksi — tekan Ambil Foto"
                                    }.addOnCompleteListener { imageProxy.close() }
                            } else imageProxy.close()
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview, imageCapture, imageAnalysis
                )
            } catch (e: Exception) { 
                Log.e("Enrollment", "Camera error: ${e.message}") 
            }
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown(); faceDetector.close() }
    }

    // Instruksi posisi berdasarkan foto ke berapa
    val positionHint = when (captureProgress) {
        0 -> "Hadap ke depan secara lurus"
        1 -> "Sedikit miringkan kepala ke kiri"
        2 -> "Sedikit miringkan kepala ke kanan"
        else -> "Posisikan wajah Anda"
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        // ── Camera Preview ──────────────────────────────
        Box(contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(300.dp).clip(CircleShape).border(4.dp, BlueNeon, CircleShape)) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }
            
            // Prominent Instruction Badge floating over the bottom of the preview
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 24.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BlueNeon.copy(alpha = 0.95f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "FOTO ${captureProgress + 1} DARI $totalCaptures",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        positionHint.uppercase(),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(Modifier.height(40.dp))
        Text(faceDetectedMsg, color = if (faceDetectedMsg.startsWith("✓")) GreenSecure else TextSecondary,
            fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))

        if (cameraPermissionState.status.isGranted) {
            Button(
                onClick = {
                    hasCapture = true
                    imageCaptureRef.value?.let { capture ->
                        val mainExec = ContextCompat.getMainExecutor(context)
                        capture.takePicture(mainExec, object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                                val bitmap = image.toBitmap()
                                image.close()
                                onFaceCaptured(bitmap)
                            }
                            override fun onError(exc: ImageCaptureException) {
                                Log.e("Enrollment", "Capture failed: ${exc.message}")
                                hasCapture = false
                            }
                        })
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = BlueNeon),
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.CameraAlt, "Ambil Foto", modifier = Modifier.size(32.dp))
            }
        } else {
            Button(
                onClick = { cameraPermissionState.launchPermissionRequest() },
                colors = ButtonDefaults.buttonColors(containerColor = BlueNeon)
            ) {
                Text("Izinkan Kamera")
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ConfirmStep(
    bitmaps: List<Bitmap>,
    onRetake: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        // Preview captured faces — show all 3
        Text("3 Foto Wajah Berhasil Diambil", fontSize = 18.sp, fontWeight = FontWeight.Bold,
            color = GreenSecure, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            bitmaps.forEachIndexed { index, bitmap ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .clip(CircleShape)
                            .border(2.dp, GreenSecure, CircleShape)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Foto ${index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Foto ${index + 1}", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Masukkan nama untuk wajah ini", color = TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nama *", color = TextSecondary) },
            placeholder = { Text("cth: Budi, Mama, Papa", color = TextSecondary.copy(0.5f)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BlueNeon, unfocusedBorderColor = Color(0xFF3A3A3A),
                focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = BlueNeon
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))

        // Info badge
        Card(
            colors = CardDefaults.cardColors(containerColor = BlueNeon.copy(alpha = 0.08f)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Info, null, tint = BlueNeon, modifier = Modifier.size(14.dp))
                Text(
                    "3 foto dirata-ratakan untuk embedding yang lebih akurat",
                    color = BlueNeon.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A3A3A))
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ambil Ulang")
            }
            Button(
                onClick = { if (name.isNotBlank()) onSave(name) },
                modifier = Modifier.weight(1f),
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = BlueNeon),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Simpan")
            }
        }
    }
}

@Composable
private fun SavingStep() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = BlueNeon, modifier = Modifier.size(56.dp), strokeWidth = 4.dp)
        Spacer(Modifier.height(24.dp))
        Text("Menyimpan data wajah...", color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text("Menghitung centroid dari 3 embedding", color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun DoneStep(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.CheckCircle, null, tint = GreenSecure, modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(24.dp))
        Text("Wajah Berhasil Didaftarkan!", fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = GreenSecure, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Data biometrik dari 3 sudut tersimpan dan siap digunakan untuk membuka brankas.",
            color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))
        Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = BlueNeon),
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Selesai", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ErrorStep(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ErrorOutline, null, tint = RedDanger, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(24.dp))
        Text("Gagal Mendeteksi Wajah", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(message, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = BlueNeon),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Coba Lagi")
        }
    }
}
