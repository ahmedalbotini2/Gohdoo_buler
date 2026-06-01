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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScreenMonitorService : Service() {

    companion object {
        const val ACTION_START      = "ACTION_START"
        const val ACTION_STOP       = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        var isMonitoring = false
            private set

        private var overlayRef: OverlayManager? = null

        fun setBlurStrength(value: Float) {
            overlayRef?.updateBlurRadius(value)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay:  VirtualDisplay?  = null
    private var imageReader:     ImageReader?      = null
    private var overlayManager:  OverlayManager?  = null
    private var analyzer:        ContentAnalyzer? = null

    private val serviceScope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler            = Handler(Looper.getMainLooper())
    private val captureIntervalMs  = 1500L
    private var isCapturing        = false

    // ── عداد التأكيد: N فريم آمن متتالي قبل رفع الـ Blur ───────────────────
    private val safeFramesRequired = 3
    private var safeFrameCount     = 0
    private var isCurrentlyBlurred = false

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
                val resultData: Intent? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultData != null) startMonitoring(resultCode, resultData)
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
            .setContentText("يتم تحليل محتوى الشاشة محلياً.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true).build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, notification)

        overlayManager = OverlayManager(this)
        overlayRef     = overlayManager
        analyzer       = LocalAiAnalyzer(this)

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, resultData)
        setupVirtualDisplay()

        isMonitoring = true
        isCapturing  = true
        handler.post(captureRunnable)
        Log.d("Ghadhoo", "✅ الخدمة تعمل")
    }

    private fun setupVirtualDisplay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            metrics.widthPixels  = b.width()
            metrics.heightPixels = b.height()
            metrics.densityDpi   = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        }

        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val density = metrics.densityDpi

        // ✅ ImageReader للتحليل فقط — لم نعد نحتاج الصورة للـ Overlay
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GhadhooCapture", screenW, screenH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun captureFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            if (image.planes.isEmpty()) return

            val localAnalyzer = analyzer as? LocalAiAnalyzer
            // نحتاج الـ Bitmap للتحليل فقط — لا نمرره للـ Overlay
            val screenshot = localAnalyzer?.imageToBitmap(image) ?: return

            serviceScope.launch {
                val result = analyzer?.analyze(screenshot) ?: return@launch
                // تحرير الـ Bitmap فور انتهاء التحليل — لم نعد نحتاجه
                screenshot.recycle()

                if (result.isUnsafe) {
                    // ── محتوى سيء → أضف طبقة Blur وأعد العداد ──────────────
                    safeFrameCount     = 0
                    isCurrentlyBlurred = true
                    overlayManager?.showBlurLayer()
                    Log.w("Ghadhoo", "🚨 محتوى غير آمن — Blur مفعّل")

                } else if (isCurrentlyBlurred) {
                    // ── محتوى آمن + Blur ظاهر → ابدأ العد ───────────────────
                    safeFrameCount++
                    Log.d("Ghadhoo", "✅ فريم آمن $safeFrameCount/$safeFramesRequired")

                    if (safeFrameCount >= safeFramesRequired) {
                        safeFrameCount     = 0
                        isCurrentlyBlurred = false
                        overlayManager?.removeOverlay()
                        Log.d("Ghadhoo", "✅ تأكد اختفاء المحتوى — رُفع الـ Blur")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Ghadhoo", "❌ خطأ: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun stopMonitoring() {
        isCapturing        = false
        isMonitoring       = false
        safeFrameCount     = 0
        isCurrentlyBlurred = false
        handler.removeCallbacks(captureRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        overlayManager?.removeOverlay()
        overlayRef = null
        analyzer?.close()
        analyzer = null
        stopForeground(true)
        stopSelf()
        Log.d("Ghadhoo", "✅ الخدمة توقفت")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "safescreen_channel", "Ghadhoo Protection", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() { super.onDestroy(); stopMonitoring() }
}