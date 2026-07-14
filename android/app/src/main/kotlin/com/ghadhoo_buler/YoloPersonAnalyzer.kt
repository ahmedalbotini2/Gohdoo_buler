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

        var letterboxed: Bitmap? = null
        return@withContext try {
            val lb = letterboxResize(bitmap)
            letterboxed = lb.bitmap
            val rawBoxes = runInference(lb.bitmap)
            val personBoxes = rawBoxes.filter { it.classId == personClassId }
            val finalBoxes = nonMaxSuppression(personBoxes, iouThreshold)

            Log.d("GhadhooAI", "🧍 [YOLO] تم اكتشاف ${finalBoxes.size} شخص/أشخاص")

            finalBoxes.map { box ->
                // ✅ إحداثيات الصندوق قادمة نسبةً لصورة letterbox المربعة
                // (640×640 بحشو رمادي) — لازم نحوّلها أولاً لنسب الصورة
                // الأصلية (قبل الحشو والتصغير) عبر عكس عملية letterbox
                val (left, top, right, bottom) = lb.unletterbox(
                    box.cx - box.w / 2f, box.cy - box.h / 2f,
                    box.cx + box.w / 2f, box.cy + box.h / 2f
                )
                RegionResult(
                    label = "person",
                    bounds = RectF(
                        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
                        right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f)
                    ),
                    confidence = box.conf
                )
            }
        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ [YOLO] خطأ أثناء التحليل: ${e.message}")
            emptyList<RegionResult>()
        } finally {
            letterboxed?.recycle()
        }
    }

    // ── Letterbox: يحافظ على تناسق الأبعاد الأصلي ثم يحشو بلون رمادي
    // (114,114,114 — نفس لون الحشو المستخدم أثناء تدريب Ultralytics) ليصبح
    // الناتج مربعاً inputWidth×inputHeight بدون أي تشويه للصورة. هذا يطابق
    // بالضبط أسلوب المعالجة المسبقة الذي تدرّب عليه النموذج، على عكس الضغط
    // المباشر (squish) الذي يشوّه الأشخاص القريبين من الحواف ويربك التموضع.
    private fun letterboxResize(src: Bitmap): LetterboxData {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        val scale = min(inputWidth / srcW, inputHeight / srcH)
        val newW = (srcW * scale).toInt().coerceAtLeast(1)
        val newH = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (inputWidth - newW) / 2f
        val padY = (inputHeight - newH) / 2f

        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)
        val canvasBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(canvasBitmap)
        canvas.drawColor(android.graphics.Color.rgb(114, 114, 114)) // لون الحشو القياسي في Ultralytics
        canvas.drawBitmap(resized, padX, padY, null)
        if (resized !== src) resized.recycle()

        return LetterboxData(canvasBitmap, scale, padX, padY)
    }

    private class LetterboxData(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    ) {
        // يحوّل مستطيلاً مُطبَّعاً (0..1) نسبةً لصورة letterbox 640×640،
        // إلى مستطيل مُطبَّع (0..1) نسبةً للصورة الأصلية قبل الحشو والتصغير
        fun unletterbox(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
            val inputW = bitmap.width.toFloat()
            val inputH = bitmap.height.toFloat()
            // بُعد الصورة الأصلية (قبل الحشو والتصغير) بوحدات بكسل
            val realOrigW = (inputW - 2 * padX) / scale
            val realOrigH = (inputH - 2 * padY) / scale

            fun mapX(nx: Float): Float = ((nx * inputW) - padX) / scale / realOrigW
            fun mapY(ny: Float): Float = ((ny * inputH) - padY) / scale / realOrigH

            return floatArrayOf(mapX(left), mapY(top), mapX(right), mapY(bottom))
        }
    }


    private data class RawBox(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val conf: Float, val classId: Int
    )

    private fun runInference(bitmap: Bitmap): List<RawBox> {
        val inputBuffers  = compiledModel!!.createInputBuffers()
        val outputBuffers = compiledModel!!.createOutputBuffers()

        // ── تحضير الإدخال: [1, 3, 640, 640] بصيغة NCHW (planar) ─────────────
        // ⚠️ مهم: التصدير الجديد لـ Ultralytics (LiteRT w8a32) يستخدم NCHW
        // (كل قناة لون منفصلة بالكامل: كل قيم R ثم كل قيم G ثم كل قيم B)
        // وليس NHWC القديم (R,G,B متتالية لكل بكسل). كتابة الإدخال بالترتيب
        // الخاطئ تُنتج بيانات بلا معنى بصرياً ويفشل النموذج في اكتشاف أي شيء
        // رغم أن الاستدلال يعمل بدون أخطاء ظاهرة.
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val channelSize = inputWidth * inputHeight
        val floatValues = FloatArray(channelSize * 3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF).toFloat() / 255.0f
            val g = ((pixel shr 8)  and 0xFF).toFloat() / 255.0f
            val b = (pixel and 0xFF).toFloat() / 255.0f
            floatValues[i]                   = r  // قناة R كاملة أولاً
            floatValues[channelSize + i]     = g  // ثم قناة G كاملة
            floatValues[channelSize * 2 + i] = b  // ثم قناة B كاملة
        }
        inputBuffers[0].writeFloat(floatValues)
        compiledModel!!.run(inputBuffers, outputBuffers)

        // ── قراءة الإخراج: [1, 84, 8400] (4 إحداثيات + 80 نتيجة فئة) ────────
        val output = outputBuffers[0].readFloat()
        val numClasses = 80
        val numAttrs = 4 + numClasses
        val numPredictions = output.size / numAttrs

        // ✅ تصحيح جوهري: الشكل الأصلي عند التصدير من PyTorch هو [1, 84, 8400]
        // (سمة أولاً)، لكن أدوات التحويل PyTorch→TensorFlow المستخدمة داخلياً
        // في تصدير LiteRT تُبدّل الترتيب عادة إلى [1, 8400, 84] (صندوق أولاً /
        // channels-last) — وهو المعيار المفضّل في TensorFlow. القراءة بالترتيب
        // الخاطئ (سمة أولاً) كانت "تخلط" قيم من صناديق مختلفة عشوائياً، فتنتج
        // ثقات تبدو معقولة صدفة لكن إحداثيات مواقع قريبة من الصفر دائماً —
        // بالضبط النمط اللي شفناه (صندوق 17×18px بزاوية 0,0 في كل مرة).
        // الآن: كل صندوق i له numAttrs (84) قيمة متتالية بدءاً من i*numAttrs.
        var maxCxSeen = 0f
        for (i in 0 until numPredictions) {
            val cx = output[i * numAttrs + 0]
            if (cx > maxCxSeen) maxCxSeen = cx
        }
        val coordsArePixelSpace = maxCxSeen > 1.5f
        if (coordsArePixelSpace) {
            Log.d("GhadhooAI", "🔬 [YOLO] إحداثيات بوحدات بكسل (max cx=$maxCxSeen) — سيتم التطبيع بالقسمة على $inputWidth")
        } else {
            Log.d("GhadhooAI", "🔬 [YOLO] إحداثيات مُطبَّعة أصلاً (max cx=$maxCxSeen)")
        }

        val boxes = mutableListOf<RawBox>()
        var bestPersonScoreSeen = 0f

        // ✅ تشخيص دقيق: نحتفظ بالقيم الخام غير المُعالَجة (قبل أي حساب
        // left/top/w/h) لأعلى صندوق ثقة، لتحديد ترتيب السمات الفعلي بيقين
        // بدل الافتراض. الأنماط الشائعة المحتملة:
        //   xywh: [cx, cy, w, h]           ← الافتراض الحالي (تقليدي/PyTorch)
        //   yxhw: [cy, cx, h, w]           ← تحويلات TensorFlow أحياناً "y أولاً"
        var bestRawScore = 0f
        var bestRawAttrs = FloatArray(4)

        for (i in 0 until numPredictions) {
            val base = i * numAttrs
            val cx = output[base + 0]
            val cy = output[base + 1]
            val w  = output[base + 2]
            val h  = output[base + 3]

            var bestClassId = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val score = output[base + 4 + c]
                if (score > bestScore) {
                    bestScore = score
                    bestClassId = c
                }
            }

            val personScore = output[base + 4 + personClassId]
            if (personScore > bestPersonScoreSeen) bestPersonScoreSeen = personScore

            if (bestScore > bestRawScore) {
                bestRawScore = bestScore
                bestRawAttrs = floatArrayOf(cx, cy, w, h)
            }

            // ✅ إصلاح جوهري: نماذج YOLO الحديثة تستخدم sigmoid مستقل لكل فئة
            // (وليس softmax تنافسي) — يعني عدة فئات قد تسجّل ثقة عالية على نفس
            // الصندوق في آنٍ واحد. الشرط الصحيح هو فحص ثقة "شخص" مباشرة، بغض
            // النظر عن كونها الفئة الأعلى (argmax) عند هذا الصندوق تحديداً.
            // الشرط القديم (bestClassId == personClassId) كان يُسقط اكتشافات
            // شخص عالية الثقة (0.994) لمجرد أن فئة أخرى تفوّقت عليها بفارق ضئيل
            // جداً (0.996 مقابل 0.994) على نفس الصندوق.
            if (personScore >= confThreshold) {
                Log.d("GhadhooAI", "🧪 [BUILD-MARKER-V2] صندوق مقبول i=$i personScore=${"%.3f".format(personScore)}")
                boxes.add(
                    RawBox(
                        cx = if (coordsArePixelSpace) cx / inputWidth  else cx,
                        cy = if (coordsArePixelSpace) cy / inputHeight else cy,
                        w  = if (coordsArePixelSpace) w  / inputWidth  else w,
                        h  = if (coordsArePixelSpace) h  / inputHeight else h,
                        conf = personScore,
                        classId = personClassId
                    )
                )
            }
        }
        Log.d("GhadhooAI", "🔬 [YOLO] أعلى ثقة person قبل الفلترة: ${"%.3f".format(bestPersonScoreSeen)} | عتبة القبول: $confThreshold")
        Log.d(
            "GhadhooAI",
            "🔬 [YOLO] أفضل صندوق خام (قبل أي تحويل): " +
                "attr0=${"%.3f".format(bestRawAttrs[0])} attr1=${"%.3f".format(bestRawAttrs[1])} " +
                "attr2=${"%.3f".format(bestRawAttrs[2])} attr3=${"%.3f".format(bestRawAttrs[3])} " +
                "score=${"%.3f".format(bestRawScore)}"
        )
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