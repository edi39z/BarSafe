package com.example.barsafe.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.barsafe.data.remote.FirebaseLog
import com.example.barsafe.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import coil.compose.AsyncImage

@Composable
fun LogsScreen(onBack: () -> Unit, viewModel: LogsViewModel = viewModel()) {
    val firebaseLogs by viewModel.firebaseLogs.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val filtered = viewModel.filteredLogs(firebaseLogs, filter)
    val lastReadTimestamp by viewModel.lastReadTimestamp.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Riwayat & Anomali", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Rekam jejak keamanan brankas", fontSize = 13.sp, color = TextSecondary)
            }
            IconButton(onClick = { viewModel.markAllAsRead() }) {
                Icon(Icons.Default.DoneAll, contentDescription = "Tandai Sudah Dibaca", tint = BlueNeon)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Filter chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogFilter.values().forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { viewModel.setFilter(f) },
                    label = {
                        Text(when (f) {
                            LogFilter.ALL -> "Semua"
                            LogFilter.ACCESS -> "Akses"
                            LogFilter.ANOMALY -> "Anomali"
                        }, fontSize = 12.sp)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BlueNeon.copy(alpha = 0.2f),
                        selectedLabelColor = BlueNeon,
                        containerColor = DarkSurface,
                        labelColor = TextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = DarkSurface,
                        selectedBorderColor = BlueNeon.copy(alpha = 0.5f),
                        enabled = true,
                        selected = filter == f
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, null, tint = TextSecondary, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Belum ada riwayat aktivitas", color = TextSecondary, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.key }) { log ->
                    val isUnread = log.timestamp > lastReadTimestamp
                    LogEntryCard(log = log, isUnread = isUnread, onDelete = { viewModel.deleteLog(log) })
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun LogEntryCard(log: FirebaseLog, isUnread: Boolean, onDelete: () -> Unit) {
    val (color, icon) = logStyle(log.type)
    val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id"))
    var showDetail by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { showDetail = true },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (isUnread) DarkSurface.copy(alpha = 0.8f) else DarkSurface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(color.copy(alpha = 0.15f),
                    RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
                if (isUnread) {
                    Box(modifier = Modifier.size(10.dp).background(RedDanger, androidx.compose.foundation.shape.CircleShape).align(Alignment.TopEnd))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.description.ifBlank { friendlyType(log.type) },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                if (log.imageUrls.isNotEmpty() || log.imageUrl.isNotBlank()) {
                    val imagesToDisplay = if (log.imageUrls.isNotEmpty()) log.imageUrls else listOf(log.imageUrl)
                    Spacer(Modifier.height(8.dp))
                    if (imagesToDisplay.size == 1) {
                        AsyncImage(
                            model = imagesToDisplay.first(),
                            contentDescription = "Foto Anomali",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            imagesToDisplay.take(3).forEach { img ->
                                AsyncImage(
                                    model = img,
                                    contentDescription = "Foto Anomali",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                        }
                    }
                }
                if (log.personName.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("oleh: ${log.personName}", color = TextSecondary, fontSize = 12.sp)
                }
                if (log.timestamp > 0L) {
                    Spacer(Modifier.height(2.dp))
                    Text(fmt.format(Date(log.timestamp)), color = TextSecondary.copy(0.7f), fontSize = 11.sp)
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Hapus", tint = TextSecondary.copy(0.5f), modifier = Modifier.size(16.dp))
            }
        }
    }

    if (showDetail) {
        LogDetailDialog(
            log = log, 
            color = color, 
            icon = icon, 
            fmt = fmt, 
            onDismiss = { showDetail = false },
            onDelete = { 
                onDelete()
                showDetail = false 
            }
        )
    }
}

private fun logStyle(type: String): Pair<Color, ImageVector> = when {
    type.contains("SUCCESS", true) -> GreenSecure to Icons.Default.CheckCircle
    type.contains("FAILED", true) -> RedDanger to Icons.Default.Cancel
    type.contains("ANOMALY", true) || type.contains("Anomali", true) -> YellowWarning to Icons.Default.Warning
    type.contains("INTRUSION", true) -> RedDanger to Icons.Default.GppBad
    type.contains("UNLOCK", true) || type.contains("Sistem", true) -> BlueNeon to Icons.Default.LockOpen
    else -> TextSecondary to Icons.Default.Info
}

private fun friendlyType(type: String) = when (type) {
    "UNLOCK_SUCCESS" -> "Brankas berhasil dibuka"
    "UNLOCK_FAILED" -> "Percobaan buka brankas gagal"
    "ANOMALY" -> "Anomali terdeteksi"
    "INTRUSION" -> "Percobaan gangguan terdeteksi"
    "ACCESS" -> "Akses brankas"
    else -> type
}

@Composable
private fun LogDetailDialog(log: FirebaseLog, color: Color, icon: ImageVector, fmt: SimpleDateFormat, onDismiss: () -> Unit, onDelete: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val imagesToDisplay = if (log.imageUrls.isNotEmpty()) log.imageUrls else if (log.imageUrl.isNotBlank()) listOf(log.imageUrl) else emptyList()
                
                if (imagesToDisplay.isNotEmpty()) {
                    androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                        items(imagesToDisplay) { img ->
                            AsyncImage(
                                model = img,
                                contentDescription = "Full Anomali",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillParentMaxWidth().height(260.dp).background(Color(0xFF1E1E1E))
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp).background(color.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = color, modifier = Modifier.size(48.dp))
                    }
                }
                
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(friendlyType(log.type), color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(log.description.ifBlank { friendlyType(log.type) }, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
                    
                    Spacer(Modifier.height(16.dp))
                    
                    if (log.personName.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(log.personName, color = TextSecondary, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    if (log.timestamp > 0L) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccessTime, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(fmt.format(Date(log.timestamp)), color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                    
                    Spacer(Modifier.height(24.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Tutup", color = TextSecondary) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onDelete,
                            colors = ButtonDefaults.buttonColors(containerColor = RedDanger.copy(alpha = 0.15f), contentColor = RedDanger),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            Text("Hapus Riwayat", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
