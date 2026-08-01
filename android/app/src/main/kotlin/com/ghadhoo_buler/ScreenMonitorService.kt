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
        //   - useLocalAi = false → "احترافي": YoloPersonAnalyzer (gohdooai.tflite) فقط،
        //                          يعمل مباشرة على كل إطار ويحجب فقط ما يكتشفه بدقة
        //                          (بلا بوابة، بلا اشتباه، بلا حجب احتياطي) — كله محلي بالكامل
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

    // البوابة الأولى: تصنّف كل إطار آمن/غير آمن (nsfw.tflite)
    // - في الوضع العادي: هي المحلل الوحيد المستخدم
    // - في الوضع الاحترافي: ✅ لم تعد تُستخدم إطلاقاً (تبقى null) — الكاشف
    //   الدقيق (yoloAnalyzer) يعمل مباشرة بلا بوابة تسبقه
    private var localGateAnalyzer: LocalAiAnalyzer? = null

    // ✅ يشغّل gohdooai.tflite محلياً على الجهاز لتحديد صناديق الأجزاء
    // الإباحية الصريحة داخل الصورة مباشرة على كل إطار (بلا بوابة تسبقه في
    // الوضع الاحترافي). يبقى null في الوضع العادي — غير مستخدم هناك.
    private var yoloAnalyzer: YoloPersonAnalyzer? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handler      = Handler(Looper.getMainLooper())

    // ✅ وقت التقاط ثابت. في الوضع الاحترافي، gohdooai.tflite يعمل على كل
    // إطار مباشرة (بلا بوابة تسبقه).
    private val captureMs: Long = 500L
    private var isCapturing  = false
    // ✅ يمنع بدء تحليل جديد قبل اكتمال التحليل الحالي
    @Volatile private var isAnalyzing = false
    // ✅ رحلة القيمة: 3 (تسبب تأخر إزالة الحجب ~3 ثوانٍ بعد المقطع غير الآمن،
    // فيتداخل مع مقطع آمن تالٍ) → 1 (إزالة فورية، لكن سبّبت وميض الصندوق:
    // يظهر ثم يختفي بسرعة بسبب تذبذب ثقة الكشف حول العتبة كل إطار مستقل) →
    // 2 (التوازن الحالي: يحتاج إطارين متتاليين بلا اكتشاف قبل إزالة الحجب،
    // تأخير طفيف ~ثانية واحدة إضافية يمتص الوميض دون تأخير ملحوظ عند الانتقال).
    private val safeRequired = 5
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
            // ✅ الوضع الاحترافي: gohdooai.tflite هو المصدر الأساسي والدقيق —
            // يعمل مباشرة على كل إطار، وإن وجد صندوقاً، يُحجب هو فقط بدقة.
            // localGateAnalyzer أُعيد تفعيله هنا لكن كطبقة احتياطية ثانوية
            // فقط: يُستشار فقط عندما لا يجد الكاشف الدقيق أي صندوق، لالتقاط
            // محتوى "خادش" (مثير لكن غير عارٍ صراحة) لا يستطيع الكاشف الدقيق
            // كشفه أصلاً لأنه مُدرَّب فقط على أجزاء مكشوفة حرفياً.
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
                          else "✅ الخدمة تعمل — وضع احترافي: كاشف دقيق مباشر (gohdooai.tflite) بلا بوابة")
    }

    // ✅ أبعاد الالتقاط الفعلية المستخدمة هذه الجلسة — تُحسب بحيث تحافظ على
    // نفس نسبة عرض/ارتفاع الشاشة الحقيقية (وإلا تنحرف مواقع المناطق المكتشفة
    // عند رسم الـ overlay، لأن الذكاء الاصطناعي يرجع نسباً 0..1 محسوبة على
    // صورة الالتقاط لا على الشاشة الحقيقية)
    private var captureWidth  = 480
    private var captureHeight = 800

    private fun computeCaptureSize() {
        val (screenW, screenH) = getRealScreenSize()
        // ✅ خُفِّضت من 800 إلى 480: النموذج يُصغِّرها لـ320×320 على أي حال،
        // فالتقاط 800px كان يُهدر وقتاً في النسخ/التحجيم قبل الوصول للنموذج
        // أصلاً بلا أي فائدة إضافية في الدقة. تقليلها يُسرّع كل مراحل خط
        // الأنابيب (التقاط → letterbox → استدلال) وبالتالي يقلّل الإطارات
        // المتخطّاة (⏭️) بسبب انشغال التحليل السابق.
        val targetLongSide = 480
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
                    if (useLocalAi) {
                        // الوضع العادي: البوابة هي القرار النهائي — حجب كامل عند الاشتباه
                        val gateResult = localGateAnalyzer?.analyze(bitmap)
                        applyAnalysisResult(gateResult)
                    } else {
                        // ✅ الوضع الاحترافي: gohdooai.tflite يرجع الآن صناديق
                        // "مكشوفة" و"مغطاة" معاً (COVERED محسوبة أيضاً لكنها
                        // كانت تُهمَل سابقاً). نصنّفها هنا:
                        //   - وُجد صندوق EXPOSED → عري صريح → يُحجب هو فقط بدقة
                        //   - لا EXPOSED لكن البوابة العامة اشتبهت بمحتوى خادش
                        //     ووُجدت صناديق COVERED → نستخدمها كموقع دقيق تقريبي
                        //     (بدل التغطية الكاملة!) — هذا يعطي دقة مكانية حتى
                        //     للمحتوى المثير بلا نموذج مخصص
                        //   - لا شيء من الاثنين → لا حجب إطلاقاً
                        val allRegions: List<RegionResult> = yoloAnalyzer?.detectPersons(bitmap) ?: emptyList()
                        val exposedRegions = allRegions.filter { YoloPersonAnalyzer.isExposedLabel(it.label) }

                        if (exposedRegions.isNotEmpty()) {
                            applyAnalysisResult(
                                AnalysisResult(isUnsafe = true, regions = exposedRegions, reason = "yolo_exposed_detection")
                            )
                        } else {
                            val gateResult = localGateAnalyzer?.analyze(bitmap)
                            if (gateResult?.isUnsafe == true) {
                                val coveredRegions = allRegions.filter { YoloPersonAnalyzer.isCoveredLabel(it.label) }
                                if (coveredRegions.isNotEmpty()) {
                                    Log.d("Ghadhoo", "🔎 البوابة اشتبهت بمحتوى خادش — استخدام مواقع COVERED (${coveredRegions.size}) بدل التغطية الكاملة")
                                    applyAnalysisResult(
                                        AnalysisResult(isUnsafe = true, regions = coveredRegions, reason = "gate_covered_fallback")
                                    )
                                } else {
                                    // البوابة اشتبهت لكن لا يوجد أي موقع (لا EXPOSED
                                    // ولا COVERED) — نتجاهل هذا الإطار بدل تغطية
                                    // الشاشة كاملة، حفاظاً على مبدأ "بلا حجب احتياطي"
                                    Log.w("Ghadhoo", "⚠️ البوابة اشتبهت لكن لا يوجد أي موقع محدد — تم تجاهل هذا الإطار")
                                    applyAnalysisResult(AnalysisResult(isUnsafe = false, regions = null, reason = "no_location_available"))
                                }
                            } else {
                                applyAnalysisResult(gateResult)
                            }
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

    // ✅ يطبّق نتيجة تحليل (من البوابة في الوضع العادي، أو من الكاشف الدقيق
    // مباشرة في الوضع الاحترافي) على الـ overlay وsafeCount
    private fun applyAnalysisResult(result: AnalysisResult?) {
        val isUnsafe = result?.isUnsafe ?: false

        if (isUnsafe) {
            safeCount = 0
            // نمرر المناطق المكتشفة (من الكاشف الدقيق في الوضع الاحترافي)،
            // أو null في الوضع العادي (تغطية الشاشة كاملة — هذا وضعها الطبيعي
            // المقصود). في الوضع الاحترافي regions لا يمكن أن تكون null هنا
            // أصلاً لأن isUnsafe لا يكون true إلا إذا وُجدت مناطق فعلية.
            overlayManager?.showOverlay(result?.regions)
            Log.d("Ghadhoo", "🚨 تم التعتيم! (مناطق=${result?.regions?.size ?: "شاشة كاملة"})")
        } else {
            safeCount++
            if (safeCount >= safeRequired) {
                overlayManager?.removeOverlay()
            }
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