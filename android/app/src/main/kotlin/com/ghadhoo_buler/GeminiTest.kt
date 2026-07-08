package com.ghadhoo_buler

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * ✅ استثناء يُرمى عندما تفشل كل المحاولات المتاحة بسبب تجاوز الحصة المجانية
 * (HTTP 429) على كل الموديلات المُجرَّبة. يُستخدم من ScreenMonitorService
 * لاكتشاف هذه الحالة بدقة والتراجع التلقائي إلى المحلل المحلي.
 */
class RateLimitExceededException(message: String) : Exception(message)

/**
 * ══════════════════════════════════════════════════════════════════════
 * DirectOpenRouterAnalyzer (نسخة ديناميكية — تجلب الموديلات المجانية حياً)
 *
 * لماذا هذه النسخة مختلفة عن كل المحاولات السابقة؟
 * قائمة الموديلات المجانية على OpenRouter تتغيّر بشكل شبه أسبوعي (نماذج
 * تُسحب، تُعاد تسميتها، أو تصبح مدفوعة فجأة). تثبيت اسم موديل واحد بالكود
 * (سواء يدوياً أو عبر "openrouter/free") يعني أعطال متكررة كل ما تغيّرت
 * القائمة من طرف OpenRouter، بدون أي تدخل منك.
 *
 * الحل هنا: بدل تخمين الأسماء، التطبيق يسأل OpenRouter مباشرة عبر
 * GET /api/v1/models عن كل الموديلات المتاحة حالياً، ويُصفّي منها فقط
 * التي (أ) مجانية فعلاً (pricing = 0) و(ب) تدعم إدخال صور (Vision).
 * القائمة تُخزَّن مؤقتاً (cache) لمدة ساعة لتفادي استدعاء إضافي بكل تحليل.
 *
 * عند التحليل: نجرّب الموديلات المُصفّاة بالتتابع (حتى MAX_MODELS_TO_TRY)
 * — كل موديل يُعطى فرصة واحدة سريعة، وأي 429 ينتقل للموديل التالي فوراً
 * (بدل انتظار طويل على نفس الموديل المزدحم). فقط لو فشلت كلها بـ429
 * يُرمى RateLimitExceededException ليتم التراجع للمحلل المحلي.
 * ══════════════════════════════════════════════════════════════════════
 */
class DirectOpenRouterAnalyzer(
    private val openRouterApiKey: String
) : ContentAnalyzer {

    override var isReady = true
        private set

    override val analyzerType = AnalyzerType.CLOUD

    companion object {
        private const val TAG = "DirectOpenRouterAnalyzer"
        private const val UNSAFE_THRESHOLD = 60
        private const val CHAT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private const val MODELS_ENDPOINT = "https://openrouter.ai/api/v1/models"
        private const val HTTP_TOO_MANY_REQUESTS = 429

        // أقصى ضلع للصورة بعد التصغير (بالبكسل). 768 كافٍ لمعظم موديلات
        // الـ Vision لاكتشاف محتوى غير لائق دون الحاجة لدقة الشاشة الكاملة.
        private const val MAX_IMAGE_DIMENSION = 768
        private const val JPEG_QUALITY = 75

        // أقصى عدد موديلات نجرّبها بالتتابع بجولة تحليل واحدة
        private const val MAX_MODELS_TO_TRY = 4

        // مدة صلاحية كاش قائمة الموديلات المجانية (ساعة واحدة)
        private const val MODEL_LIST_CACHE_MS = 60 * 60 * 1000L

        // ✅ كاش مشترك بين كل نسخ الكلاس (companion) — يمنع استدعاء
        // /models في كل تحليل، ويُحدَّث تلقائياً كل ساعة أو عند الفشل الكامل
        @Volatile private var cachedFreeVisionModels: List<String> = emptyList()
        @Volatile private var cacheTimestamp: Long = 0L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ══════════════════════════════════════════════════════════════════════
    // analyze: يجلب (أو يستخدم الكاش) قائمة الموديلات المجانية الداعمة
    // للصور، ثم يجرّبها بالتتابع. أي 429 ينتقل فوراً للموديل التالي.
    // فقط لو فشلت كل المحاولات بـ429 يُرمى RateLimitExceededException.
    // ══════════════════════════════════════════════════════════════════════
    override suspend fun analyze(bitmap: Bitmap): AnalysisResult = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)
        val models = getFreeVisionModels()

        if (models.isEmpty()) {
            Log.e(TAG, "❌ لا توجد موديلات مجانية داعمة للصور متاحة حالياً")
            throw RateLimitExceededException("لا توجد موديلات سحابية مجانية متاحة حالياً")
        }

        var allFailedWithRateLimit = true
        var lastRateLimitMessage = "تم تجاوز الحد المجاني اليومي لكل الموديلات السحابية المتاحة"

        for ((index, modelId) in models.take(MAX_MODELS_TO_TRY).withIndex()) {
            try {
                val result = requestAnalysis(modelId, base64Image)
                Log.d(TAG, "✅ نجح التحليل بالموديل: $modelId")
                return@withContext result
            } catch (e: RateLimitExceededException) {
                Log.w(TAG, "🛑 429 للموديل [$modelId] (${index + 1}/${minOf(models.size, MAX_MODELS_TO_TRY)}) — تجربة التالي...")
                lastRateLimitMessage = e.message ?: lastRateLimitMessage
                continue
            } catch (e: Exception) {
                Log.e(TAG, "❌ خطأ غير متعلق بـ429 للموديل [$modelId]: ${e.message}")
                allFailedWithRateLimit = false
                // نُفرغ الكاش لهذا الموديل تحديداً لو كان 404 (موديل سُحب)
                // بإجبار تحديث القائمة بالمحاولة القادمة
                if (e.message?.contains("404") == true) {
                    cacheTimestamp = 0L
                }
                continue
            }
        }

        if (allFailedWithRateLimit) {
            throw RateLimitExceededException(lastRateLimitMessage)
        }
        Log.e(TAG, "❌ فشلت كل الموديلات المُجرَّبة بأخطاء متنوعة، اعتبار المحتوى آمناً مؤقتاً")
        AnalysisResult(false)
    }

    // ══════════════════════════════════════════════════════════════════════
    // يرجع قائمة الموديلات المجانية الداعمة للصور — من الكاش لو لسا صالح،
    // وإلا يجلبها حياً من OpenRouter عبر GET /api/v1/models
    // ══════════════════════════════════════════════════════════════════════
    private fun getFreeVisionModels(): List<String> {
        val now = System.currentTimeMillis()
        if (cachedFreeVisionModels.isNotEmpty() && (now - cacheTimestamp) < MODEL_LIST_CACHE_MS) {
            return cachedFreeVisionModels
        }

        return try {
            val fetched = fetchFreeVisionModelsFromApi()
            if (fetched.isNotEmpty()) {
                cachedFreeVisionModels = fetched
                cacheTimestamp = now
                Log.d(TAG, "🔄 تحديث قائمة الموديلات المجانية: ${fetched.size} موديل متاح — $fetched")
                fetched
            } else if (cachedFreeVisionModels.isNotEmpty()) {
                // فشل التحديث لكن عندنا كاش قديم — أفضل من لا شي
                Log.w(TAG, "⚠️ فشل تحديث القائمة، استخدام الكاش القديم")
                cachedFreeVisionModels
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل جلب قائمة الموديلات: ${e.message}")
            cachedFreeVisionModels // قد تكون فارغة أو قديمة، حسب الحالة
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // يستدعي GET /api/v1/models ويُصفّي: مجانية (pricing=0) + تدعم صور
    // ══════════════════════════════════════════════════════════════════════
    private fun fetchFreeVisionModelsFromApi(): List<String> {
        val request = Request.Builder()
            .url(MODELS_ENDPOINT)
            .addHeader("Authorization", "Bearer $openRouterApiKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ فشل جلب قائمة الموديلات: HTTP ${response.code}")
                return emptyList()
            }

            val bodyStr = response.body?.string() ?: return emptyList()
            val json = JSONObject(bodyStr)
            val dataArr = json.optJSONArray("data") ?: return emptyList()

            val result = mutableListOf<String>()
            for (i in 0 until dataArr.length()) {
                val model = dataArr.optJSONObject(i) ?: continue
                val id = model.optString("id", "")
                if (id.isEmpty() || !id.endsWith(":free")) continue

                // تأكيد إن التسعير فعلاً صفر (طبقة مجانية حقيقية)
                val pricing = model.optJSONObject("pricing")
                val promptPrice = pricing?.optString("prompt", "0") ?: "0"
                val isFree = promptPrice == "0" || promptPrice.toDoubleOrNull() == 0.0
                if (!isFree) continue

                // تأكيد دعم إدخال الصور (Vision) عبر حقل architecture
                val architecture = model.optJSONObject("architecture")
                val modality = architecture?.optString("modality", "") ?: ""
                val inputModalities = architecture?.optJSONArray("input_modalities")
                var supportsImage = modality.contains("image", ignoreCase = true)
                if (!supportsImage && inputModalities != null) {
                    for (j in 0 until inputModalities.length()) {
                        if (inputModalities.optString(j).equals("image", ignoreCase = true)) {
                            supportsImage = true
                            break
                        }
                    }
                }
                if (!supportsImage) continue

                result.add(id)
            }
            return result
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // طلب تحليل واحد للموديل المحدد — يرمي RateLimitExceededException عند 429
    // ويسجّل زمن الاستجابة الفعلي (elapsedMs) لكل طلب في Logcat.
    // ══════════════════════════════════════════════════════════════════════
    private fun requestAnalysis(modelId: String, base64Image: String): AnalysisResult {
        val prompt = """
            Analyze this screen image for inappropriate or unsafe content.
            Return ONLY a JSON object with the following exact structure, and
            nothing else (no markdown, no extra text):
            {
              "is_unsafe": boolean,
              "confidence": integer between 0 and 100,
              "reason": "short explanation of the detection",
              "regions": [
                {
                  "label": "name of unsafe element",
                  "box": [y_min, x_min, y_max, x_max]
                }
              ]
            }
            Coordinates in "box" must be scaled 0 to 1000.
            If the image is completely safe, return "is_unsafe": false and an empty "regions" array.
        """.trimIndent()

        val requestBodyJson = buildRequestBody(modelId, prompt, base64Image)

        val request = Request.Builder()
            .url(CHAT_ENDPOINT)
            .addHeader("Authorization", "Bearer $openRouterApiKey")
            .addHeader("Content-Type", "application/json")
            // الترويستان التاليتان اختياريتان، تساعدان OpenRouter في تصنيف
            // التطبيق ضمن لوحاتهم الإحصائية، يمكن حذفهما بدون مشاكل
            .addHeader("HTTP-Referer", "https://github.com/ghadhoo-buler")
            .addHeader("X-Title", "Ghadhoo Buler Content Analyzer")
            .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        Log.d(TAG, "🚀 جاري إرسال الصورة إلى OpenRouter ($modelId) للتحليل...")
        val startTime = System.currentTimeMillis()

        client.newCall(request).execute().use { response ->
            val elapsedMs = System.currentTimeMillis() - startTime
            val bodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                if (response.code == HTTP_TOO_MANY_REQUESTS) {
                    Log.e(TAG, "🛑 تجاوز الحصة المجانية (429) للموديل $modelId بعد ${elapsedMs}ms: $bodyStr")
                    throw RateLimitExceededException(extractRateLimitMessage(bodyStr))
                }
                Log.e(TAG, "❌ فشل الطلب للموديل $modelId بعد ${elapsedMs}ms: HTTP ${response.code} | body=$bodyStr")
                throw Exception("HTTP ${response.code} للموديل $modelId")
            }

            Log.d(TAG, "⏱️ زمن استجابة $modelId: ${elapsedMs}ms")

            val responseText = extractContentText(bodyStr)
            return parseResponse(responseText)
        }
    }

    // استخراج رسالة واضحة من جسم رد 429 لعرضها للمستخدم إن لزم
    private fun extractRateLimitMessage(body: String): String {
        return try {
            JSONObject(body).optJSONObject("error")?.optString("message")
                ?: "تم تجاوز الحد المجاني اليومي لتحليل الذكاء الاصطناعي السحابي"
        } catch (e: Exception) {
            "تم تجاوز الحد المجاني اليومي لتحليل الذكاء الاصطناعي السحابي"
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // تحويل Bitmap إلى نص base64 (data URL) لإرساله ضمن JSON.
    // يتم أولاً تصغير الصورة إلى MAX_IMAGE_DIMENSION بأقصى ضلع للحفاظ على
    // نسبة الأبعاد وتقليل حجم البيانات المرسلة ووقت معالجة الموديل.
    // ══════════════════════════════════════════════════════════════════════
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val resized = resizeIfNeeded(bitmap)

        val outputStream = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val bytes = outputStream.toByteArray()

        Log.d(TAG, "🖼️ حجم الصورة بعد الضغط: ${bytes.size / 1024} KB (الأبعاد: ${resized.width}x${resized.height})")

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun resizeIfNeeded(bitmap: Bitmap): Bitmap {
        val largestDimension = maxOf(bitmap.width, bitmap.height)
        if (largestDimension <= MAX_IMAGE_DIMENSION) return bitmap

        val scale = MAX_IMAGE_DIMENSION.toFloat() / largestDimension
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    // ══════════════════════════════════════════════════════════════════════
    // بناء جسم الطلب بصيغة OpenAI-compatible (chat completions + image_url)
    // ══════════════════════════════════════════════════════════════════════
    private fun buildRequestBody(modelId: String, prompt: String, base64Image: String): JSONObject {
        val imageContent = JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/jpeg;base64,$base64Image")
            })
        }

        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        }

        val contentArray = JSONArray().apply {
            put(textContent)
            put(imageContent)
        }

        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        }

        val messagesArray = JSONArray().apply {
            put(userMessage)
        }

        return JSONObject().apply {
            put("model", modelId)
            put("messages", messagesArray)
            // يفرض على الموديل إرجاع JSON فقط إن كان الموديل يدعم هذا الخيار
            put("response_format", JSONObject().apply {
                put("type", "json_object")
            })
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // استخراج نص الرد من جسم استجابة OpenRouter (نفس بنية OpenAI)
    // ══════════════════════════════════════════════════════════════════════
    private fun extractContentText(body: String): String {
        return try {
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            val firstChoice = choices?.optJSONObject(0)
            val message = firstChoice?.optJSONObject("message")
            var text = message?.optString("content", "{}") ?: "{}"

            // تنظيف احتياطي في حال أضاف النموذج علامات Markdown
            text = text.replace("```json", "").replace("```", "").trim()
            text
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل استخراج النص من رد OpenRouter: ${e.message} | body=$body")
            "{}"
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // تحليل الرد. يدعم صيغتين:
    // 1) JSON القياسي (المطلوب بالـ prompt) — لمعظم موديلات الـ Vision العامة.
    // 2) صيغة نصية بسيطة يستخدمها موديل nvidia/nemotron-3.5-content-safety
    //    (وموديلات تصنيف أمان محتوى مشابهة قد تُختار ديناميكياً من القائمة):
    //    "User Safety: safe"
    //    "User Safety: unsafe\nSafety Categories: Sexual, Profanity"
    //    هذا الموديل لا يتبع تعليمات JSON لأنه مصمم لغرض تصنيف ثابت فقط،
    //    لكنه مفيد جداً هنا لأنه مخصص أصلاً لتصنيف أمان المحتوى.
    // ══════════════════════════════════════════════════════════════════════
    private fun parseResponse(body: String): AnalysisResult {
        return try {
            parseJsonResponse(body)
        } catch (jsonError: Exception) {
            try {
                parseTextSafetyResponse(body)
            } catch (textError: Exception) {
                Log.e(TAG, "❌ فشل تحليل الرد بكلا الصيغتين (JSON ونصي): ${jsonError.message} | body=$body")
                AnalysisResult(false)
            }
        }
    }

    private fun parseJsonResponse(body: String): AnalysisResult {
        val json       = JSONObject(body)
        val isUnsafe   = json.optBoolean("is_unsafe",   false)
        val confidence = json.optInt("confidence",      0)
        val reason     = json.optString("reason",       "")
        val regionsArr = json.optJSONArray("regions")

        Log.d(TAG, "📊 is_unsafe=$isUnsafe | confidence=$confidence% | reason=$reason")

        val regions = mutableListOf<RegionResult>()
        if (regionsArr != null) {
            for (i in 0 until regionsArr.length()) {
                val region = regionsArr.getJSONObject(i)
                val label  = region.optString("label", "")
                val box    = region.optJSONArray("box")

                if (box != null && box.length() == 4) {
                    val rect = RectF(
                        box.getInt(1) / 1000f,   // left  = x_min
                        box.getInt(0) / 1000f,   // top   = y_min
                        box.getInt(3) / 1000f,   // right = x_max
                        box.getInt(2) / 1000f    // bottom= y_max
                    )
                    regions.add(RegionResult(label = label, bounds = rect, confidence = 1.0f))
                    Log.d(TAG, "📍 منطقة: $label → $rect")
                }
            }
        }

        if (isUnsafe) Log.w(TAG, "🚨 محتوى غير آمن! (${regions.size} منطقة)")
        else          Log.d(TAG, "✅ المحتوى آمن")

        return AnalysisResult(
            isUnsafe   = isUnsafe && confidence >= UNSAFE_THRESHOLD,
            regions    = if (regions.isNotEmpty()) regions else null,
            confidence = confidence,
            reason     = reason
        )
    }

    // يحلل صيغة "User Safety: safe/unsafe" + "Safety Categories: ..." الاختيارية
    private fun parseTextSafetyResponse(body: String): AnalysisResult {
        val safetyLine = body.lineSequence()
            .firstOrNull { it.contains("User Safety", ignoreCase = true) }
            ?: throw Exception("لا يوجد سطر 'User Safety' بالرد")

        val isUnsafe = safetyLine.contains("unsafe", ignoreCase = true)

        val categoriesLine = body.lineSequence()
            .firstOrNull { it.contains("Safety Categories", ignoreCase = true) }
        val categories = categoriesLine
            ?.substringAfter(":")
            ?.trim()
            ?: ""

        Log.d(TAG, "📊 [نصي] isUnsafe=$isUnsafe | categories=$categories")
        if (isUnsafe) Log.w(TAG, "🚨 محتوى غير آمن (تصنيف نصي)! فئات: $categories")
        else          Log.d(TAG, "✅ المحتوى آمن (تصنيف نصي)")

        // هذا الموديل لا يرجع درجة ثقة رقمية ولا مناطق محددة (bounding boxes)،
        // فنعتمد تصنيفه الثنائي مباشرة (بدون بوابة UNSAFE_THRESHOLD لأنها
        // مصممة لموديلات JSON التي ترجع نسبة ثقة فعلية)
        return AnalysisResult(
            isUnsafe   = isUnsafe,
            regions    = null,
            confidence = if (isUnsafe) 100 else 0,
            reason     = if (categories.isNotEmpty()) categories else if (isUnsafe) "محتوى غير آمن" else "آمن"
        )
    }

    override fun close() {
        Log.d(TAG, "✅ DirectOpenRouterAnalyzer closed")
    }
}