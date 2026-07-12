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

/**
 * YoloPersonAnalyzer
 * ─────────────────────────────────────────────────────────────────────────
 * محلل مستقل يشغّل نموذج YOLO11n العام (COCO، 80 فئة) محلياً على الجهاز.
 * يُستخدم فقط بعد أن يصنّف LocalAiAnalyzer (nsfw.tflite) الصورة كـ"غير آمنة".
 *
 * الهدف: إرجاع صناديق (regions) حول فئة "person" فقط، لتُستخدم في
 * OverlayManager لحجب دقيق بدل تغطية الشاشة كاملة.
 *
 * ⚠️ ملاحظة مهمة: هذا نموذج كشف أجسام عام (person/car/dog/...)، وليس
 * نموذجاً مصنَّفاً لكشف محتوى حساس. يكتشف "شخص" ككيان كامل فقط،
 * بصرف النظر عن أي تفاصيل بصرية أخرى.
 *
 * يتبع نفس بنية LocalAiAnalyzer.kt (تحميل من assets عبر cacheDir،
 * نفس أسلوب الـ logging، ونفس واجهة CompiledModel من LiteRT).
 */
class YoloPersonAnalyzer(private val context: Context) {

    var isReady = false
        private set

    var compiledModel: CompiledModel? = null

    // مقاس الإدخال القياسي لتصدير YOLO11n إلى tflite/LiteRT
    private val inputWidth  = 640
    private val inputHeight = 640

    // إعدادات الكشف
    private val confThreshold = 0.35f   // أدنى ثقة نقبلها لصندوق
    private val iouThreshold  = 0.45f   // عتبة NMS (لدمج الصناديق المتداخلة)
    private val personClassId = 0       // "person" هي الفئة رقم 0 في ترتيب COCO القياسي

    init {
        try {
            val modelPath = getModelFilePath(context, "yolo11n.tflite")
            if (modelPath != null) {
                Log.d("GhadhooAI", "✅ [YOLO] ملف الموديل موجود في: $modelPath")
                compiledModel = CompiledModel.create(
                    modelPath, CompiledModel.Options(Accelerator.CPU)
                )
                isReady = true
                Log.d("GhadhooAI", "✅ [YOLO] CompiledModel تم تهيئته بنجاح")
            } else {
                Log.e("GhadhooAI", "❌ [YOLO] الموديل غير موجود في assets!")
            }
        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ [YOLO] خطأ في تهيئة CompiledModel: ${e.message}")
        }
    }

    /**
     * يحلل bitmap ويرجع قائمة مناطق "شخص" مكتشفة، بالنسب المئوية
     * (0.0 - 1.0) بالنسبة لأبعاد الصورة الأصلية — بنفس تنسيق RegionResult
     * (bounds: RectF) المُعرَّف في ملف AnalysisResult/ContentAnalyzer.
     *
     * لا يُستدعى هذا إلا بعد أن يقرر LocalAiAnalyzer أن الصورة غير آمنة.
     */
    suspend fun detectPersons(bitmap: Bitmap): List<RegionResult> = withContext(Dispatchers.Default) {
        if (!isReady || compiledModel == null) return@withContext emptyList<RegionResult>()

        var scaled: Bitmap? = null
        return@withContext try {
            scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val rawBoxes = runInference(scaled)
            val personBoxes = rawBoxes.filter { it.classId == personClassId }
            val finalBoxes = nonMaxSuppression(personBoxes, iouThreshold)

            Log.d("GhadhooAI", "🧍 [YOLO] تم اكتشاف ${finalBoxes.size} شخص/أشخاص")

            finalBoxes.map { box ->
                val left   = (box.cx - box.w / 2f).coerceIn(0f, 1f)
                val top    = (box.cy - box.h / 2f).coerceIn(0f, 1f)
                val right  = (box.cx + box.w / 2f).coerceIn(0f, 1f)
                val bottom = (box.cy + box.h / 2f).coerceIn(0f, 1f)
                RegionResult(
                    label = "person",
                    bounds = RectF(left, top, right, bottom),
                    confidence = box.conf
                )
            }
        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ [YOLO] خطأ أثناء التحليل: ${e.message}")
            emptyList<RegionResult>()
        } finally {
            scaled?.recycle()
        }
    }

    // ── تمثيل داخلي لصندوق خام قبل NMS ───────────────────────────────────
    private data class RawBox(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val conf: Float, val classId: Int
    )

    private fun runInference(bitmap: Bitmap): List<RawBox> {
        val inputBuffers  = compiledModel!!.createInputBuffers()
        val outputBuffers = compiledModel!!.createOutputBuffers()

        // ── تحضير الإدخال: [1, 640, 640, 3] بصيغة RGB مُطبَّعة 0-1 ──────────
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val floatValues = FloatArray(inputWidth * inputHeight * 3)
        var idx = 0
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF).toFloat() / 255.0f
            val g = ((pixel shr 8)  and 0xFF).toFloat() / 255.0f
            val b = (pixel and 0xFF).toFloat() / 255.0f
            floatValues[idx++] = r
            floatValues[idx++] = g
            floatValues[idx++] = b
        }
        inputBuffers[0].writeFloat(floatValues)
        compiledModel!!.run(inputBuffers, outputBuffers)

        // ── قراءة الإخراج: [1, 84, 8400] (4 إحداثيات + 80 نتيجة فئة) ────────
        val output = outputBuffers[0].readFloat()
        val numClasses = 80
        val numAttrs = 4 + numClasses
        val numPredictions = output.size / numAttrs

        val boxes = mutableListOf<RawBox>()
        for (i in 0 until numPredictions) {
            // التخطيط channel-first: كل خاصية مخزّنة كمصفوفة متتالية بطول numPredictions
            val cx = output[0 * numPredictions + i]
            val cy = output[1 * numPredictions + i]
            val w  = output[2 * numPredictions + i]
            val h  = output[3 * numPredictions + i]

            var bestClassId = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val score = output[(4 + c) * numPredictions + i]
                if (score > bestScore) {
                    bestScore = score
                    bestClassId = c
                }
            }

            if (bestScore >= confThreshold && bestClassId == personClassId) {
                boxes.add(
                    RawBox(
                        cx = cx / inputWidth,
                        cy = cy / inputHeight,
                        w = w / inputWidth,
                        h = h / inputHeight,
                        conf = bestScore,
                        classId = bestClassId
                    )
                )
            }
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

        val areaA = a.w * a.h
        val areaB = b.w * b.h
        val unionArea = areaA + areaB - interArea

        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    // ── نسخ الموديل من assets إلى cacheDir (نفس أسلوب LocalAiAnalyzer) ──
    private fun getModelFilePath(context: Context, modelName: String): String? {
        val file = File(context.cacheDir, modelName)
        if (file.exists()) {
            Log.d("GhadhooAI", "📁 [YOLO] الموديل موجود في الكاش: ${file.absolutePath}")
            return file.absolutePath
        }
        return try {
            Log.d("GhadhooAI", "📁 [YOLO] نسخ الموديل من assets...")
            context.assets.open(modelName).use { input ->
                FileOutputStream(file).use { output ->
                    val buf = ByteArray(4 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) output.write(buf, 0, n)
                }
            }
            Log.d("GhadhooAI", "✅ [YOLO] تم نسخ الموديل: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ [YOLO] فشل نسخ الموديل: ${e.message}")
            null
        }
    }

    fun close() {
        compiledModel = null
        isReady = false
    }
}