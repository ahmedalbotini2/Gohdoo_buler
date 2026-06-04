package com.ghadhoo_buler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ScreenMonitorService : Service() {

    companion object {
        const val ACTION_START      = "ACTION_START"
        const val ACTION_STOP       = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        var isMonitoring = false
            private set

        private var overlayManagerRef: OverlayManager? = null
        fun setBlurRadius(radius: Float) { overlayManagerRef?.blurRadius = radius }
        fun setOverlayColor(color: Int)  { overlayManagerRef?.overlayColor = color }
    }

    // ── متغيرات مشتركة ────────────────────────────────────────────────────
    private var mediaProjection: MediaProjection?   = null
    private var overlayManager: OverlayManager?     = null
    private var localAiAnalyzer: LocalAiAnalyzer?   = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handler      = Handler(Looper.getMainLooper())
    private val captureMs    = 300L
    private var isCapturing  = false
    private val safeRequired = 10
    private var safeCount    = 0

    // ── Android 11-: متغيرات خاصة ────────────────────────────────────────
    private var virtualDisplay11: VirtualDisplay? = null
    private var imageReader11: ImageReader?       = null

    // ── Android 12+: متغيرات خاصة (reader ثانٍ بدون overlay) ─────────────
    private var virtualDisplay12: VirtualDisplay? = null   // عادي مع overlay
    private var imageReader12Clean: ImageReader?  = null   // بدون overlay للتحليل
    private var virtualDisplay12Clean: VirtualDisplay? = null

    private val isA12 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                captureFrame()
                handler.postDelayed(this, captureMs)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data != null) startMonitoring(code, data)
            }
            ACTION_STOP -> stopMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring(resultCode: Int, resultData: Intent) {
        if (isMonitoring) return
        createNotificationChannel()

        val notif = NotificationCompat.Builder(this, "safescreen_channel")
            .setContentTitle("غُضُّوا — الحماية نشطة")
            .setContentText("يتم تحليل الشاشة بالذكاء الاصطناعي المحلي.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else
            startForeground(1, notif)

        overlayManager    = OverlayManager(this)
        overlayManagerRef = overlayManager
        localAiAnalyzer   = LocalAiAnalyzer(this)

        val projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projMgr.getMediaProjection(resultCode, resultData)

        if (isA12) setupVirtualDisplay12() else setupVirtualDisplay11()

        isMonitoring = true
        isCapturing  = true
        handler.post(captureRunnable)
        Log.d("Ghadhoo", "✅ الخدمة تعمل — ${if (isA12) "Android 12+" else "Android 11-"}")
    }

    // ════════════════════════════════════════════════════════════════════════
    // Android 11-: VirtualDisplay واحد بسيط
    // ════════════════════════════════════════════════════════════════════════
    private fun setupVirtualDisplay11() {
        val density = getDensity()
        imageReader11 = ImageReader.newInstance(480, 800, PixelFormat.RGBA_8888, 2)
        virtualDisplay11 = mediaProjection?.createVirtualDisplay(
            "GhadhooCapture11", 480, 800, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader11?.surface, null, null
        )
        Log.d("Ghadhoo", "✅ VirtualDisplay11 جاهز")
    }

    // ════════════════════════════════════════════════════════════════════════
    // Android 12+: VirtualDisplay نظيف (flag=0) للتحليل فقط
    // TYPE_APPLICATION_OVERLAY لا يظهر في VirtualDisplay بدون FLAG_AUTO_MIRROR
    // لذلك الـ reader النظيف يرى المحتوى الحقيقي بدون الـ blur overlay
    // ════════════════════════════════════════════════════════════════════════
    private fun setupVirtualDisplay12() {
        val density = getDensity()

        // Reader نظيف للتحليل — بدون FLAG_AUTO_MIRROR لا يرى الـ overlay
        imageReader12Clean = ImageReader.newInstance(480, 800, PixelFormat.RGBA_8888, 2)
        virtualDisplay12Clean = mediaProjection?.createVirtualDisplay(
            "GhadhooCapture12Clean", 480, 800, density,
            0, // ← لا flags — لا يعكس الـ overlay
            imageReader12Clean?.surface, null, null
        )
        Log.d("Ghadhoo", "✅ VirtualDisplay12Clean جاهز (بدون overlay)")
    }

    private fun getDensity(): Int {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            resources.configuration.densityDpi
        else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
            metrics.densityDpi
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // captureFrame: يختار الـ reader المناسب حسب الإصدار
    // ════════════════════════════════════════════════════════════════════════
    private fun captureFrame() {
        val image = if (isA12)
            imageReader12Clean?.acquireLatestImage()   // ← Android 12: بدون overlay
        else
            imageReader11?.acquireLatestImage()        // ← Android 11: عادي

        image ?: return

        try {
            if (image.planes.isEmpty()) return
            val bitmap: Bitmap = localAiAnalyzer?.imageToBitmap(image) ?: return

            // التحقق من صحة الـ bitmap قبل التحليل (يمنع overflow)
            if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) {
                bitmap.recycle()
                return
            }

            serviceScope.launch {
                try {
                    val isUnsafe = localAiAnalyzer?.analyze(bitmap)?.isUnsafe ?: false
                    if (isUnsafe) {
                        safeCount = 0
                        overlayManager?.showOverlay()
                    } else {
                        safeCount++
                        if (safeCount >= safeRequired)
                            overlayManager?.removeOverlay()
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e("Ghadhoo", "❌ captureFrame: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun stopMonitoring() {
        isCapturing  = false
        isMonitoring = false
        safeCount    = 0
        handler.removeCallbacks(captureRunnable)
        serviceScope.cancel()

        // تحرير Android 11
        virtualDisplay11?.release()
        imageReader11?.close()
        virtualDisplay11 = null
        imageReader11    = null

        // تحرير Android 12
        virtualDisplay12?.release()
        virtualDisplay12Clean?.release()
        imageReader12Clean?.close()
        virtualDisplay12      = null
        virtualDisplay12Clean = null
        imageReader12Clean    = null

        mediaProjection?.stop()
        mediaProjection = null

        overlayManager?.removeOverlay()
        localAiAnalyzer?.close()
        localAiAnalyzer   = null
        overlayManagerRef = null
        overlayManager    = null

        stopForeground(true)
        stopSelf()
        Log.d("Ghadhoo", "✅ الخدمة أوقفت")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "safescreen_channel", "غُضُّوا — حارس البصر",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "إشعار الحماية النشطة"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() { super.onDestroy(); stopMonitoring() }
}