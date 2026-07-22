package com.ghadhoo_buler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════
 * YoloPersonAnalyzer  (الاسم أُبقي كما هو حتى لا نلمس ScreenMonitorService.kt
 * أو OverlayManager.kt — هذا الملف فقط هو ما تغيّر داخلياً)
 *
 * يستخدم الآن نموذج "gohdooai.tflite" (مبني على NudeNet 320n) بدل يولو
 * العام لتحديد الأشخاص. النموذج يكتشف مباشرة أجزاء الجسد المكشوفة
 * (عاري) بدل مجرد "شخص"، وهذا أدق لغرض التطبيق.
 *
 * ⚠️ ملاحظة مهمة يجب التحقق منها بنفسك قبل الاعتماد الكامل على الكود:
 * لا يمكنني تشغيل النموذج فعلياً هنا للتأكد من شكل المخرجات (output
 * tensor shape) بعد تحويله عبر onnx2tf، لأن هذا يعتمد على كيفية تصديره
 * تحديداً (channel-last / channel-first، القيم منسّبة 0..1 أو بوحدة
 * بكسل). الكود أدناه مكتوب ليتكيّف تلقائياً مع الحالتين الأكثر شيوعاً،
 * لكن ننصحك بمراقبة الـ Logcat (تاق "Ghadhoo") عند أول تشغيل فعلي على
 * جهاز حقيقي، وإخباري بالسطر الذي يطبع "📐 شكل مخرجات النموذج" حتى
 * أتأكد من صحة فك الترميز أو أعدّله معك إذا لزم.
 * ═══════════════════════════════════════════════════════════════════════
 */
class YoloPersonAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "Ghadhoo"

        private const val MODEL_FILE = "gohdooai.tflite"
        private const val INPUT_SIZE = 320          // 320n → مدخل 320x320
        private const val NUM_CLASSES = 18

        // درجة الثقة الدنيا لقبول أي اكتشاف
        // ✅ خُفِّضت من 0.30 إلى 0.18: بعد إزالة الحجب الاحتياطي الكامل من
        // ScreenMonitorService.kt، لم يعد هناك "شبكة أمان" لو فشل هذا
        // النموذج بتحديد صندوق — فيجب أن يكون أكثر تساهلاً في القبول حتى
        // لا يفوّت حالات حقيقية. النتيجة: صناديق أكثر (بثقة أقل نسبياً)
        // بدل عدم الحجب إطلاقاً.
        private const val CONF_THRESHOLD = 0.18f
        // عتبة NMS لدمج الصناديق المتداخلة لنفس الفئة
        private const val IOU_THRESHOLD = 0.45f

        // ✅ رُفعت من 0.55 إلى 0.95: كانت ترفض اكتشافات صحيحة فعلياً حين يملأ
        // المحتوى معظم الإطار (مؤكَّد من اللوج: عشرات الصناديق الصحيحة بعرض
        // ~90% كانت تُرفض ظلماً). الآن لا نرفض إلا القيم شبه المستحيلة فعلياً
        // (أكبر من 95% من الإطار).
        private const val MAX_BOX_FRACTION = 0.95f

        // ✅ رفعناها من 0.30 إلى 0.80: التوسيع بـ30% لم يكن كافياً حسب
        // ملاحظتك — النموذج أصلاً يكتشف "نقطة/ميزة" تشريحية دقيقة وضيقة
        // (مثل حلمة الثدي تحديداً) وليس كامل المنطقة المرئية المكشوفة، لذا
        // يحتاج تكبيراً أكبر بكثير ليغطي المنطقة الفعلية بالكامل. القيمة
        // 0.80 تعني: العرض والارتفاع النهائيان ≈ 1.8× الحجم الخام المكتشف.
        private const val BOX_EXPAND_RATIO = 0.80f

        // ✅ عدد صناديق YOLOv8 القياسي لمدخل 320×320 (3 مقاييس اكتشاف):
        // (320/8)² + (320/16)² + (320/32)² = 1600 + 400 + 100 = 2100
        // لا نعتمد على interp.getOutputTensor(0).shape() لأنه يرجع [1,1,1]
        // وهمياً قبل تشغيل الاستدلال الفعلي (تأكدنا من هذا عملياً على الجهاز:
        // الحجم الحقيقي كان 184800 بايت = 46200 قيمة = 2100×22 بالضبط).
        private const val NUM_BOXES = 2100
        private const val CHANNELS = 4 + NUM_CLASSES // 22
        private const val TOTAL_OUTPUT_FLOATS = NUM_BOXES * CHANNELS

        // ✅ ترتيب الفئات كما هو في نموذج NudeNet v3 (320n) الرسمي —
        // الترتيب هنا حساس جداً؛ لا تُغيّره إلا إذا تأكدت أن نسخة
        // النموذج لديك تستخدم ترتيباً مختلفاً.
        private val LABELS = arrayOf(
            "FEMALE_GENITALIA_COVERED", // 0
            "FACE_FEMALE",               // 1
            "BUTTOCKS_EXPOSED",          // 2
            "FEMALE_BREAST_EXPOSED",     // 3
            "FEMALE_GENITALIA_EXPOSED",  // 4
            "MALE_BREAST_EXPOSED",       // 5
            "ANUS_EXPOSED",              // 6
            "FEET_EXPOSED",              // 7
            "BELLY_COVERED",             // 8
            "FEET_COVERED",              // 9
            "ARMPITS_COVERED",           // 10
            "ARMPITS_EXPOSED",           // 11
            "FACE_MALE",                 // 12
            "BELLY_EXPOSED",             // 13
            "MALE_GENITALIA_EXPOSED",    // 14
            "ANUS_COVERED",              // 15
            "FEMALE_BREAST_COVERED",     // 16
            "BUTTOCKS_COVERED"           // 17
        )

        // ✅ الفئات "الصريحة" فقط التي نريد حجبها فعلاً — تجاهلنا عمداً
        // الوجوه (FACE_*) وكل ما هو "COVERED" (لأنه أصلاً مغطى ولا داعي
        // لحجبه)، وتجاهلنا أيضاً البطن/الإبط/القدم لأنها ليست عرياً
        // صريحاً. عدّل هذه المجموعة بحرية حسب مستوى الحساسية المطلوب.
        private val EXPOSED_LABELS = setOf(
            "FEMALE_GENITALIA_EXPOSED",
            "MALE_GENITALIA_EXPOSED",
            "FEMALE_BREAST_EXPOSED",
            "MALE_BREAST_EXPOSED",
            "BUTTOCKS_EXPOSED",
            "ANUS_EXPOSED"
        )

        // ✅ فئات "مغطاة" لكنها مواقع تشريحية حساسة (وليست وجه/قدم/إبط/بطن) —
        // نستخدم صناديقها كموقع تقريبي دقيق لمحتوى "مثير" (بيكيني/ملابس
        // كاشفة) حين تشتبه البوابة العامة (nsfw.tflite) بالمحتوى ككل لكن لا
        // يوجد عري صريح فعلي. هذا يعطينا دقة مكانية حتى بلا نموذج مخصص
        // لكشف "الإثارة".
        private val COVERED_LABELS_OF_INTEREST = setOf(
            "FEMALE_GENITALIA_COVERED",
            "FEMALE_BREAST_COVERED",
            "BUTTOCKS_COVERED",
            "ANUS_COVERED"
        )

        // ✅ يستخدمها ScreenMonitorService.kt لتصنيف كل RegionResult دون
        // الحاجة لنسخ قوائم الفئات هناك يدوياً
        fun isExposedLabel(label: String): Boolean = label in EXPOSED_LABELS
        fun isCoveredLabel(label: String): Boolean = label in COVERED_LABELS_OF_INTEREST
    }

    private var interpreter: Interpreter? = null
    private var loggedOutputShapeOnce = false
    private var loggedRawDumpOnce = false

    // ✅ حاسم لمنع تعطّل native (SIGSEGV): Interpreter في TensorFlow Lite
    // ليس آمناً للاستخدام المتزامن إطلاقاً. لو استدعاءان لـ detectPersons()
    // تداخلا زمنياً (حتى من نفس CoroutineScope لكن على خيوط مختلفة)، الوصول
    // المتزامن لنفس الـ Interpreter من خيطين يُتلف الذاكرة الأصلية ويُسقط
    // التطبيق فوراً. كل استخدام للـ interpreter الآن يمرّ عبر هذا القفل.
    private val interpreterLock = Any()

    // ✅ النموذج مُصدَّر بأبعاد ديناميكية (dynamic shape) لتنسور المدخل
    // "images"، لذلك يبدأ بحجم صغير جداً (12 بايت) حتى نستدعي resizeInput()
    // صراحةً. لا نعرف مسبقاً هل الترتيب NHWC أم NCHW، فنبدأ بافتراض NHWC
    // (الأشيع بعد onnx2tf)، ونبدّل تلقائياً لـ NCHW إذا فشل أول تشغيل فعلي.
    private enum class Layout { NHWC, NCHW }
    private var layout: Layout = Layout.NHWC

    init {
        try {
            val options = Interpreter.Options().apply { setNumThreads(4) }
            interpreter = Interpreter(loadModelFile(), options)
            val inShapeBefore = interpreter?.getInputTensor(0)?.shape()
            Log.d(TAG, "📥 شكل مدخل النموذج (قبل resize): ${inShapeBefore?.joinToString(",")}")
            applyInputShape(layout)
            Log.d(TAG, "✅ gohdooai.tflite تم تحميله بنجاح")
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تحميل gohdooai.tflite: ${e.message}")
            interpreter = null
        }
    }

    private fun applyInputShape(l: Layout) {
        val interp = interpreter ?: return
        val shape = if (l == Layout.NHWC)
            intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3)
        else
            intArrayOf(1, 3, INPUT_SIZE, INPUT_SIZE)
        interp.resizeInput(0, shape)
        interp.allocateTensors()
        Log.d(TAG, "📥 تم ضبط شكل المدخل إلى: ${shape.joinToString(",")} (${l.name})")
    }

    private fun loadModelFile(): ByteBuffer {
        val afd = context.assets.openFd(MODEL_FILE)
        val inputStream = afd.createInputStream()
        val channel = inputStream.channel
        val startOffset = afd.startOffset
        val declaredLength = afd.declaredLength
        return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * الدالة العامة التي يستدعيها ScreenMonitorService.kt
     * ترجع مناطق (0..1 نسبةً لأبعاد bitmap) لكل جزء عارٍ مكتشف.
     */
    fun detectPersons(bitmap: Bitmap): List<RegionResult> = synchronized(interpreterLock) {
        if (interpreter == null) return@synchronized emptyList()
        if (bitmap.width <= 0 || bitmap.height <= 0) return@synchronized emptyList()

        val (letterboxed, scale, padX, padY) = letterbox(bitmap)

        // نحاول أولاً بالتنسيق الحالي (layout)، وإن فشل بسبب عدم تطابق حجم
        // التنسور، نبدّل تلقائياً للتنسيق الآخر ونعيد المحاولة مرة واحدة فقط.
        val firstTry = runInference(letterboxed, scale, padX, padY, bitmap.width, bitmap.height, layout)
        if (firstTry != null) return@synchronized firstTry

        val otherLayout = if (layout == Layout.NHWC) Layout.NCHW else Layout.NHWC
        Log.w(TAG, "⚠️ فشل التنسيق ${layout.name} — تجربة ${otherLayout.name}")
        try {
            applyInputShape(otherLayout)
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تبديل شكل المدخل: ${e.message}")
            return@synchronized emptyList()
        }
        layout = otherLayout

        val secondTry = runInference(letterboxed, scale, padX, padY, bitmap.width, bitmap.height, layout)
        if (secondTry != null) return@synchronized secondTry

        Log.e(TAG, "❌ فشل التنسيقان NHWC وNCHW معاً — تحقق من النموذج نفسه")
        return@synchronized emptyList()
    }

    private fun runInference(
        letterboxed: Bitmap,
        scale: Float,
        padX: Float,
        padY: Float,
        origWidth: Int,
        origHeight: Int,
        l: Layout
    ): List<RegionResult>? {
        val interp = interpreter ?: return null
        return try {
            val inputBuffer = bitmapToInputBuffer(letterboxed, l)

            // ✅ حجم ثابت محسوب رياضياً (راجع التعليق عند تعريف الثوابت) —
            // لا نستعلم عن شكل تنسور المخرجات قبل التشغيل لأنه غير موثوق.
            val outputBuffer = ByteBuffer.allocateDirect(4 * TOTAL_OUTPUT_FLOATS)
                .order(ByteOrder.nativeOrder())

            interp.run(inputBuffer, outputBuffer)

            if (!loggedOutputShapeOnce) {
                try {
                    val realShape = interp.getOutputTensor(0).shape()
                    Log.d(TAG, "📐 شكل مخرجات النموذج (بعد التشغيل): ${realShape.joinToString(",")}")
                } catch (e: Exception) { /* تجاهل — معلومات تشخيصية فقط */ }
                loggedOutputShapeOnce = true
            }

            outputBuffer.rewind()
            val flat = FloatArray(TOTAL_OUTPUT_FLOATS)
            outputBuffer.asFloatBuffer().get(flat)

            if (!loggedRawDumpOnce) {
                dumpRawDiagnostics(flat)
                loggedRawDumpOnce = true
            }

            val detections = decodeAuto(flat, scale, padX, padY, origWidth, origHeight)
            val finalDetections = nonMaxSuppression(detections)

            finalDetections.map { det -> RegionResult(bounds = det.rect, label = det.label) }
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ أثناء تشغيل gohdooai.tflite (${l.name}): ${e.message}")
            null
        }
    }

    fun close() = synchronized(interpreterLock) {
        interpreter?.close()
        interpreter = null
    }

    // ═══════════════════════════════════════════════════════════════════
    // Letterbox: تحجيم مع الحفاظ على النسبة + حشو رمادي حتى 320x320
    // ═══════════════════════════════════════════════════════════════════
    private data class LetterboxResult(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private operator fun LetterboxResult.component1() = bitmap
    private operator fun LetterboxResult.component2() = scale
    private operator fun LetterboxResult.component3() = padX
    private operator fun LetterboxResult.component4() = padY

    private fun letterbox(src: Bitmap): LetterboxResult {
        val scale = min(
            INPUT_SIZE.toFloat() / src.width,
            INPUT_SIZE.toFloat() / src.height
        )
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        val padX = (INPUT_SIZE - newW) / 2f
        val padY = (INPUT_SIZE - newH) / 2f

        val scaled = Bitmap.createScaledBitmap(src, newW, newH, true)
        val out = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(114, 114, 114)) // حشو رمادي قياسي لنماذج YOLO
        canvas.drawBitmap(scaled, padX, padY, Paint(Paint.FILTER_BITMAP_FLAG))
        if (scaled !== src) scaled.recycle()

        return LetterboxResult(out, scale, padX, padY)
    }

    // ═══════════════════════════════════════════════════════════════════
    // تحويل الصورة المحضّرة إلى ByteBuffer مدخل للنموذج (float32, NHWC, 0..1)
    // ═══════════════════════════════════════════════════════════════════
    private fun bitmapToInputBuffer(bitmap: Bitmap, l: Layout): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        if (l == Layout.NHWC) {
            // [1, H, W, 3] — كل بكسل يليه مباشرة قيمه R,G,B
            for (p in pixels) {
                buffer.putFloat(((p shr 16) and 0xFF) / 255f) // R
                buffer.putFloat(((p shr 8) and 0xFF) / 255f)  // G
                buffer.putFloat((p and 0xFF) / 255f)           // B
            }
        } else {
            // [1, 3, H, W] — كل قناة كاملة على حدة (كل قيم R، ثم كل قيم G، ثم كل قيم B)
            for (p in pixels) buffer.putFloat(((p shr 16) and 0xFF) / 255f) // R plane
            for (p in pixels) buffer.putFloat(((p shr 8) and 0xFF) / 255f)  // G plane
            for (p in pixels) buffer.putFloat((p and 0xFF) / 255f)           // B plane
        }

        buffer.rewind()
        return buffer
    }

    // ═══════════════════════════════════════════════════════════════════
    // فك ترميز مخرجات النموذج (YOLOv8-style: [x_center, y_center, w, h, class_scores...])
    // ═══════════════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════════════
    // 🔬 تشخيص لمرة واحدة فقط: يطبع القيم الخام الحقيقية لعدة صناديق
    // (بترتيبين مختلفين) حتى نحدد الترتيب الصحيح الفعلي بيقين بدل التخمين.
    // احذف استدعاء هذه الدالة لاحقاً بعد حل المشكلة نهائياً (ليست جزءاً
    // من المنطق الدائم، فقط أداة تشخيص مؤقتة).
    // ═══════════════════════════════════════════════════════════════════
    private fun dumpRawDiagnostics(flat: FloatArray) {
        Log.d(TAG, "🔬 ═══ بدء تشخيص القيم الخام (مرة واحدة) ═══")
        Log.d(TAG, "🔬 إجمالي القيم: ${flat.size} (متوقع $TOTAL_OUTPUT_FLOATS)")

        // أول 10 قيم خام كما هي في الذاكرة (بدون أي تفسير)
        Log.d(TAG, "🔬 أول 10 قيم خام متتالية: ${flat.take(10).joinToString(", ") { "%.3f".format(it) }}")

        val sampleBoxIndices = listOf(0, 500, 1000, 1500, 2099)

        for (b in sampleBoxIndices) {
            // تفسير box-major: flat[b*22 + ch]
            val bmCx = flat.getOrNull(b * CHANNELS + 0) ?: -999f
            val bmCy = flat.getOrNull(b * CHANNELS + 1) ?: -999f
            val bmW  = flat.getOrNull(b * CHANNELS + 2) ?: -999f
            val bmH  = flat.getOrNull(b * CHANNELS + 3) ?: -999f
            val bmScoresSample = (0 until 4).map {
                flat.getOrNull(b * CHANNELS + 4 + it) ?: -999f
            }

            // تفسير channel-major: flat[ch*2100 + b]
            val cmCx = flat.getOrNull(0 * NUM_BOXES + b) ?: -999f
            val cmCy = flat.getOrNull(1 * NUM_BOXES + b) ?: -999f
            val cmW  = flat.getOrNull(2 * NUM_BOXES + b) ?: -999f
            val cmH  = flat.getOrNull(3 * NUM_BOXES + b) ?: -999f
            val cmScoresSample = (0 until 4).map {
                flat.getOrNull((4 + it) * NUM_BOXES + b) ?: -999f
            }

            Log.d(TAG, "🔬 صندوق#$b [box-major]  cx=%.2f cy=%.2f w=%.2f h=%.2f scores0-3=%s".format(
                bmCx, bmCy, bmW, bmH, bmScoresSample.joinToString(",") { "%.3f".format(it) }
            ))
            Log.d(TAG, "🔬 صندوق#$b [channel-major] cx=%.2f cy=%.2f w=%.2f h=%.2f scores0-3=%s".format(
                cmCx, cmCy, cmW, cmH, cmScoresSample.joinToString(",") { "%.3f".format(it) }
            ))
        }

        // إحصائية عامة: أعلى وأدنى قيمة في كامل المصفوفة (يساعد لمعرفة
        // إن كانت القيم مطبَّعة 0..1 أم بمقياس بكسل 0..320 أم شيء آخر تماماً)
        val minVal = flat.minOrNull() ?: 0f
        val maxVal = flat.maxOrNull() ?: 0f
        Log.d(TAG, "🔬 أصغر قيمة في كامل المصفوفة: $minVal — أكبر قيمة: $maxVal")
        Log.d(TAG, "🔬 ═══ نهاية التشخيص ═══")
    }

    private data class RawDetection(val rect: RectF, val label: String, val score: Float)

    // ✅ نجرّب تفسيرين مختلفين لنفس مصفوفة المخرجات (بدون إعادة تشغيل
    // الاستدلال — فقط قراءة مختلفة للفهرسة) ونختار التفسير الذي يعطي
    // اكتشافات فعلية. لا نعرف مسبقاً أي ترتيب استخدمه onnx2tf فعلياً.
    private fun decodeAuto(
        flat: FloatArray,
        scale: Float,
        padX: Float,
        padY: Float,
        origWidth: Int,
        origHeight: Int
    ): List<RawDetection> {
        // ✅ نبدأ بـ channel-major لأن الأدلة العملية (صناديق ضخمة عشوائية
        // بثقة زائفة، left=0 دائماً) أثبتت أن box-major كان يقرأ بيانات
        // ملوَّثة من قناة مختلفة. مع فحص سلامة الثقة (bestScore > 1.05
        // يُرفض)، أصبح بإمكاننا الاعتماد على أي ترتيب يعطي نتائج صحيحة أولاً.
        val channelMajorResult = decode(flat, boxMajor = false, scale, padX, padY, origWidth, origHeight)
        if (channelMajorResult.isNotEmpty()) {
            Log.d(TAG, "✅ فُك الترميز بنجاح بترتيب channel-major (${channelMajorResult.size} اكتشاف)")
            return channelMajorResult
        }

        val boxMajorResult = decode(flat, boxMajor = true, scale, padX, padY, origWidth, origHeight)
        if (boxMajorResult.isNotEmpty()) {
            Log.d(TAG, "✅ فُك الترميز بنجاح بترتيب box-major (${boxMajorResult.size} اكتشاف)")
            return boxMajorResult
        }

        return emptyList()
    }

    private fun decode(
        flat: FloatArray,
        boxMajor: Boolean,
        scale: Float,
        padX: Float,
        padY: Float,
        origWidth: Int,
        origHeight: Int
    ): List<RawDetection> {
        fun valueAt(boxIdx: Int, chIdx: Int): Float {
            return if (boxMajor) {
                flat[boxIdx * CHANNELS + chIdx]
            } else {
                flat[chIdx * NUM_BOXES + boxIdx]
            }
        }

        // نتحقق إن كانت القيم منسّبة (0..~1.5) أو بوحدة بكسل (0..320)
        var sampleMax = 0f
        val sampleCount = min(NUM_BOXES, 50)
        for (i in 0 until sampleCount) {
            sampleMax = max(sampleMax, valueAt(i, 0))
            sampleMax = max(sampleMax, valueAt(i, 1))
        }
        val isNormalized = sampleMax in 0f..1.5f

        val results = ArrayList<RawDetection>()
        var rejectedCount = 0
        var rejectedMaxW = 0f
        var rejectedMaxH = 0f
        var rejectedLabel = ""

        for (b in 0 until NUM_BOXES) {
            var bestScore = -1f
            var bestClass = -1
            for (c in 0 until NUM_CLASSES) {
                val s = valueAt(b, 4 + c)
                if (s > bestScore) { bestScore = s; bestClass = c }
            }
            if (bestScore < CONF_THRESHOLD || bestClass < 0) continue

            // ✅ فحص سلامة إضافي حاسم: درجة الثقة الحقيقية (بعد sigmoid) لا
            // يمكن رياضياً أن تتجاوز 1.0. لو تجاوزتها، فهذا يعني أن الفهرسة
            // (box-major/channel-major) خاطئة، وأننا قرأنا خطأً قيمة إحداثية
            // ضخمة (مثل 200 بكسل) بدل درجة ثقة حقيقية. نرفض هذا الترتيب بالكامل
            // بدل قبول اكتشافات وهمية عشوائية بثقة "عالية" زائفة.
            if (bestScore > 1.05f) continue

            val label = LABELS[bestClass]
            if (label !in EXPOSED_LABELS && label !in COVERED_LABELS_OF_INTEREST) continue

            var cx = valueAt(b, 0)
            var cy = valueAt(b, 1)
            var w = valueAt(b, 2)
            var h = valueAt(b, 3)

            if (isNormalized) {
                cx *= INPUT_SIZE; cy *= INPUT_SIZE; w *= INPUT_SIZE; h *= INPUT_SIZE
            }

            val origCx = (cx - padX) / scale
            val origCy = (cy - padY) / scale
            var origW = w / scale
            var origH = h / scale

            // ✅ توسيع الصندوق نسبياً حول مركزه (وليس بمقدار ثابت) — الصندوق
            // الخام من النموذج غالباً ضيّق جداً حول الجزء المكتشف بالضبط ولا
            // يغطيه بالكامل حتى حوافه. BOX_EXPAND_RATIO يكبّر العرض والارتفاع
            // بنسبة 30% (15% لكل جهة) قبل الرسم، فيضمن تغطية أوفر.
            origW *= (1f + BOX_EXPAND_RATIO)
            origH *= (1f + BOX_EXPAND_RATIO)

            val left = (origCx - origW / 2f)
            val top = (origCy - origH / 2f)
            val right = (origCx + origW / 2f)
            val bottom = (origCy + origH / 2f)

            val normRect = RectF(
                (left / origWidth).coerceIn(0f, 1f),
                (top / origHeight).coerceIn(0f, 1f),
                (right / origWidth).coerceIn(0f, 1f),
                (bottom / origHeight).coerceIn(0f, 1f)
            )

            if (normRect.width() <= 0f || normRect.height() <= 0f) continue

            // ✅ رفض الصناديق الضخمة بشكل غير منطقي فعلياً (نادر جداً الآن
            // بعد رفع السقف لـ 0.95) — نجمّع العدّاد بدل طباعة سطر منفصل
            // لكل صندوق مرفوض (كان يُفيض الـ Logcat ويُسبب تجمّد الواجهة).
            if (normRect.width() > MAX_BOX_FRACTION || normRect.height() > MAX_BOX_FRACTION) {
                rejectedCount++
                rejectedMaxW = max(rejectedMaxW, normRect.width())
                rejectedMaxH = max(rejectedMaxH, normRect.height())
                rejectedLabel = label
                continue
            }

            results.add(RawDetection(normRect, label, bestScore))
        }

        if (rejectedCount > 0) {
            Log.w(TAG, "⚠️ رُفض $rejectedCount صندوق ضخم غير منطقي (أكبر قيمة: w=$rejectedMaxW h=$rejectedMaxH label=$rejectedLabel)")
        }

        return results
    }

    // ═══════════════════════════════════════════════════════════════════
    // Non-Max Suppression بسيط لكل فئة على حدة
    // ═══════════════════════════════════════════════════════════════════
    private fun nonMaxSuppression(detections: List<RawDetection>): List<RawDetection> {
        val grouped = detections.groupBy { it.label }
        val kept = ArrayList<RawDetection>()

        for ((_, group) in grouped) {
            val sorted = group.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept.add(best)
                sorted.removeAll { iou(best.rect, it.rect) > IOU_THRESHOLD }
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val interArea = interW * interH

        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
        val union = areaA + areaB - interArea

        return if (union <= 0f) 0f else interArea / union
    }
}