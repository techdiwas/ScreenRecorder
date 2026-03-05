package com.example.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var isRecording by mutableStateOf(false)

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Step 3: User approved screen capture", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                isRecording = true
                Toast.makeText(this, "Step 4: Service Started!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Screen capture was cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startScreenCaptureIntent()
        } else {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScreenRecorderUI(
                        isRecording = isRecording,
                        onStartClick = { 
                            Toast.makeText(this, "Step 1: Button Clicked", Toast.LENGTH_SHORT).show()
                            checkPermissionsAndStart() 
                        },
                        onStopClick = { stopScreenCapture() }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startScreenCaptureIntent()
            } else {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startScreenCaptureIntent()
        }
    }

    private fun startScreenCaptureIntent() {
        try {
            Toast.makeText(this, "Step 2: Requesting Screen Capture...", Toast.LENGTH_SHORT).show()
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = projectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error launching capture prompt: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopScreenCapture() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(intent)
        isRecording = false
        Toast.makeText(this, "Recording Stopped", Toast.LENGTH_SHORT).show()
    }
}

// ... [Keep the exact same ScreenRecorderUI Composable code here] ...
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenRecorderUI(
    isRecording: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screen Recorder", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = if (isRecording) 1.2f else 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "scale"
                    )

                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(if (isRecording) Color.Red.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (isRecording) 32.dp else 40.dp)
                                .clip(if (isRecording) RoundedCornerShape(8.dp) else CircleShape)
                                .background(if (isRecording) Color.White else MaterialTheme.colorScheme.primary)
                        )
                    }

                    Text(
                        text = if (isRecording) "Recording in progress..." else "Ready to capture",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    
                    Text(
                        text = if (isRecording) "Your screen is currently being captured and saved to your gallery." 
                               else "Press the button below to start capturing your screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = if (isRecording) onStopClick else onStartClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = if (isRecording) "STOP RECORDING" else "START RECORDING",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}
