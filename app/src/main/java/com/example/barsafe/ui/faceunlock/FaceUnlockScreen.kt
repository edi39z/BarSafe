package com.example.barsafe.ui.faceunlock

import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.barsafe.service.BarSafeMonitorService
import com.example.barsafe.ui.theme.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun FaceUnlockScreen(
    onNavigateBack: () -> Unit,
    viewModel: FaceUnlockViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val recognizedName by viewModel.recognizedName.collectAsState()
    val pendingCount by viewModel.pendingCommandCount.collectAsState(initial = 0)
    val isOnline by viewModel.isOnline.collectAsState()
    val testingMetrics by viewModel.testingMetrics.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // ── Screen Flash: Naikkan brightness layar saat scanning ───────────
    // Layar putih terang berfungsi sebagai "fill light" untuk menerangi wajah,
    // sehingga mengurangi efek backlight (cahaya dari belakang).
    val isScanning = state in setOf(
        UnlockState.IDLE, UnlockState.NO_FACE,
        UnlockState.FACE_DETECTED, UnlockState.AWAITING_BLINK
    )
    val activity = context as? android.app.Activity
    DisposableEffect(isScanning) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness ?: -1f
        if (isScanning && window != null) {
            val params = window.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            window.attributes = params
        }
        onDispose {
            // Kembalikan brightness ke semula saat keluar atau selesai scan
            if (window != null) {
                val params = window.attributes
                params.screenBrightness = originalBrightness
                window.attributes = params
            }
        }
    }

    // Collect unlock success event → kirim notifikasi sistem
    LaunchedEffect(Unit) {
        viewModel.unlockSuccessEvent.collect { (personName, isAcked) ->
            BarSafeMonitorService.sendUnlockSuccessNotification(context, personName, isAcked)
        }
    }

    // Collect offline queue event → notifikasi info
    LaunchedEffect(Unit) {
        viewModel.queuedOfflineEvent.collect { personName ->
            BarSafeMonitorService.sendQueuedOfflineNotification(context, personName)
        }
    }

    // Show result overlay
    var showSuccessOverlay by remember { mutableStateOf(false) }
    var showQueuedOverlay by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        showSuccessOverlay = state in setOf(UnlockState.SUCCESS, UnlockState.SUCCESS_ACKED)
        showQueuedOverlay = state == UnlockState.QUEUED_OFFLINE
    }
    
    // Auto-close on success like phone face-unlock
    LaunchedEffect(showSuccessOverlay) {
        if (showSuccessOverlay) {
            kotlinx.coroutines.delay(1200)
            onNavigateBack()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse"
    )

    val ringColor by animateColorAsState(
        targetValue = when (state) {
            UnlockState.SUCCESS, UnlockState.SUCCESS_ACKED -> GreenSecure
            UnlockState.FAILED_NO_MATCH, UnlockState.FAILED_NO_INTERNET -> RedDanger
            UnlockState.QUEUED_OFFLINE -> YellowWarning
            UnlockState.AWAITING_BLINK, UnlockState.BLINK_DETECTED -> YellowWarning
            UnlockState.WAITING_ACK -> GreenSecure.copy(alpha = 0.7f)
            else -> BlueNeon
        },
        animationSpec = tween(400),
        label = "ringColor"
    )

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            viewModel.reset()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top Bar ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                }
                Text(
                    "Face Unlock",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                // Online/offline badge
                Row(
                    modifier = Modifier
                        .background(
                            (if (isOnline) GreenSecure else RedDanger).copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier.size(6.dp).clip(CircleShape)
                            .background(if (isOnline) GreenSecure else RedDanger)
                    )
                    Text(
                        if (isOnline) "Online" else "Offline",
                        fontSize = 11.sp,
                        color = if (isOnline) GreenSecure else RedDanger,
                        fontWeight = FontWeight.Medium
                    )
                }
                // Pending commands badge
                if (pendingCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(YellowWarning.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "$pendingCount antrian",
                            fontSize = 11.sp,
                            color = YellowWarning,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }

            // ── Offline warning banner ────────────────────────────────────
            if (!isOnline) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(YellowWarning.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.WifiOff, null, tint = YellowWarning, modifier = Modifier.size(16.dp))
                    Text(
                        "Mode Offline — perintah akan disimpan dan dikirim saat online",
                        color = YellowWarning,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Camera Circle ─────────────────────────────────────────────
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(280.dp)) {
                if (state in setOf(UnlockState.AWAITING_BLINK, UnlockState.FACE_DETECTED)) {
                    Box(
                        modifier = Modifier.size(280.dp).scale(pulseScale)
                            .border(2.dp, ringColor.copy(0.3f), CircleShape)
                    )
                }
                Box(
                    modifier = Modifier.size(240.dp).clip(CircleShape)
                        .border(3.dp, ringColor, CircleShape)
                ) {
                    if (state != UnlockState.MODEL_NOT_FOUND) {
                        if (cameraPermissionState.status.isGranted) {
                            CameraPreview(
                                context = context,
                                lifecycleOwner = lifecycleOwner,
                                executor = cameraExecutor,
                                onFrame = { viewModel.processImageProxy(it) }
                            )
                        } else {
                            Box(
                                Modifier.fillMaxSize().background(DarkSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.CameraAlt, null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Button(
                                        onClick = { cameraPermissionState.launchPermissionRequest() },
                                        colors = ButtonDefaults.buttonColors(containerColor = BlueNeon)
                                    ) { Text("Izinkan Kamera") }
                                }
                            }
                        }
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(DarkSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ModelTraining, null, tint = TextSecondary, modifier = Modifier.size(60.dp))
                        }
                    }
                    // Overlay for failed states
                    if (state in setOf(UnlockState.FAILED_NO_MATCH, UnlockState.FAILED_NO_INTERNET)) {
                        Box(
                            Modifier.fillMaxSize().background(RedDanger.copy(0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Cancel, null, tint = Color.White, modifier = Modifier.size(72.dp))
                        }
                    }
                    // Offline queue overlay
                    if (state == UnlockState.QUEUED_OFFLINE) {
                        Box(
                            Modifier.fillMaxSize().background(YellowWarning.copy(0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudOff, null, tint = Color.White, modifier = Modifier.size(64.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── Title ─────────────────────────────────────────────────────
            Text(
                text = when (state) {
                    UnlockState.SUCCESS -> "Akses Diberikan!"
                    UnlockState.SUCCESS_ACKED -> "Brankas Terbuka! ✓"
                    UnlockState.QUEUED_OFFLINE -> "Disimpan ke Antrian"
                    UnlockState.FAILED_NO_MATCH -> "Wajah Tidak Dikenali"
                    UnlockState.FAILED_NO_INTERNET -> "Koneksi Bermasalah"
                    UnlockState.RECOGNIZING, UnlockState.BLINK_DETECTED -> "Mengidentifikasi..."
                    UnlockState.SENDING_COMMAND -> "Membuka Brankas..."
                    UnlockState.WAITING_ACK -> "Menunggu Konfirmasi..."
                    UnlockState.AWAITING_BLINK -> "Kedipkan Mata 1x"
                    UnlockState.FACE_DETECTED -> "Wajah Terdeteksi!"
                    UnlockState.MODEL_NOT_FOUND -> "Model Tidak Ditemukan"
                    else -> "Posisikan Wajah Anda"
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = when (state) {
                    UnlockState.SUCCESS, UnlockState.SUCCESS_ACKED -> GreenSecure
                    UnlockState.FAILED_NO_MATCH, UnlockState.FAILED_NO_INTERNET -> RedDanger
                    UnlockState.QUEUED_OFFLINE -> YellowWarning
                    UnlockState.AWAITING_BLINK -> YellowWarning
                    else -> Color.White
                },
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = when (state) {
                    UnlockState.SUCCESS -> "Selamat datang, ${recognizedName ?: ""}! Perintah terkirim."
                    UnlockState.SUCCESS_ACKED -> "Solenoid dikonfirmasi ✓ — Selamat datang, ${recognizedName ?: ""}!"
                    UnlockState.QUEUED_OFFLINE -> "Wajah dikenali. Perintah akan dikirim saat internet tersedia."
                    UnlockState.FAILED_NO_MATCH -> "Wajah tidak terdaftar dalam sistem"
                    UnlockState.FAILED_NO_INTERNET -> "Gagal mengirim perintah ke brankas"
                    UnlockState.AWAITING_BLINK -> "Blink detection aktif — kedip secara natural"
                    UnlockState.RECOGNIZING, UnlockState.BLINK_DETECTED -> "Memproses pengenalan wajah..."
                    UnlockState.SENDING_COMMAND -> "Mengirim perintah ke ESP32..."
                    UnlockState.WAITING_ACK -> "Menunggu konfirmasi solenoid dari ESP32..."
                    UnlockState.MODEL_NOT_FOUND -> "Letakkan mobilefacenet.tflite di folder assets"
                    else -> "Posisikan wajah di dalam lingkaran"
                },
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(Modifier.height(24.dp))

            // ── Action Buttons ────────────────────────────────────────────
            when (state) {
                UnlockState.FAILED_NO_MATCH, UnlockState.FAILED_NO_INTERNET -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.reset() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BlueNeon),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BlueNeon.copy(0.5f))
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Coba Lagi")
                        }
                        Button(
                            onClick = onNavigateBack,
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurface)
                        ) { Text("Tutup") }
                    }
                }
                UnlockState.QUEUED_OFFLINE -> {
                    Button(
                        onClick = onNavigateBack,
                        colors = ButtonDefaults.buttonColors(containerColor = YellowWarning),
                        modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Home, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Kembali ke Beranda", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                UnlockState.RECOGNIZING, UnlockState.SENDING_COMMAND,
                UnlockState.BLINK_DETECTED, UnlockState.WAITING_ACK -> {
                    CircularProgressIndicator(color = BlueNeon, modifier = Modifier.size(32.dp))
                }
                else -> {}
            }

            Spacer(Modifier.height(24.dp))

            // ── Testing / Debug Panel ──────────────────────────────────────
            TestingMetricsPanel(
                metrics = testingMetrics,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(Modifier.weight(1f))

            // ── Status Chip ───────────────────────────────────────────────
            val chipText = when (state) {
                UnlockState.IDLE, UnlockState.NO_FACE -> "Menunggu deteksi wajah..."
                UnlockState.FACE_DETECTED -> "Wajah terdeteksi — bersiaplah"
                UnlockState.AWAITING_BLINK -> "Kedipkan mata untuk verifikasi liveness"
                UnlockState.BLINK_DETECTED -> "Blink terdeteksi!"
                UnlockState.RECOGNIZING -> "Menganalisis wajah dengan AI..."
                UnlockState.SENDING_COMMAND -> "Menghubungi brankas..."
                UnlockState.WAITING_ACK -> "Menunggu ACK dari ESP32..."
                UnlockState.SUCCESS -> "Akses diberikan ✓"
                UnlockState.SUCCESS_ACKED -> "Solenoid dikonfirmasi ✓"
                UnlockState.QUEUED_OFFLINE -> "Perintah dalam antrian offline"
                UnlockState.FAILED_NO_MATCH -> "Akses ditolak"
                UnlockState.FAILED_NO_INTERNET -> "Tidak terhubung internet"
                UnlockState.MODEL_NOT_FOUND -> "Model TFLite tidak ditemukan"
            }
            StatusChip(text = chipText, color = ringColor)
            Spacer(Modifier.height(32.dp))
        }

        // ── Premium Success Overlay ───────────────────────────────────────
        AnimatedVisibility(
            visible = showSuccessOverlay,
            enter = fadeIn(tween(400)) + scaleIn(
                initialScale = 0.85f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            ),
            exit = fadeOut(tween(300))
        ) {
            UnlockSuccessOverlay(
                personName = recognizedName ?: "Pengguna",
                isAcked = state == UnlockState.SUCCESS_ACKED,
                onDismiss = { showSuccessOverlay = false; onNavigateBack() }
            )
        }

        // ── Offline Queued Overlay ────────────────────────────────────────
        AnimatedVisibility(
            visible = showQueuedOverlay,
            enter = fadeIn(tween(400)) + scaleIn(
                initialScale = 0.85f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            ),
            exit = fadeOut(tween(300))
        ) {
            OfflineQueuedOverlay(
                personName = recognizedName ?: "Pengguna",
                onDismiss = { showQueuedOverlay = false; onNavigateBack() }
            )
        }
    }
}

// ── Simple Success Overlay ────────────────────────────────────────────────────
@Composable
private fun UnlockSuccessOverlay(
    personName: String,
    isAcked: Boolean,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.width(300.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            elevation = CardDefaults.cardElevation(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(BlueNeon.copy(alpha = 0.1f), DarkSurface)))
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = BlueNeon,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Pintu Terbuka",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Selamat datang, $personName",
                    fontSize = 15.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                if (isAcked) {
                    Spacer(Modifier.height(12.dp))
                    Text("Solenoid aktif (ACK)", fontSize = 12.sp, color = BlueNeon)
                }
            }
        }
    }
}

// ── Offline Queued Overlay ─────────────────────────────────────────────────────
@Composable
private fun OfflineQueuedOverlay(personName: String, onDismiss: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "queuePulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(1400, easing = EaseInOut), RepeatMode.Reverse),
        label = "qglow"
    )

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(320.dp).background(
                Brush.radialGradient(
                    colors = listOf(YellowWarning.copy(alpha = glowAlpha * 0.3f), Color.Transparent)
                )
            )
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            elevation = CardDefaults.cardElevation(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(YellowWarning.copy(alpha = 0.08f), DarkSurface)
                        )
                    )
                    .padding(horizontal = 28.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(90.dp).clip(CircleShape)
                        .background(YellowWarning.copy(alpha = 0.12f))
                        .border(2.dp, YellowWarning.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CloudOff, null, tint = YellowWarning, modifier = Modifier.size(48.dp))
                }

                Spacer(Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .background(YellowWarning.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .border(1.dp, YellowWarning.copy(alpha = 0.4f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text(
                        "PERINTAH DISIMPAN OFFLINE",
                        color = YellowWarning, fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp
                    )
                }

                Spacer(Modifier.height(20.dp))

                Text("Tidak Ada Koneksi", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text("Wajah dikenali:", fontSize = 13.sp, color = TextSecondary)
                Text(personName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = YellowWarning)

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .background(YellowWarning.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Schedule, null, tint = YellowWarning, modifier = Modifier.size(14.dp))
                    Text(
                        "Perintah dalam antrian — auto-retry saat online",
                        color = YellowWarning, fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "Perintah akan dikirim otomatis\nke ESP32 saat koneksi internet pulih.",
                    fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center, lineHeight = 18.sp
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = YellowWarning)
                ) {
                    Icon(Icons.Default.Home, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Kembali ke Beranda", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    executor: java.util.concurrent.Executor,
    onFrame: (androidx.camera.core.ImageProxy) -> Unit
) {
    val previewView = remember {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { it.setAnalyzer(executor, onFrame) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("FaceUnlockScreen", "Camera binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

// ── Testing / Debug Metrics Panel ─────────────────────────────────────────────
@Composable
private fun TestingMetricsPanel(
    metrics: TestingMetrics,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Panel Pengujian",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

                    val threshold = metrics.threshold
                    val displayScore = metrics.similarityScore ?: metrics.highestRawScore
                    val isSuccess = metrics.isAboveThreshold
                    val barProgress = if (displayScore != null) displayScore.coerceIn(0f, 1f) else 0f
                    val scoreColor = if (isSuccess) GreenSecure else RedDanger

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Similarity Score", fontSize = 12.sp, color = TextSecondary)
                        Text(
                            if (isSuccess) "Diterima" else "Ditolak",
                            fontSize = 11.sp,
                            color = scoreColor,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (displayScore != null) "%.4f".format(displayScore) else "—",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "/ threshold $threshold",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        if (displayScore != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .align(Alignment.CenterStart)
                                    .offset(x = (threshold * 300).dp)
                                    .background(TextSecondary)
                            )
                        }

                        // Keterangan jika gagal
                        if (!isSuccess && metrics.highestRawScore != null) {
                            Text(
                                "Skor tertinggi: ${"%.4f".format(metrics.highestRawScore)} — butuh ≥ $threshold",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun StatusChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(0.12f))
            .border(1.dp, color.copy(0.4f), RoundedCornerShape(50))
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
