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
import android.media.Image
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
import com.ghadhoo_buler.BuildConfig

class ScreenMonitorService : Service() {

    companion object {
        const val ACTION_START      = "ACTION_START"
        const val ACTION_STOP       = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        var openRouterApiKey = BuildConfig.OPENROUTER_API_KEY// ✅ مفتاح OpenRouter — استبدله بمفتاحك الخاص
        var isMonitoring = false
            private set

        // ✅ جديد: وضع المحلل — محلي افتراضيًا عند كل تشغيل للتطبيق
        // (لا يُخزَّن بين الجلسات عمداً، حتى يبدأ التطبيق دائماً بالخصوصية الكاملة أولاً)
        var useLocalAi = true
            private set

        private var overlayManagerRef: OverlayManager? = null
        fun setBlurRadius(radius: Float) { overlayManagerRef?.blurRadius = radius }
        fun setOverlayColor(color: Int)  { overlayManagerRef?.overlayColor = color }

        // ✅ جديد: يستقبل اختيار الوضع من Flutter عبر MainActivity
        fun setAnalyzerMode(local: Boolean) {
            useLocalAi = local
            Log.d("Ghadhoo", "🔄 تم تغيير وضع المحلل إلى: ${if (local) "محلي" else "احترافي (سحابي)"}")
        }
    }

    private var mediaProjection: MediaProjection?   = null
    private var overlayManager: OverlayManager?     = null

    // ✅ يمنع تكرار رسالة/عملية التراجع للمحلي إذا تكرر 429 أكثر من مرة بالجلسة
    private var rateLimitFallbackTriggered = false

    // استخدام الواجهة المشتركة — يتم اختيار التطبيق الفعلي (محلي/سحابي) في startMonitoring()
    private var aiAnalyzer: ContentAnalyzer?        = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handler      = Handler(Looper.getMainLooper())
    // وقت الالتقاط: نرفعه تلقائيًا عند استخدام الوضع السحابي لأنه يحتاج وقتاً للرد عبر الإنترنت
    private val captureMs: Long
        get() = if (useLocalAi) 1000L else 3000L
    private var isCapturing  = false
    // ✅ يمنع بدء تحليل جديد قبل اكتمال التحليل الحالي (مهم خصوصاً مع تدوير
    // الموديلات السحابية، حيث قد تستغرق دورة كاملة وقتاً أطول من captureMs)
    @Volatile private var isAnalyzing = false
    private val safeRequired = 3
    private var safeCount    = 0

    private var virtualDisplay11: VirtualDisplay? = null
    private var imageReader11: ImageReader?       = null

    private var virtualDisplay12: VirtualDisplay? = null
    private var imageReader12Clean: ImageReader?  = null
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
        rateLimitFallbackTriggered = false // ✅ جلسة جديدة، نسمح بالتراجع التلقائي من جديد إن لزم

        val notifText = if (useLocalAi)
            "يتم تحليل الشاشة محلياً على جهازك."
        else
            "يتم تحليل الشاشة سحابياً للتجربة."

        val notif = NotificationCompat.Builder(this, "safescreen_channel")
            .setContentTitle("غُضُّوا — الحماية نشطة")
            .setContentText(notifText)
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

        // ✅ تهيئة المحلل المناسب حسب الوضع المختار من الواجهة
        aiAnalyzer = if (useLocalAi) {
            LocalAiAnalyzer(this)
        } else {
               DirectOpenRouterAnalyzer(
            openRouterApiKey,
         listOf("nvidia/nemotron-nano-12b-v2-vl:free",
           "google/gemma-4-31b-it:free",
           "google/gemma-4-26b-a4b-it:free"))
           //DirectOpenRouterAnalyzer("key")
        }

        val projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projMgr.getMediaProjection(resultCode, resultData)

        if (isA12) setupVirtualDisplay12() else setupVirtualDisplay11()

        isMonitoring = true
        isCapturing  = true
        handler.post(captureRunnable)
        Log.d("Ghadhoo", if (useLocalAi) "✅ الخدمة تعمل — محلل محلي (LocalAiAnalyzer)"
                          else "✅ الخدمة تعمل — يتم استخدام OpenRouter")
    }

    // ✅ أبعاد الالتقاط الفعلية المستخدمة هذه الجلسة — تُحسب بحيث تحافظ على
    // نفس نسبة عرض/ارتفاع الشاشة الحقيقية (وإلا تنحرف مواقع المناطق المكتشفة
    // عند رسم الـ overlay، لأن الذكاء الاصطناعي يرجع نسباً 0..1 محسوبة على
    // صورة الالتقاط لا على الشاشة الحقيقية)
    private var captureWidth  = 480
    private var captureHeight = 800

    private fun computeCaptureSize() {
        val (screenW, screenH) = getRealScreenSize()
        // نحافظ على البعد الأكبر تقريباً 800px مع نفس نسبة عرض/ارتفاع الشاشة
        val targetLongSide = 800
        if (screenH >= screenW) {
            captureHeight = targetLongSide
            captureWidth  = (targetLongSide.toFloat() * screenW / screenH).toInt().coerceAtLeast(1)
        } else {
            captureWidth  = targetLongSide
            captureHeight = (targetLongSide.toFloat() * screenH / screenW).toInt().coerceAtLeast(1)
        }
        Log.d("Ghadhoo", "📐 أبعاد الالتقاط: ${captureWidth}x${captureHeight} (شاشة: ${screenW}x${screenH})")
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun setupVirtualDisplay11() {
        computeCaptureSize()
        val density = getDensity()
        imageReader11 = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay11 = mediaProjection?.createVirtualDisplay(
            "GhadhooCapture11", captureWidth, captureHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader11?.surface, null, null
        )
    }

    private fun setupVirtualDisplay12() {
        computeCaptureSize()
        val density = getDensity()
        imageReader12Clean = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay12Clean = mediaProjection?.createVirtualDisplay(
            "GhadhooCapture12Clean", captureWidth, captureHeight, density,
            0,
            imageReader12Clean?.surface, null, null
        )
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

    // دالة تحويل الصورة الافتراضية (تُستخدم من المحلل السحابي)
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun captureFrame() {
        // ✅ لا نبدأ التقاطاً/تحليلاً جديداً قبل اكتمال السابق — يمنع تراكم
        // طلبات OpenRouter المتزامنة ويحافظ على الحصة المجانية
        if (isAnalyzing) {
            Log.d("Ghadhoo", "⏭️ تخطي هذا الإطار — التحليل السابق لم يكتمل بعد")
            return
        }

        val image = if (isA12)
            imageReader12Clean?.acquireLatestImage()
        else
            imageReader11?.acquireLatestImage()

        image ?: return

        try {
            if (image.planes.isEmpty()) return
            val bitmap: Bitmap = imageToBitmap(image) ?: return

            if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) {
                bitmap.recycle()
                return
            }

            isAnalyzing = true
            serviceScope.launch {
                try {
                    val result = aiAnalyzer?.analyze(bitmap)
                    val isUnsafe = result?.isUnsafe ?: false

                    if (isUnsafe) {
                        safeCount = 0
                        // ✅ نمرر المناطق المكتشفة إن وجدت (الوضع السحابي) — وإن
                        // كانت null (الوضع المحلي) سيُغطّى الشاشة بالكامل كالسابق
                        overlayManager?.showOverlay(result?.regions)
                        Log.d("Ghadhoo", "🚨 تم التعتيم! (مناطق=${result?.regions?.size ?: "شاشة كاملة"})")
                    } else {
                        safeCount++
                        if (safeCount >= safeRequired) {
                            overlayManager?.removeOverlay()
                        }
                    }
                } catch (e: RateLimitExceededException) {
                    // ✅ تجاوز الحصة المجانية اليومية لـ OpenRouter — نتراجع
                    // تلقائياً إلى المحلل المحلي حتى لا تنقطع الحماية، ونُبلّغ
                    // المستخدم عبر تحديث إشعار الخدمة القائمة (Foreground Notification)
                    Log.w("Ghadhoo", "🛑 RateLimitExceeded: ${e.message} — التراجع للمحلل المحلي")
                    handleRateLimitFallback()
                } catch (e: Exception) {
                    Log.e("Ghadhoo", "❌ خطأ غير متوقع أثناء التحليل: ${e.message}")
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    isAnalyzing = false // ✅ يسمح بالإطار التالي بعد اكتمال هذا تماماً
                }
            }
        } catch (e: Exception) {
            Log.e("Ghadhoo", "❌ captureFrame: ${e.message}")
            isAnalyzing = false
        } finally {
            image.close()
        }
    }

    private fun stopMonitoring() {
        isCapturing  = false
        isAnalyzing  = false
        isMonitoring = false
        safeCount    = 0
        handler.removeCallbacks(captureRunnable)
        serviceScope.cancel()

        virtualDisplay11?.release()
        imageReader11?.close()
        virtualDisplay11 = null
        imageReader11    = null

        virtualDisplay12?.release()
        virtualDisplay12Clean?.release()
        imageReader12Clean?.close()
        virtualDisplay12      = null
        virtualDisplay12Clean = null
        imageReader12Clean    = null

        mediaProjection?.stop()
        mediaProjection = null

        overlayManager?.removeOverlay()

        aiAnalyzer?.close()
        aiAnalyzer        = null

        overlayManagerRef = null
        overlayManager    = null

        stopForeground(true)
        stopSelf()
    }

    // ✅ يُستدعى عند رصد RateLimitExceededException من المحلل السحابي
    // (تجاوز الحصة المجانية اليومية في OpenRouter): يُبدّل المحلل النشط فوراً
    // إلى LocalAiAnalyzer لباقي الجلسة، ويُحدّث إشعار الخدمة لإبلاغ المستخدم
    private fun handleRateLimitFallback() {
        if (rateLimitFallbackTriggered) return // لا نكرر التبديل/الإشعار
        rateLimitFallbackTriggered = true

        try {
            aiAnalyzer?.close()
        } catch (_: Exception) { /* تجاهل أي خطأ إغلاق غير مهم هنا */ }

        aiAnalyzer = LocalAiAnalyzer(this)
        Log.w("Ghadhoo", "🔄 تم التبديل تلقائياً إلى المحلل المحلي بسبب تجاوز الحصة السحابية")

        updateNotification(
            "تم تجاوز الحد المجاني اليومي — تم التحويل للمحلل المحلي تلقائياً"
        )
    }

    // ✅ يُحدّث نص الإشعار القائم بدون الحاجة لإعادة إنشاء الخدمة بالكامل
    private fun updateNotification(text: String) {
        val notif = NotificationCompat.Builder(this, "safescreen_channel")
            .setContentTitle("غُضُّوا — الحماية نشطة")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, notif)
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