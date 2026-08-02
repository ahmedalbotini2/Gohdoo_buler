package com.ghadhoo_buler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

class YoloPersonAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "GhadhooAI"
        private const val MODEL_FILE = "yolov11_person.tflite"

        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 80
        private const val PERSON_CLASS_ID = 0 // "person" في ترتيب COCO القياسي
        private const val CHANNELS = 4 + NUM_CLASSES // 84

        // ✅ عدد الصناديق القياسي لمخرجات YOLOv8/v11 بمدخل 640×640 (3 مقاييس
        // اكتشاف): (640/8)² + (640/16)² + (640/32)² = 6400+1600+400 = 8400
        // نستخدمه فقط كقيمة احتياطية إذا تعذّر حساب العدد من حجم المخرجات
        // الفعلي مباشرة (output.size / CHANNELS)
        private const val EXPECTED_NUM_BOXES = 8400

        private const val CONF_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.45f

        // هامش بسيط حول كل صندوق شخص قبل القص، حتى لا يُقطع جزء من الجسم
        // عند حافة الصندوق (تحديداً الرأس/القدمين)
        private const val CROP_MARGIN_RATIO = 0.08f
    }

    /*
     *  المستدعي (ScreenMonitorService) مسؤول عن استدعاء bitmap.recycle()
     * بعد الانتهاء من تصنيف كل قصّة، لتفادي تسريب الذاكرة.
     */
    data class PersonCrop(val bitmap: Bitmap, val bounds: RectF, val confidence: Float)

    var isReady = false
        private set

    var compiledModel: CompiledModel? = null

    private var loggedOutputShapeOnce = false

    init {
        try {
            val modelPath = getModelFilePath(context, MODEL_FILE)
            if (modelPath != null) {
                Log.d(TAG, "✅ [YOLO] ملف الموديل موجود في: $modelPath")
                compiledModel = CompiledModel.create(
                    modelPath, CompiledModel.Options(Accelerator.CPU)
                )
                isReady = true
                Log.d(TAG, "✅ [YOLO] CompiledModel تم تهيئته بنجاح")
            } else {
                Log.e(TAG, "❌ [YOLO] الموديل غير موجود في assets!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [YOLO] خطأ في تهيئة CompiledModel: ${e.message}")
        }
    }

    /**
     * الدالة العامة التي يستدعيها ScreenMonitorService.kt.
     * تكتشف كل الأشخاص في الصورة عبر YOLOv11 وتقص كل واحد إلى Bitmap
     * منفصل + إحداثياته. لا يوجد أي تصنيف أمان هنا.
     */
    suspend fun detectPersons(bitmap: Bitmap): List<PersonCrop> = withContext(Dispatchers.Default) {
        if (!isReady || compiledModel == null) return@withContext emptyList<PersonCrop>()
        if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext emptyList<PersonCrop>()

        var scaled: Bitmap? = null
        return@withContext try {
            scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val rawBoxes = runInference(scaled)
            val personBoxes = nonMaxSuppression(rawBoxes, IOU_THRESHOLD)

            Log.d(TAG, "🧍 [YOLO] تم اكتشاف ${personBoxes.size} شخص/أشخاص")

            val crops = ArrayList<PersonCrop>()
            for (box in personBoxes) {
                val left = (box.cx - box.w / 2f).coerceIn(0f, 1f)
                val top = (box.cy - box.h / 2f).coerceIn(0f, 1f)
                val right = (box.cx + box.w / 2f).coerceIn(0f, 1f)
                val bottom = (box.cy + box.h / 2f).coerceIn(0f, 1f)
                if (right <= left || bottom <= top) continue

                val normRect = RectF(left, top, right, bottom)
                val cropped = cropPerson(bitmap, normRect) ?: continue
                crops.add(PersonCrop(cropped, normRect, box.conf))
            }
            crops
        } catch (e: Exception) {
            Log.e(TAG, "❌ [YOLO] خطأ أثناء التحليل: ${e.message}")
            emptyList<PersonCrop>()
        } finally {
            scaled?.recycle()
        }
    }

    fun close() {
        compiledModel = null
        isReady = false
    }

    // ── تمثيل داخلي لصندوق خام قبل NMS (إحداثيات مُطبَّعة 0..1) ──────────
    private data class RawBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val conf: Float)

    private fun runInference(bitmap: Bitmap): List<RawBox> {
        val model = compiledModel!!
        val inputBuffers = model.createInputBuffers()
        val outputBuffers = model.createOutputBuffers()

        // ✅ شكل مدخل النموذج المؤكَّد سابقاً من اللوج: [1,3,640,640] (NCHW)
        // — نكتب البيانات بترتيب مستوٍ لكل قناة (كل قيم R كاملة، ثم كل قيم
        // G، ثم كل قيم B) بدل تشابك RGB لكل بكسل (NHWC)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val floatValues = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
        var idx = 0
        for (p in pixels) floatValues[idx++] = ((p shr 16) and 0xFF) / 255f // R plane
        for (p in pixels) floatValues[idx++] = ((p shr 8) and 0xFF) / 255f  // G plane
        for (p in pixels) floatValues[idx++] = (p and 0xFF) / 255f          // B plane

        inputBuffers[0].writeFloat(floatValues)
        model.run(inputBuffers, outputBuffers)

        val output = outputBuffers[0].readFloat()
        val numBoxes = if (output.size % CHANNELS == 0 && output.size / CHANNELS > 0) {
            output.size / CHANNELS
        } else {
            EXPECTED_NUM_BOXES
        }

        if (!loggedOutputShapeOnce) {
            Log.d(TAG, "📐 [YOLO] حجم مخرجات النموذج: ${output.size} → numBoxes=$numBoxes channels=$CHANNELS")
            loggedOutputShapeOnce = true
        }

        // ✅ نفترض channel-major أولاً (نفس افتراض الفرع القديم المؤكَّد
        // عمله: output[channel * numBoxes + box]). لو ما طلعت أي اكتشافات،
        // نجرّب box-major كتفسير بديل على نفس المصفوفة (بلا إعادة تشغيل).
        val channelMajor = decode(output, boxMajor = false, numBoxes)
        if (channelMajor.isNotEmpty()) return channelMajor

        val boxMajor = decode(output, boxMajor = true, numBoxes)
        if (boxMajor.isNotEmpty()) {
            Log.d(TAG, "✅ [YOLO] فُك الترميز بترتيب box-major بدل channel-major")
        }
        return boxMajor
    }

    private fun decode(output: FloatArray, boxMajor: Boolean, numBoxes: Int): List<RawBox> {
        fun valueAt(boxIdx: Int, chIdx: Int): Float {
            val i = if (boxMajor) boxIdx * CHANNELS + chIdx else chIdx * numBoxes + boxIdx
            return if (i in output.indices) output[i] else 0f
        }

        // نتحقق إن كانت إحداثيات المركز/الأبعاد مُطبَّعة أصلاً (0..~1.5) أو
        // بمقياس بكسل (0..640) — الفرع القديم افترض بكسل دائماً، لكن نتحقق
        // بدل الافتراض الأعمى تحسباً لاختلاف التصدير
        var sampleMax = 0f
        for (i in 0 until min(numBoxes, 50)) {
            sampleMax = max(sampleMax, valueAt(i, 0))
            sampleMax = max(sampleMax, valueAt(i, 1))
        }
        val isNormalized = sampleMax in 0f..1.5f

        val boxes = mutableListOf<RawBox>()
        for (i in 0 until numBoxes) {
            val personScore = valueAt(i, 4 + PERSON_CLASS_ID)
            // فحص سلامة: الثقة الحقيقية لا يمكن أن تتجاوز 1.0 — تجاوزها يعني
            // أن ترتيب الفهرسة خاطئ، فنرفض هذا الاكتشاف
            if (personScore < CONF_THRESHOLD || personScore > 1.05f) continue

            var cx = valueAt(i, 0)
            var cy = valueAt(i, 1)
            var w = valueAt(i, 2)
            var h = valueAt(i, 3)

            if (!isNormalized) {
                cx /= INPUT_SIZE; cy /= INPUT_SIZE; w /= INPUT_SIZE; h /= INPUT_SIZE
            }

            boxes.add(RawBox(cx = cx, cy = cy, w = w, h = h, conf = personScore))
        }
        return boxes
    }

    // ── Non-Max Suppression لدمج الصناديق المتداخلة على نفس الشخص ───────
    private fun nonMaxSuppression(boxes: List<RawBox>, iouThresh: Float): List<RawBox> {
        val sorted = boxes.sortedByDescending { it.conf }.toMutableList()
        val result = mutableListOf<RawBox>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best, it) > iouThresh }
        }
        return result
    }

    private fun iou(a: RawBox, b: RawBox): Float {
        val ax1 = a.cx - a.w / 2f; val ay1 = a.cy - a.h / 2f
        val ax2 = a.cx + a.w / 2f; val ay2 = a.cy + a.h / 2f
        val bx1 = b.cx - b.w / 2f; val by1 = b.cy - b.h / 2f
        val bx2 = b.cx + b.w / 2f; val by2 = b.cy + b.h / 2f

        val interX1 = max(ax1, bx1); val interY1 = max(ay1, by1)
        val interX2 = min(ax2, bx2); val interY2 = min(ay2, by2)
        val interW = max(0f, interX2 - interX1)
        val interH = max(0f, interY2 - interY1)
        val interArea = interW * interH

        val areaA = max(0f, a.w) * max(0f, a.h)
        val areaB = max(0f, b.w) * max(0f, b.h)
        val unionArea = areaA + areaB - interArea

        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    // ── القص: يستخرج صورة شخص واحد من الصورة الأصلية بناءً على صندوقه ───
    private fun cropPerson(src: Bitmap, normRect: RectF): Bitmap? {
        val marginW = normRect.width() * CROP_MARGIN_RATIO
        val marginH = normRect.height() * CROP_MARGIN_RATIO

        val left = ((normRect.left - marginW) * src.width).toInt().coerceIn(0, src.width - 1)
        val top = ((normRect.top - marginH) * src.height).toInt().coerceIn(0, src.height - 1)
        val right = ((normRect.right + marginW) * src.width).toInt().coerceIn(left + 1, src.width)
        val bottom = ((normRect.bottom + marginH) * src.height).toInt().coerceIn(top + 1, src.height)

        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null

        return try {
            Bitmap.createBitmap(src, left, top, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "❌ [YOLO] فشل قص صورة الشخص: ${e.message}")
            null
        }
    }

    // ── نسخ الموديل من assets إلى cacheDir (نفس أسلوب LocalAiAnalyzer) ──
    private fun getModelFilePath(context: Context, modelName: String): String? {
        val file = File(context.cacheDir, modelName)
        if (file.exists()) {
            Log.d(TAG, "📁 [YOLO] الموديل موجود في الكاش: ${file.absolutePath}")
            return file.absolutePath
        }
        return try {
            Log.d(TAG, "📁 [YOLO] نسخ الموديل من assets...")
            context.assets.open(modelName).use { input ->
                FileOutputStream(file).use { output ->
                    val buf = ByteArray(4 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) output.write(buf, 0, n)
                }
            }
            Log.d(TAG, "✅ [YOLO] تم نسخ الموديل: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "❌ [YOLO] فشل نسخ الموديل: ${e.message}")
            null
        }
    }
}