package com.example.barsafe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.barsafe.navigation.BarSafeNavGraph
import com.example.barsafe.ui.theme.BarSafeTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.barsafe.service.BarSafeMonitorService

class MainActivity : ComponentActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Firebase Anonymous Auth — required for RTDB access
        auth = Firebase.auth
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { 
                    startBackgroundMonitor() 
                }
                .addOnFailureListener { 
                    startBackgroundMonitor() 
                }
        } else {
            startBackgroundMonitor()
        }

        setContent {
            BarSafeTheme {
                BarSafeNavGraph()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Makes the intent accessible for Compose Navigation handling
    }

    private fun startBackgroundMonitor() {
        val serviceIntent = Intent(this, BarSafeMonitorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}