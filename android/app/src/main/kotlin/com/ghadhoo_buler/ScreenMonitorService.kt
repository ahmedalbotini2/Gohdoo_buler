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
        // (nsfw.tflite + yolov11_person.tflite)، بلا أي اتصال إنترنت في مسار التحليل.

        var isMonitoring = false
            private set

        // ✅ وضع المحلل — محلي (سريع/عام) افتراضيًا عند كل تشغيل للتطبيق
        // (لا يُخزَّن بين الجلسات عمداً، حتى يبدأ التطبيق دائماً بالخصوصية الكاملة أولاً)
        //   - useLocalAi = true  → "اعتيادي": LocalAiAnalyzer فقط على الإطار كاملاً،
        //                          يحجب الشاشة كاملة عند الاشتباه
        //   - useLocalAi = false → "احترافي": تسلسل من مرحلتين، كله محلي بالكامل:
        //                          1) YoloPersonAnalyzer (YOLOv11) يكتشف كل شخص في
        //                             الإطار ويقص كل واحد إلى صورة منفصلة + إحداثياته
        //                          2) كل صورة شخص تُصنَّف على حدة عبر LocalAiAnalyzer
        //                             (nsfw.tflite) — فقط من كان "غير آمن" يُحجب،
        //                             وبإحداثياته الدقيقة فقط (بلا حجب احتياطي كامل)
        var useLocalAi = true
            private set

        private var overlayManagerRef: OverlayManager? = null
        fun setBlurRadius(radius: Float) { overlayManagerRef?.blurRadius = radius }
        fun setOverlayColor(color: Int)  { overlayManagerRef?.overlayColor = color }

        // ✅ مرجع للخدمة الحيّة (إن كانت تعمل) — يُستخدم فقط لدفع تغييرات
        // فورية (إشعار + إعادة تهيئة المحللات) عند تبديل الوضع أثناء التشغيل.
        // null إذا كانت الخدمة متوقفة.
        private var instanceRef: ScreenMonitorService? = null

        // ✅ يستقبل اختيار الوضع من Flutter عبر MainActivity.
        // مهم: هذا لا يكتفي بتغيير المتغيّر الثابت — بل يدفع أيضاً إعادة
        // تهيئة المحللات الفعلية وتحديث الإشعار *فوراً* إن كانت الخدمة
        // تعمل حالياً، بدل الاكتفاء بتغيير القيمة وترك المحللات القديمة
        // (وهذا كان سبب أن التبديل يظهر في الواجهة فقط بلا أثر فعلي في
        // المنطق عند التبديل أثناء التشغيل).
        fun setAnalyzerMode(local: Boolean) {
            val changed = useLocalAi != local
            useLocalAi = local
            Log.d("Ghadhoo", "🔄 تم تغيير وضع المحلل إلى: ${if (local) "عادي (حجب كامل)" else "احترافي (حجب دقيق محلي عبر YOLO)"}")

            if (changed) {
                // ندفع التغيير فوراً بدل انتظار الإطار التالي فقط لضمان
                // اتساق فوري بين الإشعار وحالة المحللات مع القيمة الجديدة.
                instanceRef?.applyModeChangeNow()
            }
        }
    }

    private var mediaProjection: MediaProjection?   = null
    private var overlayManager: OverlayManager?     = null

    // مصنّف الأمان (nsfw.tflite):
    // - في الوضع العادي: يُستدعى مرة واحدة على الإطار كاملاً
    // - في الوضع الاحترافي: ✅ يُستدعى بشكل منفصل لكل صورة شخص مقصوصة
    //   يُرجعها yoloAnalyzer (تصنيف مستقل لكل شخص، وليس للإطار كاملاً)
    private var localGateAnalyzer: LocalAiAnalyzer? = null

    // ✅ يشغّل YOLOv11 محلياً على الجهاز لتحديد صندوق كل شخص في الإطار وقصّه
    // إلى صورة منفصلة (بلا أي تصنيف أمان — هذا دور localGateAnalyzer الآن).
    // يعمل فقط في الوضع الاحترافي؛ يبقى null في الوضع العادي.
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

        val notif = buildStatusNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else
            startForeground(1, notif)

        overlayManager    = OverlayManager(this)
        overlayManagerRef = overlayManager
        instanceRef       = this

        // ✅ تهيئة المحللات حسب الوضع المختار من الواجهة — نفس الدالة
        // المستخدمة عند تبديل الوضع أثناء التشغيل، لضمان أن منطق التهيئة
        // موحّد في مكان واحد فقط ولا يتكرر/يتباعد بين المسارين.
        ensureAnalyzersMatchMode()

        val projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projMgr.getMediaProjection(resultCode, resultData)

        if (isA12) setupVirtualDisplay12() else setupVirtualDisplay11()

        isMonitoring = true
        isCapturing  = true
        handler.post(captureRunnable)
        Log.d("Ghadhoo", if (useLocalAi) "✅ الخدمة تعمل — وضع عادي (LocalAiAnalyzer فقط)"
                          else "✅ الخدمة تعمل — وضع احترافي: كاشف دقيق مباشر (gohdooai.tflite) بلا بوابة")
    }

    // ── يبني إشعار الحالة الحالي بناءً على useLocalAi اللحظية ────────────
    // مُستخدمة عند بدء الخدمة وأيضاً عند تبديل الوضع أثناء التشغيل، حتى لا
    // يبقى نص الإشعار معبّراً عن الوضع القديم بعد التبديل.
    private fun buildStatusNotification(): android.app.Notification {
        val notifText = if (useLocalAi)
            "يتم تحليل الشاشة محلياً على جهازك."
        else
            "يتم تحليل الشاشة محلياً مع تحديد دقيق لموقع الحجب."

        return NotificationCompat.Builder(this, "safescreen_channel")
            .setContentTitle("غُضُّوا — الحماية نشطة")
            .setContentText(notifText)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ── تضمن أن الكائنات الفعلية (localGateAnalyzer / yoloAnalyzer) تطابق
    // قيمة useLocalAi الحالية. تُستدعى عند بدء المراقبة وأيضاً في كل
    // captureFrame() كحماية إضافية — بحيث حتى لو تغيّر الوضع في اللحظة
    // الفاصلة بين إطارين، يُعاد ضبط الكائنات فوراً بدل ترك yoloAnalyzer
    // فارغاً (null) أثناء العمل بالوضع الاحترافي، وهو ما كان يجعل التبديل
    // "يظهر" في المنطق (عبر قيمة useLocalAi) بلا أي أثر عملي فعلي، لأن
    // detectPersons() على كائن null يُرجع دائماً قائمة فارغة.
    private fun ensureAnalyzersMatchMode() {
        if (useLocalAi) {
            // الوضع العادي: بوابة محلية فقط على الإطار كامل، بلا YOLO
            if (localGateAnalyzer == null) {
                localGateAnalyzer = LocalAiAnalyzer(this)
            }
            if (yoloAnalyzer != null) {
                yoloAnalyzer?.close()
                yoloAnalyzer = null
                Log.d("Ghadhoo", "🔄 [Mode] تحرير YoloPersonAnalyzer بعد التحول للوضع العادي")
            }
        } else {
            // الوضع الاحترافي: yolo لتحديد الأشخاص + localGateAnalyzer
            // كطبقة تصنيف لكل شخص على حدة
            if (localGateAnalyzer == null) {
                localGateAnalyzer = LocalAiAnalyzer(this)
            }
            if (yoloAnalyzer == null) {
                yoloAnalyzer = YoloPersonAnalyzer(this)
                Log.d("Ghadhoo", "🔄 [Mode] تهيئة YoloPersonAnalyzer بعد التحول للوضع الاحترافي")
            }
        }
    }

    // ── يُستدعى من setAnalyzerMode (عبر instanceRef) فور تبديل الوضع أثناء
    // تشغيل الخدمة: يعيد ضبط المحللات فوراً بدل انتظار الإطار التالي، ويحدّث
    // نص الإشعار الظاهر للمستخدم ليعكس الوضع الجديد فعلياً.
    fun applyModeChangeNow() {
        if (!isMonitoring) return

        ensureAnalyzersMatchMode()

        // ✅ يمنع نتيجة "قديمة" من وضع سابق (مثلاً منطقة محجوبة من الوضع
        // الاحترافي) من الظهور أثناء الوضع الجديد قبل وصول أول تحليل جديد
        safeCount = 0
        overlayManager?.removeOverlay()

        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(1, buildStatusNotification())
            Log.d("Ghadhoo", "🔔 تم تحديث نص الإشعار بعد تبديل الوضع")
        } catch (e: Exception) {
            Log.e("Ghadhoo", "❌ فشل تحديث الإشعار بعد تبديل الوضع: ${e.message}")
        }
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
        // ✅ حماية إضافية: تضمن تطابق الكائنات مع useLocalAi اللحظية حتى لو
        // حصل التبديل في اللحظة الفاصلة قبل وصول applyModeChangeNow(). هذا
        // فحص رخيص (فحوصات null فقط) فلا يؤثر على الأداء.
        ensureAnalyzersMatchMode()

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
                        // ✅ الوضع الاحترافي — تسلسل من مرحلتين محليتين بالكامل:
                        //   1) yoloAnalyzer (YOLOv11) يكتشف كل شخص في الإطار
                        //      ويقص كل واحد إلى Bitmap منفصل + إحداثياته
                        //   2) كل صورة شخص تُرسل بشكل مستقل إلى localGateAnalyzer
                        //      (nsfw.tflite) — حكم آمن/غير آمن خاص بها هي وحدها،
                        //      لا علاقة له ببقية الأشخاص في نفس الإطار
                        //   3) فقط إحداثيات الأشخاص "غير الآمنين" تُجمع وتُرسل
                        //      للحجب — حجب دقيق بلا أي تغطية احتياطية كاملة
                        val personCrops = yoloAnalyzer?.detectPersons(bitmap) ?: emptyList()

                        if (personCrops.isEmpty()) {
                            // لا يوجد أي شخص في الإطار أصلاً — لا شيء لحجبه
                            applyAnalysisResult(AnalysisResult(isUnsafe = false, regions = null, reason = "no_person_detected"))
                        } else {
                            val unsafeRegions = ArrayList<RegionResult>()
                            try {
                                for (crop in personCrops) {
                                    val personResult = localGateAnalyzer?.analyze(crop.bitmap)
                                    if (personResult?.isUnsafe == true) {
                                        unsafeRegions.add(
                                            RegionResult(
                                                label = "UNSAFE_PERSON",
                                                bounds = crop.bounds,
                                                confidence = crop.confidence
                                            )
                                        )
                                    }
                                }
                            } finally {
                                // ✅ حاسم لتفادي تسريب الذاكرة: كل صورة شخص Bitmap
                                // منفصلة يجب تحريرها بعد الانتهاء من تصنيفها، بغض
                                // النظر عن نتيجتها
                                personCrops.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                            }

                            if (unsafeRegions.isNotEmpty()) {
                                Log.d("Ghadhoo", "🚨 ${unsafeRegions.size} من أصل ${personCrops.size} شخص غير آمن")
                                applyAnalysisResult(
                                    AnalysisResult(isUnsafe = true, regions = unsafeRegions, reason = "yolo_person_then_nsfw")
                                )
                            } else {
                                applyAnalysisResult(AnalysisResult(isUnsafe = false, regions = null, reason = "all_persons_safe"))
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
        instanceRef       = null

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