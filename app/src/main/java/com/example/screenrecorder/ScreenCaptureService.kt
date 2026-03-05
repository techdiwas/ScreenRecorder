package com.example.screenrecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var videoUri: Uri? = null
    private var isRecording = false

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        
        const val EXTRA_WIDTH = "EXTRA_WIDTH"
        const val EXTRA_HEIGHT = "EXTRA_HEIGHT"
        const val EXTRA_DPI = "EXTRA_DPI"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (isRecording) return
        
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "ScreenRecorderChannel")
            .setContentTitle("Screen Recorder")
            .setContentText("Recording screen...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        val width = intent.getIntExtra(EXTRA_WIDTH, 720)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1280)
        val dpi = intent.getIntExtra(EXTRA_DPI, 320)

        if (resultData == null) {
            stopSelf()
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        // FIX: Android 14 Requires a callback to be registered BEFORE creating the Virtual Display
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                stopRecording() // Handle the user clicking "Stop sharing" from the system UI
            }
        }
        val handler = Handler(Looper.getMainLooper())
        mediaProjection?.registerCallback(callback, handler)

        setupMediaRecorder(width, height)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface, null, null
        )

        mediaRecorder?.start()
        isRecording = true
    }

    private fun setupMediaRecorder(width: Int, height: Int) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ScreenRecord_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRecorder")
            }
        }

        val resolver = contentResolver
        videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        
        val fileDescriptor = videoUri?.let { resolver.openFileDescriptor(it, "rw")?.fileDescriptor }

        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setOutputFile(fileDescriptor)
            }
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(5 * 1024 * 1024)
            prepare()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            // Ignore if called immediately after start
        }
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null
        
        virtualDisplay?.release()
        virtualDisplay = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ScreenRecorderChannel",
                "Screen Recorder Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
