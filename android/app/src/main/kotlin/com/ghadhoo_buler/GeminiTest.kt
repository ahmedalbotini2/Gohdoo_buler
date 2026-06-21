package com.ghadhoo_buler

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
 * ✅ استثناء يُرمى فقط عندما تفشل **كل** الموديلات بالقائمة بسبب تجاوز
 * الحصة المجانية اليومية (HTTP 429). يُستخدم من ScreenMonitorService
 * لاكتشاف هذه الحالة بدقة والتراجع التلقائي إلى المحلل المحلي.
 */
class RateLimitExceededException(message: String) : Exception(message)

/**
 * ══════════════════════════════════════════════════════════════════════
 * DirectOpenRouterAnalyzer
 * يدعم التدوير (rotation) بين عدة موديلات مجانية: إذا رجع موديل ما خطأ
 * 429 (تجاوز الحصة)، يُجرَّب الموديل التالي بالقائمة لنفس الصورة فوراً.
 * فقط إذا فشلت كل الموديلات بـ429 يُرمى RateLimitExceededException،
 * والذي يُعامله ScreenMonitorService بالتراجع للمحلل المحلي.
 *
 * ملاحظات مهمة:
 * 1) الحد المجاني اليومي في OpenRouter غالباً تراكمي على مستوى الحساب
 *    كله (وليس لكل موديل بمفرده)، فالتدوير لا يحل المشكلة دائماً، لكنه
 *    يساعد في الحالات التي يكون الحد منفصلاً لكل مزود/موديل.
 * 2) كل modelId يجب أن يطابق بالضبط الاسم الظاهر في openrouter.ai/models
 *    ويدعم Vision/Image Input، وإلا سيفشل الطلب لأسباب أخرى غير 429.
 * 3) القائمة المجانية تتغير بمرور الوقت — تأكد من تحديثها دورياً.
 * ══════════════════════════════════════════════════════════════════════
 */
class DirectOpenRouterAnalyzer(
    private val openRouterApiKey: String,
    private val modelIds: List<String> = DEFAULT_MODEL_ROTATION
) : ContentAnalyzer {

    override var isReady = true
        private set

    override val analyzerType = AnalyzerType.CLOUD

    companion object {
        private const val TAG = "DirectOpenRouterAnalyzer"
        private const val UNSAFE_THRESHOLD = 60
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private const val HTTP_TOO_MANY_REQUESTS = 429

        // ✅ ترتيب التدوير الافتراضي: يبدأ بالأقوى (Vision أدق) وينتقل
        // للأبسط عند الفشل. عدّل هذه القائمة حسب توفر الموديلات لديك على
        // openrouter.ai/models (تأكد من دعم Vision لكل موديل تضيفه).
        val DEFAULT_MODEL_ROTATION = listOf(
            "nvidia/nemotron-nano-12b-v2-vl:free",
            "google/gemma-4-31b-it:free",
            "qwen/qwen2.5-vl-32b-instruct:free"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ══════════════════════════════════════════════════════════════════════
    // analyze: يحوّل الـ bitmap إلى base64 ويجرّب كل موديل بالقائمة بالتتابع.
    // ⚠️ يرمي RateLimitExceededException فقط إذا فشلت كل الموديلات بـ429.
    // أي خطأ آخر (غير 429) لموديل معيّن يُسجَّل ويُكمل للموديل التالي أيضاً،
    // لتعظيم فرص نجاح التحليل بأي وسيلة متاحة.
    // ══════════════════════════════════════════════════════════════════════
    override suspend fun analyze(bitmap: Bitmap): AnalysisResult = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)
        var allFailedWithRateLimit = true
        var lastRateLimitMessage = "تم تجاوز الحد المجاني اليومي لكل الموديلات السحابية المتاحة"

        for ((index, modelId) in modelIds.withIndex()) {
            try {
                val result = requestAnalysis(modelId, base64Image)
                Log.d(TAG, "✅ نجح التحليل بالموديل: $modelId")
                return@withContext result
            } catch (e: RateLimitExceededException) {
                Log.w(TAG, "🛑 429 للموديل [$modelId] (${index + 1}/${modelIds.size}) — تجربة التالي...")
                lastRateLimitMessage = e.message ?: lastRateLimitMessage
                continue // جرّب الموديل التالي
            } catch (e: Exception) {
                Log.e(TAG, "❌ خطأ غير متعلق بـ429 للموديل [$modelId]: ${e.message}")
                allFailedWithRateLimit = false
                continue // جرّب الموديل التالي أيضاً، فقد يكون عطلاً مؤقتاً بمزود واحد
            }
        }

        // إذا وصلنا هنا، فكل الموديلات فشلت
        if (allFailedWithRateLimit) {
            throw RateLimitExceededException(lastRateLimitMessage)
        }
        // فشل عام (ليس 429 بالضرورة لكل المحاولات) — نعتبر المحتوى آمناً
        // احترازياً بدل إيقاف الحماية بالكامل
        Log.e(TAG, "❌ فشلت كل الموديلات بأخطاء متنوعة، اعتبار المحتوى آمناً مؤقتاً")
        AnalysisResult(false)
    }

    // ══════════════════════════════════════════════════════════════════════
    // طلب تحليل واحد لموديل محدد — يرمي RateLimitExceededException عند 429
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
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $openRouterApiKey")
            .addHeader("Content-Type", "application/json")
            // الترويستان التاليتان اختياريتان، تساعدان OpenRouter في تصنيف
            // التطبيق ضمن لوحاتهم الإحصائية، يمكن حذفهما بدون مشاكل
            .addHeader("HTTP-Referer", "https://github.com/ghadhoo-buler")
            .addHeader("X-Title", "Ghadhoo Buler Content Analyzer")
            .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        Log.d(TAG, "🚀 جاري إرسال الصورة إلى OpenRouter ($modelId) للتحليل...")

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                if (response.code == HTTP_TOO_MANY_REQUESTS) {
                    Log.e(TAG, "🛑 تجاوز الحصة المجانية (429) للموديل $modelId: $bodyStr")
                    throw RateLimitExceededException(extractRateLimitMessage(bodyStr))
                }
                Log.e(TAG, "❌ فشل الطلب للموديل $modelId: HTTP ${response.code} | body=$bodyStr")
                throw Exception("HTTP ${response.code} للموديل $modelId")
            }

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
    // تحويل Bitmap إلى نص base64 (data URL) لإرساله ضمن JSON
    // ══════════════════════════════════════════════════════════════════════
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
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
    // تحليل الرد (نفس منطق DirectGeminiAnalyzer تماماً)
    // ══════════════════════════════════════════════════════════════════════
    private fun parseResponse(body: String): AnalysisResult {
        return try {
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

            AnalysisResult(
                isUnsafe   = isUnsafe && confidence >= UNSAFE_THRESHOLD,
                regions    = if (regions.isNotEmpty()) regions else null,
                confidence = confidence,
                reason     = reason
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تحليل الرد: ${e.message} | body=$body")
            AnalysisResult(false)
        }
    }

    override fun close() {
        Log.d(TAG, "✅ DirectOpenRouterAnalyzer closed")
    }
}