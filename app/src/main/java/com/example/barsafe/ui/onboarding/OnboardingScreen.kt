package com.example.barsafe.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.barsafe.ui.theme.*

private data class OnboardingPage(
    val icon: ImageVector,
    val iconColor: Color,
    val title: String,
    val description: String,
    val gradient: List<Color>
)

private val pages = listOf(
    OnboardingPage(
        icon = Icons.Default.Security,
        iconColor = BlueNeon,
        title = "BarSafe Smart Brankas",
        description = "Sistem keamanan modern dengan perlindungan tingkat tinggi. Pantau dan amankan aset berharga Anda dalam satu aplikasi cermat dan elegan.",
        gradient = listOf(BlueNeon.copy(0.15f), Color.Transparent)
    ),
    OnboardingPage(
        icon = Icons.Default.Face,
        iconColor = GreenSecure,
        title = "Kunci Wajah Biometrik",
        description = "Kamera menangkap wajah sebagai kunci brankas. AI biometrik berteknologi luring (offline) memastikan data diproses secara cepat dan aman di dalam perangkat Anda.",
        gradient = listOf(GreenSecure.copy(0.15f), Color.Transparent)
    ),
    OnboardingPage(
        icon = Icons.Default.NotificationsActive,
        iconColor = YellowWarning,
        title = "Notifikasi Cerdas",
        description = "Sensor mendeteksi anomali seperti getaran mencurigakan. Anda akan menerima notifikasi real-time yang memberi tahu setiap potensi ancaman keamanan.",
        gradient = listOf(YellowWarning.copy(0.15f), Color.Transparent)
    ),
    OnboardingPage(
        icon = Icons.Default.CloudSync,
        iconColor = BlueNeon,
        title = "Keamanan Terintegrasi",
        description = "Catat setiap akses dengan detail penuh. Log aktivitas dan foto sinkron secara aman dengan Cloudinary dan Firebase untuk pemantauan optimal.",
        gradient = listOf(BlueNeon.copy(0.15f), Color.Transparent)
    )
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var currentPage by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        // Gradient background
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.radialGradient(colors = pages[currentPage].gradient)
            )
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            // Animated page content
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(500)),
                exit = fadeOut(tween(300))
            ) {
                val page = pages[currentPage]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(120.dp)
                            .clip(CircleShape)
                            .background(page.iconColor.copy(0.15f))
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = page.icon,
                            contentDescription = null,
                            tint = page.iconColor,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = page.title,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = page.description,
                        fontSize = 15.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Page indicators
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(pages.size) { idx ->
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(if (idx == currentPage) 28.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (idx == currentPage) BlueNeon else Color(0xFF3A3A3A))
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (currentPage > 0) {
                    OutlinedButton(
                        onClick = { currentPage-- },
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A3A3A)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Kembali")
                    }
                } else {
                    TextButton(
                        onClick = onFinish,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Lewati", color = TextSecondary)
                    }
                }

                Button(
                    onClick = {
                        if (currentPage < pages.size - 1) currentPage++
                        else onFinish()
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BlueNeon),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        if (currentPage < pages.size - 1) "Lanjut" else "Mulai",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
