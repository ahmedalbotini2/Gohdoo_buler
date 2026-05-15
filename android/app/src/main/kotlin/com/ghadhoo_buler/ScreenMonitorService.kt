package com.ghadhoo_buler

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
import io.flutter.plugin.common.MethodChannel

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
    
    // تعريف المحلل (اختياري إذا كنت ستعتمد كلياً على فلاتر، ولكن يفضل وجوده للتحقق)
    private var nsfwAnalyzer: NSFWAnalyzer? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private val captureIntervalMs = 3000L // التقاط كل 3 ثوانٍ لتوفير البطارية
    private var isCapturing = false

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                captureFrame()
                handler.postDelayed(this, captureIntervalMs)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    startMonitoring(resultCode, resultData)
                }
            }
            ACTION_STOP -> {
                stopMonitoring()
            }
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring(resultCode: Int, resultData: Intent) {
        if (isMonitoring) return

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "safescreen_channel")
            .setContentTitle("غدو - الحماية نشطة")
            .setContentText("يتم الآن مراقبة الشاشة لحمايتك.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        startForeground(1, notification)

        overlayManager = OverlayManager(this)
        nsfwAnalyzer = NSFWAnalyzer(this)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        setupVirtualDisplay()

        isMonitoring = true
        isCapturing = true
        handler.post(captureRunnable)
        
        Log.d("Ghadhoo", "Screen monitoring service started.")
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }

        // تقليل الدقة للنصف لتحسين أداء الذكاء الاصطناعي وتقليل استهلاك الذاكرة
        val width = metrics.widthPixels / 2
        val height = metrics.heightPixels / 2
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }

    private fun captureFrame() {
        val image = imageReader?.acquireLatestImage()
        if (image != null) {
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                
                val width = image.width
                val height = image.height

                // إرسال الإطار إلى جانب Flutter للتحليل عبر SafeScreenController
                MainActivity.methodChannel?.invokeMethod(
                    "processFrameInFlutter",
                    mapOf(
                        "bytes" to bytes,
                        "width" to width,
                        "height" to height
                    ),
                    object : MethodChannel.Result {
                        override fun success(result: Any?) {
                            val shouldBlock = result as? Boolean ?: false
                            if (shouldBlock) {
                                overlayManager?.showOverlay()
                            } else {
                                overlayManager?.removeOverlay()
                            }
                        }

                        override fun error(errorCode: String, errorMessage: String?, p2: Any?) {
                            Log.e("Ghadhoo", "Flutter error: $errorMessage")
                        }

                        override fun notImplemented() {
                            Log.e("Ghadhoo", "Method not implemented in Flutter")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("Ghadhoo", "Error capturing frame: ${e.message}")
            } finally {
                image.close() // ضروري جداً لتجنب تسريب الذاكرة
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
        Log.d("Ghadhoo", "Screen monitoring service stopped.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "safescreen_channel",
                "Ghadhoo Protection Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
    }
}