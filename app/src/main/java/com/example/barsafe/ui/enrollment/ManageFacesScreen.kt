package com.example.barsafe.ui.enrollment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.barsafe.data.model.FaceEmbedding
import com.example.barsafe.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ManageFacesScreen(
    onBack: () -> Unit,
    onAddNewFace: () -> Unit,
    viewModel: ManageFacesViewModel = viewModel()
) {
    val faces by viewModel.faces.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddNewFace,
                containerColor = BlueNeon,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.PersonAdd, "Tambah Wajah")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Kelola Wajah",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "${faces.size} wajah terdaftar dalam sistem",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            }

            if (faces.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FaceRetouchingOff,
                            null,
                            tint = TextSecondary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Belum ada wajah terdaftar",
                            color = TextSecondary,
                            fontSize = 15.sp
                        )
                        Text(
                            "Ketuk + untuk mendaftarkan wajah pertama",
                            color = TextSecondary.copy(0.6f),
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(faces, key = { it.personId }) { face ->
                        FaceCard(
                            face = face,
                            onDelete = { viewModel.deleteFace(it) }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FaceCard(
    face: FaceEmbedding,
    onDelete: (FaceEmbedding) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id"))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(BlueNeon.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    null,
                    tint = BlueNeon,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    face.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Didaftarkan: ${dateFormat.format(Date(face.enrolledAt))}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(4.dp))
                StatusChip(
                    text = "Vektor ${face.embedding.size}d",
                    color = GreenSecure
                )
            }

            // Delete button
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    Icons.Default.DeleteOutline,
                    "Hapus",
                    tint = RedDanger,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = DarkSurface,
            icon = {
                Icon(
                    Icons.Default.Warning,
                    null,
                    tint = RedDanger,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text("Hapus Wajah?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "\"${face.name}\" akan dihapus dari sistem. " +
                            "Orang ini tidak akan bisa membuka brankas lagi.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(face)
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedDanger)
                ) {
                    Text("Hapus")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Batal", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, color = color)
    }
}
