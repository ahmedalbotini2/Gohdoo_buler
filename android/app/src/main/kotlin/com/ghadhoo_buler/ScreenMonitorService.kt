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

class ScreenMonitorService : Service() {

    companion object {
        const val ACTION_START      = "ACTION_START"
        const val ACTION_STOP       = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        // ❌ أُزيلت مفاتيح ونقاط نهاية السحابة (OpenRouter / الباك اند) —
        // لم تعد مستخدمة بعد التحويل الكامل لخط أنابيب محلي بالكامل
        // (nsfw.tflite + yolo11n.tflite)، بلا أي اتصال إنترنت في مسار التحليل.

        var isMonitoring = false
            private set

        // ✅ وضع المحلل — محلي (سريع/عام) افتراضيًا عند كل تشغيل للتطبيق
        // (لا يُخزَّن بين الجلسات عمداً، حتى يبدأ التطبيق دائماً بالخصوصية الكاملة أولاً)
        //   - useLocalAi = true  → "اعتيادي": LocalAiAnalyzer فقط، يحجب الشاشة كاملة عند الاشتباه
        //   - useLocalAi = false → "احترافي": LocalAiAnalyzer كبوابة + YoloPersonAnalyzer
        //                          لتحديد موقع الحجب بدقة (شخص/أشخاص فقط) — كله محلي، بدون سحابة
        var useLocalAi = true
            private set

        private var overlayManagerRef: OverlayManager? = null
        fun setBlurRadius(radius: Float) { overlayManagerRef?.blurRadius = radius }
        fun setOverlayColor(color: Int)  { overlayManagerRef?.overlayColor = color }

        // ✅ يستقبل اختيار الوضع من Flutter عبر MainActivity
        fun setAnalyzerMode(local: Boolean) {
            useLocalAi = local
            Log.d("Ghadhoo", "🔄 تم تغيير وضع المحلل إلى: ${if (local) "عادي (حجب كامل)" else "احترافي (حجب دقيق محلي عبر YOLO)"}")
        }
    }

    private var mediaProjection: MediaProjection?   = null
    private var overlayManager: OverlayManager?     = null

    // البوابة الأولى في كل الأحوال: تصنّف كل إطار آمن/غير آمن (nsfw.tflite)
    // - في الوضع العادي: هي المحلل الوحيد المستخدم
    // - في الوضع الاحترافي: تُستخدم كبوابة سريعة، ثم يُستدعى YOLO فقط عند الاشتباه
    private var localGateAnalyzer: LocalAiAnalyzer? = null

    // ✅ جديد: يحل محل المحلل السحابي سابقاً. يشغّل yolo11n.tflite محلياً
    // على الجهاز لتحديد صناديق "شخص" داخل الصورة عندما تشتبه البوابة
    // المحلية (localGateAnalyzer) بمحتوى غير آمن. يبقى null في الوضع
    // العادي (useLocalAi = true) — غير مستخدم هناك.
    private var yoloAnalyzer: YoloPersonAnalyzer? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handler      = Handler(Looper.getMainLooper())

    // ✅ وقت التقاط ثابت وسريع للحالتين. في الوضع الاحترافي، كل إطار يمر
    // أولاً على البوابة المحلية السريعة (nsfw.tflite) — YOLO (أثقل قليلاً)
    // لا يُستدعى إلا لو البوابة اشتبهت، مش كل إطار.
    private val captureMs: Long = 1000L
    private var isCapturing  = false
    // ✅ يمنع بدء تحليل جديد قبل اكتمال التحليل الحالي
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

        val notifText = if (useLocalAi)
            "يتم تحليل الشاشة محلياً على جهازك."
        else
            "يتم تحليل الشاشة محلياً مع تحديد دقيق لموقع الحجب."

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

        // ✅ تهيئة المحللات حسب الوضع المختار من الواجهة
        if (useLocalAi) {
            // الوضع العادي: بوابة محلية فقط، بلا YOLO — حجب الشاشة كاملة عند الاشتباه
            localGateAnalyzer = LocalAiAnalyzer(this)
            yoloAnalyzer = null
        } else {
            // الوضع الاحترافي: بوابة محلية سريعة + YOLO محلي لتحديد موقع الحجب
            // بدقة (كل شيء على الجهاز، بدون أي اتصال سحابي)
            localGateAnalyzer = LocalAiAnalyzer(this)
            yoloAnalyzer = YoloPersonAnalyzer(this)
        }

        val projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projMgr.getMediaProjection(resultCode, resultData)

        if (isA12) setupVirtualDisplay12() else setupVirtualDisplay11()

        isMonitoring = true
        isCapturing  = true
        handler.post(captureRunnable)
        Log.d("Ghadhoo", if (useLocalAi) "✅ الخدمة تعمل — وضع عادي (LocalAiAnalyzer فقط)"
                          else "✅ الخدمة تعمل — وضع احترافي: بوابة محلية + YOLO محلي لتحديد موقع الحجب")
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

    // دالة تحويل الصورة الافتراضية إلى Bitmap (تُستخدم قبل تمريرها للمحللات)
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
        // ✅ لا نبدأ التقاطاً/تحليلاً جديداً قبل اكتمال السابق
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
                    // البوابة المحلية تعمل في كل الأحوال (عادي واحترافي)
                    val gateResult = localGateAnalyzer?.analyze(bitmap)

                    if (useLocalAi) {
                        // الوضع العادي: البوابة هي القرار النهائي — حجب كامل عند الاشتباه
                        applyAnalysisResult(gateResult)
                    } else {
                        // الوضع الاحترافي: لو البوابة قالت آمن، نكتفي بها
                        if (gateResult?.isUnsafe != true) {
                            applyAnalysisResult(gateResult)
                        } else {
                            // اشتباه محلي → استدعاء YOLO محلياً لتحديد موقع الحجب بدقة
                            Log.d("Ghadhoo", "🔎 البوابة المحلية اشتبهت في محتوى غير آمن — تشغيل YOLO لتحديد موقع الحجب...")
                            runYoloLocalization(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Ghadhoo", "❌ خطأ غير متوقع أثناء التحليل: ${e.message}")
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    isAnalyzing = false
                }
            }
        } catch (e: Exception) {
            Log.e("Ghadhoo", "❌ captureFrame: ${e.message}")
            isAnalyzing = false
        } finally {
            image.close()
        }
    }

    // ✅ يطبّق نتيجة تحليل (من البوابة أو من YOLO) على الـ overlay وsafeCount
    private fun applyAnalysisResult(result: AnalysisResult?) {
        val isUnsafe = result?.isUnsafe ?: false

        if (isUnsafe) {
            safeCount = 0
            // نمرر المناطق المكتشفة إن وجدت (من YOLO) — وإن كانت null
            // (بوابة محلية بحتة، أو YOLO لم يجد أشخاصاً رغم الاشتباه)
            // تُغطّى الشاشة بالكامل كإجراء احترازي
            overlayManager?.showOverlay(result?.regions)
            Log.d("Ghadhoo", "🚨 تم التعتيم! (مناطق=${result?.regions?.size ?: "شاشة كاملة"})")
        } else {
            safeCount++
            if (safeCount >= safeRequired) {
                overlayManager?.removeOverlay()
            }
        }
    }

    // ✅ يُستدعى فقط لما البوابة المحلية تشتبه في محتوى غير آمن — يشغّل
    // yolo11n.tflite محلياً على نفس الإطار لتحديد صناديق "شخص" بدقة.
    // كل العملية محلية بالكامل، بلا أي اتصال شبكة.
    private suspend fun runYoloLocalization(bitmap: Bitmap) {
        try {
            val regions: List<RegionResult> = yoloAnalyzer?.detectPersons(bitmap) ?: emptyList()
            val result = AnalysisResult(
                isUnsafe = true,
                regions = if (regions.isNotEmpty()) regions else null,
                reason = "yolo_person_localization"
            )
            applyAnalysisResult(result)
        } catch (e: Exception) {
            Log.e("Ghadhoo", "❌ خطأ أثناء تحديد الموقع عبر YOLO: ${e.message}")
            // البوابة المحلية أصلاً اشتبهت — الأمان أولاً: نعتّم الشاشة
            // كاملة احتياطياً بدل ترك المحتوى المشتبه به بلا حجب
            overlayManager?.showOverlay(null)
            safeCount = 0
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

        localGateAnalyzer?.close()
        localGateAnalyzer = null

        yoloAnalyzer?.close()
        yoloAnalyzer = null

        overlayManagerRef = null
        overlayManager    = null

        stopForeground(true)
        stopSelf()
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