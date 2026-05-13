package com.example.safety_screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class ScreenMonitorService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        
        var isMonitoring = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayManager: OverlayManager? = null
    private val analyzer = MockAIAnalyzer()
    
    private val handler = Handler(Looper.getMainLooper())
    private val captureIntervalMs = 3000L
    private var isCapturing = false

    private val captureRunnable = object : Runnable {
        override fun run() {
            captureFrame()
            if (isCapturing) {
                handler.postDelayed(this, captureIntervalMs)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (resultData != null) {
                startMonitoring(resultCode, resultData)
            }
        } else if (intent?.action == ACTION_STOP) {
            stopMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring(resultCode: Int, resultData: Intent) {
        if (isMonitoring) return

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "safescreen_channel")
            .setContentTitle("SafeScreen Active")
            .setContentText("Monitoring screen for unsafe content.")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // built-in fallback
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1, notification)

        overlayManager = OverlayManager(this)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        setupVirtualDisplay()

        isMonitoring = true
        isCapturing = true
        handler.post(captureRunnable)
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        // Lower resolution for better performance
        val width = metrics.widthPixels / 2
        val height = metrics.heightPixels / 2
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, handler
        )
    }

    private fun captureFrame() {
        val image = imageReader?.acquireLatestImage()
        if (image != null) {
            val isUnsafe = analyzer.analyzeImage(image)
            image.close()
            
            if (isUnsafe) {
                Log.d("SafeScreen", "Unsafe content detected!")
                overlayManager?.showOverlay()
                
                // Auto-remove overlay after 4 seconds (for demo purposes)
                handler.postDelayed({
                    overlayManager?.removeOverlay()
                }, 4000)
            } else {
                Log.d("SafeScreen", "Content safe.")
            }
        }
    }

    private fun stopMonitoring() {
        isCapturing = false
        isMonitoring = false
        handler.removeCallbacks(captureRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        overlayManager?.removeOverlay()
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "safescreen_channel",
                "SafeScreen Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
    }
}
