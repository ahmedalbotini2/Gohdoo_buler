package com.ghadhoo_buler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
    private var localAiAnalyzer: LocalAiAnalyzer? = null

    private val handler = Handler(Looper.getMainLooper())
    private val captureIntervalMs = 1500L
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
                // ✅ الإصلاح: استخدام getParcelableExtra المتوافق مع Android 13+
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultData != null) {
                    startMonitoring(resultCode, resultData)
                }
            }
            ACTION_STOP -> stopMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring(resultCode: Int, resultData: Intent) {
        if (isMonitoring) return

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "safescreen_channel")
            .setContentTitle("غدو - الحماية نشطة")
            .setContentText("يتم الآن تحليل محتوى الشاشة محلياً.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        overlayManager = OverlayManager(this)
        localAiAnalyzer = LocalAiAnalyzer(this)

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        setupVirtualDisplay()

        isMonitoring = true
        isCapturing = true
        handler.post(captureRunnable)

        Log.d("Ghadhoo", "تم تشغيل خدمة المراقبة بنجاح.")
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }

        val width = 480
        val height = 800
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GhadhooCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }

    private fun captureFrame() {
        // ✅ الإصلاح: acquireLatestImage قد يرجع null عند عدم وجود فريم جديد، نتجاهلها بأمان
        val image = imageReader?.acquireLatestImage() ?: return

        try {
            // ✅ التحقق من planes قبل المعالجة لتجنب ArrayIndexOutOfBoundsException
            if (image.planes.isEmpty()) {
                Log.w("Ghadhoo", "الفريم لا يحتوي على planes.")
                return
            }

            val isUnsafe = localAiAnalyzer?.analyzeImage(image) ?: false

            if (isUnsafe) {
                Log.w("Ghadhoo", "محتوى غير آمن! جاري الحجب...")
                overlayManager?.showOverlay()
            } else {
                overlayManager?.removeOverlay()
            }

        } catch (e: Exception) {
            Log.e("Ghadhoo", "خطأ أثناء التحليل: ${e.message}")
        } finally {
            // ✅ إغلاق الـ Image دائماً في finally لضمان تحرير الموارد حتى عند الخطأ
            image.close()
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

        // ✅ الإصلاح: تحرير موارد الـ Interpreter لمنع تسرب الذاكرة
        localAiAnalyzer?.close()
        localAiAnalyzer = null

        stopForeground(true)
        stopSelf()
        Log.d("Ghadhoo", "تم إيقاف الخدمة وتحرير جميع الموارد.")
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