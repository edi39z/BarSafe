package com.example.barsafe.navigation

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.barsafe.data.local.AppDatabase
import com.example.barsafe.ui.enrollment.FaceEnrollmentScreen
import com.example.barsafe.ui.faceunlock.FaceUnlockScreen
import com.example.barsafe.ui.home.HomeScreen
import com.example.barsafe.ui.inventory.InventoryScreen
import com.example.barsafe.ui.enrollment.ManageFacesScreen
import com.example.barsafe.ui.logs.LogsScreen
import com.example.barsafe.ui.onboarding.OnboardingScreen
import com.example.barsafe.ui.theme.BlueNeon
import com.example.barsafe.ui.theme.DarkSurface
import com.example.barsafe.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val Context.dataStore by preferencesDataStore(name = "barsafe_prefs")
private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")

sealed class Screen(val route: String, val icon: ImageVector, val label: String) {
    object Home : Screen("home", Icons.Default.DashboardCustomize, "Beranda")
    object Inventory : Screen("inventory", Icons.Default.Inventory2, "Aset")
    object Logs : Screen("logs", Icons.Default.History, "Riwayat")
    object FaceUnlock : Screen("face_unlock", Icons.Default.Shield, "Buka")
    object Onboarding : Screen("onboarding", Icons.Default.Shield, "")
    object Enrollment : Screen("enrollment", Icons.Default.Shield, "")
    object ManageFaces : Screen("manage_faces", Icons.Default.Face, "Wajah")
}

private val bottomNavItems = listOf(Screen.Home, Screen.Inventory)

@Composable
fun BarSafeNavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var startDestination by remember { mutableStateOf<String?>(null) }
    var splashFinished by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        val onboardingDone = prefs[ONBOARDING_DONE] ?: false
        val faceCount = db.faceEmbeddingDao().count()
        startDestination = when {
            faceCount == 0 -> Screen.Onboarding.route
            !onboardingDone -> Screen.Onboarding.route
            else -> Screen.Home.route
        }
    }

    // Show loading splash while determining start destination or playing animation
    if (!splashFinished || startDestination == null) {
        AnimatedSplashScreen(
            onAnimationDone = { splashFinished = true }
        )
        return
    }

    val dest = startDestination!!

    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BarSafeBottomBar(navController = navController, currentRoute = currentRoute)
            }
        },
        floatingActionButton = {
            if (showBottomBar) {
                FaceFab(onClick = { navController.navigate(Screen.FaceUnlock.route) })
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = dest,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Onboarding.route) {
                OnboardingScreen(
                    onFinish = {
                        scope.launch {
                            context.dataStore.edit { it[ONBOARDING_DONE] = true }
                            val count = kotlinx.coroutines.Dispatchers.IO.let {
                                kotlinx.coroutines.withContext(it) { db.faceEmbeddingDao().count() }
                            }
                            
                            val isBack = navController.previousBackStackEntry != null
                            // Run navigate on Main Thread
                            launch(kotlinx.coroutines.Dispatchers.Main) {
                                if (isBack) {
                                    navController.popBackStack()
                                } else {
                                    val nextRoute = if (count == 0) Screen.Enrollment.route else Screen.Home.route
                                    navController.navigate(nextRoute) {
                                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                                    }
                                }
                            }
                        }
                    }
                )
            }
            composable(Screen.Enrollment.route) {
                FaceEnrollmentScreen(
                    onBack = {
                        val canGoHome = navController.previousBackStackEntry != null &&
                                navController.previousBackStackEntry?.destination?.route != Screen.Onboarding.route
                        if (canGoHome) navController.popBackStack()
                        else navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Enrollment.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToManageFaces = { navController.navigate(Screen.ManageFaces.route) },
                    onNavigateToEnrollment = { navController.navigate(Screen.Enrollment.route) },
                    onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                    onNavigateToOnboarding = { navController.navigate(Screen.Onboarding.route) }
                )
            }
            composable(Screen.ManageFaces.route) {
                ManageFacesScreen(
                    onBack = { navController.popBackStack() },
                    onAddNewFace = { navController.navigate(Screen.Enrollment.route) }
                )
            }
            composable(Screen.Inventory.route) { InventoryScreen() }
            composable(
                route = Screen.Logs.route,
                deepLinks = listOf(navDeepLink { uriPattern = "barsafe://logs" })
            ) { LogsScreen(onBack = { navController.popBackStack() }) }
            composable(Screen.FaceUnlock.route) {
                FaceUnlockScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
fun BarSafeBottomBar(navController: NavController, currentRoute: String?) {
    NavigationBar(
        containerColor = DarkSurface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        val homeScreen = bottomNavItems[0]
        NavigationBarItem(
            selected = currentRoute == homeScreen.route,
            onClick = {
                navController.navigate(homeScreen.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(homeScreen.icon, homeScreen.label) },
            label = { Text(homeScreen.label, fontSize = 11.sp) },
            colors = navItemColors()
        )
        
        NavigationBarItem(
            selected = false,
            onClick = {},
            icon = {},
            enabled = false
        )
        
        val invScreen = bottomNavItems[1]
        NavigationBarItem(
            selected = currentRoute == invScreen.route,
            onClick = {
                navController.navigate(invScreen.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(invScreen.icon, invScreen.label) },
            label = { Text(invScreen.label, fontSize = 11.sp) },
            colors = navItemColors()
        )
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = BlueNeon,
    selectedTextColor = BlueNeon,
    unselectedIconColor = Color(0xFF6B6B6B),
    unselectedTextColor = Color(0xFF6B6B6B),
    indicatorColor = Color.Transparent
)

@Composable
fun FaceFab(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = BlueNeon,
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
        modifier = Modifier
            .size(72.dp)
            .offset(y = 48.dp) // Offset to overlap with the bottom navigation bar
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(id = com.example.barsafe.R.drawable.ic_face_logo),
            contentDescription = "Face Unlock",
            modifier = Modifier.size(36.dp),
            tint = Color.Unspecified // Keep original logo colors if needed, or Color.White
        )
    }
}

@Composable
fun AnimatedSplashScreen(onAnimationDone: () -> Unit) {
    var startScan by remember { mutableStateOf(false) }
    var scanCompleted by remember { mutableStateOf(false) }

    // Scan line vertical offset
    val scanOffset by animateFloatAsState(
        targetValue = if (startScan) 1f else 0f,
        animationSpec = tween(durationMillis = 1500, easing = LinearEasing),
        finishedListener = {
            scanCompleted = true
        },
        label = "scanOffset"
    )

    LaunchedEffect(Unit) {
        delay(300)
        startScan = true
        delay(1800) // Wait for scan (1500) + slight delay
        onAnimationDone()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(240.dp),
                contentAlignment = Alignment.Center
            ) {
                // Glow effect behind logo
                Box(
                    modifier = Modifier.fillMaxSize().scale(1.2f).background(
                        Brush.radialGradient(
                            colors = listOf(BlueNeon.copy(alpha = 0.35f), Color.Transparent)
                        )
                    )
                )

                // Logo
                Image(
                    painter = androidx.compose.ui.res.painterResource(id = com.example.barsafe.R.drawable.ic_app_logo_barsafe),
                    contentDescription = "BarSafe Logo",
                    modifier = Modifier.fillMaxSize()
                )

                // Scan line
                if (!scanCompleted) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter)
                            .offset(y = (220 * scanOffset).dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, BlueNeon, Color.Transparent)
                                )
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
            androidx.compose.material3.Text(
                text = "BarSafe",
                fontSize = 32.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = Color.White,
                letterSpacing = 2.sp
            )
        }
    }
}
