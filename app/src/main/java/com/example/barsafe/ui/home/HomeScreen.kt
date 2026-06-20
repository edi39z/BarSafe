package com.example.barsafe.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.barsafe.data.remote.FirebaseLog
import com.example.barsafe.ui.theme.*
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToManageFaces: () -> Unit = {},
    onNavigateToEnrollment: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToOnboarding: () -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val safeState by viewModel.safeState.collectAsState()
    val sensorState by viewModel.sensorState.collectAsState()
    val recentLogs by viewModel.recentLogs.collectAsState()
    val enrolledFaces by viewModel.enrolledFaces.collectAsState()
    val unreadLogsCount by viewModel.unreadLogsCount.collectAsState()
    val pendingCount by viewModel.pendingCommandCount.collectAsState()
    val isEspOnline by viewModel.isEspOnline.collectAsState()

    val isUnlocked = safeState.status == "UNLOCKED"
    val isAlert = safeState.status == "ALERT"

    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val statusColor by animateColorAsState(
        targetValue = when {
            !isEspOnline -> Color(0xFF888888)
            isUnlocked -> GreenSecure
            isAlert -> RedDanger
            else -> BlueNeon
        },
        animationSpec = tween(600),
        label = "statusColor"
    )

    // Pulsing animation for unlocked state
    val infiniteTransition = rememberInfiniteTransition(label = "homePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    var showInventoryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            if (!viewModel.hasShownInventoryDialog) {
                kotlinx.coroutines.delay(1500)
                showInventoryDialog = true
                viewModel.hasShownInventoryDialog = true
            }
        } else {
            viewModel.hasShownInventoryDialog = false
        }
    }

    if (showInventoryDialog) {
        InventoryDialog(
            onDismiss = { showInventoryDialog = false },
            onSubmit = { itemName, type ->
                viewModel.logInventoryActivity(itemName, type)
                showInventoryDialog = false
            }
        )
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            coroutineScope.launch {
                isRefreshing = true
                viewModel.forceRefresh()
                delay(1000)
                isRefreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // ── Header ──────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("BarSafe", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Smart Brankas Dashboard", fontSize = 13.sp, color = TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateToOnboarding, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.HelpOutline, "Panduan Pengguna", tint = Color.White)
                }
                IconButton(onClick = onNavigateToLogs, modifier = Modifier.size(28.dp)) {
                    BadgedBox(
                        badge = {
                            if (unreadLogsCount > 0) {
                                Badge(containerColor = RedDanger, contentColor = Color.White) {
                                    Text(unreadLogsCount.toString())
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Notifications, "Riwayat Aktivitas", tint = Color.White)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Offline Queue Banner ───────────────────────────────────────────
        if (pendingCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = YellowWarning.copy(alpha = 0.1f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, YellowWarning.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = YellowWarning,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "$pendingCount Perintah Tertunda",
                            color = YellowWarning, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Akan dikirim otomatis saat koneksi pulih",
                            color = TextSecondary, fontSize = 11.sp
                        )
                    }
                    Icon(Icons.Default.Schedule, null, tint = YellowWarning, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(8.dp))

        // ── Main Status Card ── Pintu Brankas ─────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                statusColor.copy(alpha = if (isUnlocked) pulseAlpha * 2 else 0.25f),
                                DarkSurface.copy(alpha = 0.8f)
                            )
                        )
                    )
                    .border(1.dp, statusColor.copy(alpha = 0.3f), RoundedCornerShape(32.dp))
                    .padding(28.dp)
            ) {
                // Online badge removed per user request

                Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    // ── Door status badge ──────────────────────────────────
                    Box(
                        modifier = Modifier
                            .background(
                                statusColor.copy(alpha = 0.15f),
                                RoundedCornerShape(50)
                            )
                            .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = when {
                                    !isEspOnline -> "KONEKSI TERPUTUS"
                                    isUnlocked -> "BRANKAS TERBUKA"
                                    isAlert -> "STATUS DARURAT"
                                    safeState.status == "UNKNOWN" -> "MENGHUBUNGKAN..."
                                    else -> "BRANKAS TERKUNCI"
                                },
                                color = statusColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.2.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // ── Lock Icon ──────────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.12f))
                            .border(2.dp, statusColor.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isUnlocked -> Icons.Default.LockOpen
                                isAlert -> Icons.Default.Warning
                                else -> Icons.Default.Lock
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(44.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = when {
                            !isEspOnline -> "ESP32 OFFLINE"
                            isUnlocked -> "PINTU TERBUKA"
                            isAlert -> "PERINGATAN KUNCI!"
                            safeState.status == "UNKNOWN" -> "Menghubungkan..."
                            else -> "AMAN & TERKUNCI"
                        },
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = statusColor,
                        letterSpacing = 2.sp
                    )

                    // ── Last access info ───────────────────────────────────
                    if (safeState.lastAccess > 0L) {
                        Spacer(Modifier.height(8.dp))
                        val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale("id"))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Akses terakhir: ${fmt.format(Date(safeState.lastAccess))}",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            if (safeState.lastPerson.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = safeState.lastPerson,
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // ── Status & ACK Info ──────────────
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Door mechanic status pill
                        Row(
                            modifier = Modifier
                                .background(
                                    statusColor.copy(alpha = 0.12f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = if (isUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = if (isUnlocked) "Solenoid Terbuka" else "Solenoid Terkunci",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }

                        // ACK status pill
                        if (safeState.lastPerson.isNotBlank()) {
                            Row(
                                modifier = Modifier
                                    .background(
                                        (if (safeState.isAcked) GreenSecure else TextSecondary).copy(alpha = 0.1f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (safeState.isAcked) Icons.Default.VerifiedUser else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (safeState.isAcked) GreenSecure else TextSecondary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = if (safeState.isAcked) "ACK ✓" else "Menunggu ACK",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (safeState.isAcked) GreenSecure else TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Sensor Cards ────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Status Sensor", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
            // ESP32 connection status pill
            Row(
                modifier = Modifier
                    .background(
                        (if (isEspOnline) GreenSecure else RedDanger).copy(alpha = 0.12f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isEspOnline) GreenSecure else RedDanger)
                )
                Text(
                    text = if (isEspOnline) "ESP32 Online" else "ESP32 Offline",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEspOnline) GreenSecure else RedDanger
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // Offline Warning Removed (User feedback: unprofessional)

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SensorCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Sensors,
                label = "PIR",
                value = if (!isEspOnline) "---" else if (sensorState.pirDetected) "Terdeteksi!" else "Aman",
                color = if (!isEspOnline) TextSecondary else if (sensorState.pirDetected) RedDanger else GreenSecure
            )
            SensorCard(
                modifier = Modifier.weight(1f),
                icon = if (!isEspOnline) Icons.Default.DoorFront else if (sensorState.doorClosed) Icons.Default.Lock else Icons.Default.LockOpen,
                label = "Pintu",
                value = if (!isEspOnline) "---" else if (sensorState.doorClosed) "Tertutup" else "Terbuka",
                color = if (!isEspOnline) TextSecondary else if (sensorState.doorClosed) GreenSecure else YellowWarning
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Enrolled Faces ────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Wajah Terdaftar", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
            TextButton(onClick = onNavigateToManageFaces) {
                Text("Kelola", color = BlueNeon, fontSize = 13.sp)
            }
        }
        if (enrolledFaces.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Face, null, tint = TextSecondary, modifier = Modifier.size(32.dp))
                    Text(
                        "Belum ada wajah terdaftar. Daftarkan wajah untuk membuka brankas.",
                        color = TextSecondary, fontSize = 13.sp
                    )
                }
            }
        } else {
            enrolledFaces.take(3).forEach { face ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(BlueNeon.copy(0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, null, tint = BlueNeon, modifier = Modifier.size(20.dp))
                        }
                        Text(face.name, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (enrolledFaces.size > 3) {
                Text(
                    "+${enrolledFaces.size - 3} lainnya", color = TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Recent Logs ────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Aktivitas Terbaru", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
            TextButton(onClick = onNavigateToLogs) {
                Text("Lihat Semua", color = BlueNeon, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (recentLogs.isEmpty()) {
            Text("Belum ada aktivitas tercatat", color = TextSecondary, fontSize = 13.sp)
        } else {
            recentLogs.take(5).forEach { log ->
                LogCard(log = log)
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(80.dp)) // FAB padding
        }
    }
}

@Composable
private fun SensorCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier.defaultMinSize(minHeight = 105.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkSurface.copy(alpha = 0.6f))
                .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(label, fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                value, fontSize = 13.sp, color = color, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LogCard(log: FirebaseLog) {
    val (color, icon) = when {
        log.type.contains("SUCCESS") -> GreenSecure to Icons.Default.CheckCircle
        log.type.contains("FAILED") -> RedDanger to Icons.Default.Cancel
        log.type.contains("ANOMALY") || log.type.contains("INTRUSION") -> YellowWarning to Icons.Default.Warning
        else -> BlueNeon to Icons.Default.Info
    }
    val fmt = SimpleDateFormat("dd MMM HH:mm", Locale("id"))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(color.copy(alpha = 0.06f), Color.Transparent),
                        startX = 0f,
                        endX = 200f
                    )
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    log.description.ifBlank { log.type }, color = Color.White,
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                if (log.personName.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Default.Person, null, tint = TextSecondary, modifier = Modifier.size(11.dp))
                        Text(log.personName, color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
            if (log.timestamp > 0L)
                Text(fmt.format(Date(log.timestamp)), color = TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun InventoryDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var itemName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Inventory, null, tint = BlueNeon, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("Aktivitas Barang", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Ada barang yang masuk atau keluar?", fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center)
                
                Spacer(Modifier.height(20.dp))
                
                OutlinedTextField(
                    value = itemName,
                    onValueChange = { itemName = it },
                    label = { Text("Nama Barang") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BlueNeon,
                        unfocusedBorderColor = TextSecondary,
                        focusedLabelColor = BlueNeon,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(Modifier.height(24.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { onSubmit(itemName, "KELUAR") },
                        colors = ButtonDefaults.buttonColors(containerColor = YellowWarning),
                        modifier = Modifier.weight(1f),
                        enabled = itemName.isNotBlank()
                    ) {
                        Text("Keluar", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onSubmit(itemName, "MASUK") },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenSecure),
                        modifier = Modifier.weight(1f),
                        enabled = itemName.isNotBlank()
                    ) {
                        Text("Masuk", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) {
                    Text("Lewati", color = TextSecondary)
                }
            }
        }
    }
}
