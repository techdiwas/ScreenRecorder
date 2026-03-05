package com.example.screenrecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var videoUri: Uri? = null
    
    // FIX: Keep a reference to the file descriptor so the system doesn't close it prematurely (0B bug)
    private var parcelFileDescriptor: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        
        const val EXTRA_WIDTH = "EXTRA_WIDTH"
        const val EXTRA_HEIGHT = "EXTRA_HEIGHT"
        const val EXTRA_DPI = "EXTRA_DPI"
        
        private const val TAG = "ScreenCaptureService"
        const val ACTION_RECORDING_STOPPED = "com.example.screenrecorder.RECORDING_STOPPED"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        createNotificationChannel()

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "ScreenRecorderChannel")
            .setContentTitle("Screen Recorder")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop Recording", stopPendingIntent)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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

        val rawWidth = intent.getIntExtra(EXTRA_WIDTH, 720)
        val rawHeight = intent.getIntExtra(EXTRA_HEIGHT, 1280)
        val width = if (rawWidth % 2 != 0) rawWidth - 1 else rawWidth
        val height = if (rawHeight % 2 != 0) rawHeight - 1 else rawHeight
        val dpi = intent.getIntExtra(EXTRA_DPI, 320)

        if (resultData == null) {
            stopSelf()
            return
        }

        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            setupMediaRecorder(width, height)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )

            mediaRecorder?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen recording: ${e.message}", e)
            stopRecording()
        }
    }

    private fun setupMediaRecorder(width: Int, height: Int) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ScreenRecord_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRecorder")
                // Tell system the file is currently being written to
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        
        // FIX: Assign to class-level variable so it stays open during recording
        parcelFileDescriptor = videoUri?.let { resolver.openFileDescriptor(it, "rw") }
        val fileDescriptor = parcelFileDescriptor?.fileDescriptor

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
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        } finally {
            mediaRecorder?.reset()
            mediaRecorder?.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
            
            // FIX: Close the file descriptor properly to flush the video data to disk
            try {
                parcelFileDescriptor?.close()
                parcelFileDescriptor = null
            } catch (e: Exception) {
                Log.e(TAG, "Error closing file descriptor", e)
            }

            // Tell system the file is done writing so it shows up in Gallery instantly
            videoUri?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(it, contentValues, null, null)
                }
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            
            // Tell MainActivity to update its UI
            sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
        }
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
