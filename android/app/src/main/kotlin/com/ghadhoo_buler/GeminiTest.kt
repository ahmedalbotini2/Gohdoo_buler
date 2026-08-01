// package com.ghadhoo_buler

// import android.graphics.Bitmap
// import android.graphics.RectF
// import android.util.Base64
// import android.util.Log
// import kotlinx.coroutines.Dispatchers
// import kotlinx.coroutines.delay
// import kotlinx.coroutines.withContext
// import okhttp3.MediaType.Companion.toMediaType
// import okhttp3.OkHttpClient
// import okhttp3.Request
// import okhttp3.RequestBody.Companion.toRequestBody
// import org.json.JSONArray
// import org.json.JSONObject
// import java.io.ByteArrayOutputStream
// import java.util.concurrent.TimeUnit

// /**
//  * ✅ استثناء يُرمى عندما تفشل كل المحاولات المتاحة بسبب تجاوز الحصة المجانية
//  * (HTTP 429) على كل الموديلات المُجرَّبة. يُستخدم من ScreenMonitorService
//  * لاكتشاف هذه الحالة بدقة والتراجع التلقائي إلى الباك اند.
//  */
// class RateLimitExceededException(message: String) : Exception(message)

// /**
//  * ══════════════════════════════════════════════════════════════════════
//  * DirectOpenRouterAnalyzer (نسخة ديناميكية — تجلب الموديلات المجانية حياً)
//  *
//  * لماذا هذه النسخة مختلفة عن كل المحاولات السابقة؟
//  * قائمة الموديلات المجانية على OpenRouter تتغيّر بشكل شبه أسبوعي (نماذج
//  * تُسحب، تُعاد تسميتها، أو تصبح مدفوعة فجأة). تثبيت اسم موديل واحد بالكود
//  * (سواء يدوياً أو عبر "openrouter/free") يعني أعطال متكررة كل ما تغيّرت
//  * القائمة من طرف OpenRouter، بدون أي تدخل منك.
//  *
//  * الحل هنا: بدل تخمين الأسماء، التطبيق يسأل OpenRouter مباشرة عبر
//  * GET /api/v1/models عن كل الموديلات المتاحة حالياً، ويُصفّي منها فقط
//  * التي (أ) مجانية فعلاً (pricing = 0) و(ب) تدعم إدخال صور (Vision).
//  * القائمة تُخزَّن مؤقتاً (cache) لمدة ساعة لتفادي استدعاء إضافي بكل تحليل.
//  *
//  * ✅ جديد: ترتيب أولوية الموديلات (وليس فلترة/استبعاد أي منها):
//  * موديلات الرؤية العامة (Vision LLMs قادرة على إرجاع bounding boxes ضمن
//  * JSON) تُجرَّب أولاً، لأنها الوحيدة القادرة فعلياً على حجب مناطق محددة
//  * بدقة. أما موديلات تصنيف الأمان النصية البحتة (مثل
//  * nvidia/nemotron-*-content-safety، التي ترد بصيغة "User Safety: unsafe"
//  * بدون أي إحداثيات على الإطلاق — راجع parseTextSafetyResponse أدناه) فتُدفع
//  * لنهاية قائمة المحاولة، وتُستخدم فقط كخط دفاع أخير سريع لو باقي الموديلات
//  * فشلت. هذا يعني حجباً دقيقاً بالمناطق في أغلب الحالات بدل تعتيم الشاشة
//  * كاملة، مع الحفاظ على نفس الحماية الاحتياطية القديمة.
//  *
//  * عند التحليل: نجرّب الموديلات المُرتَّبة بالتتابع (حتى MAX_MODELS_TO_TRY)
//  * — كل موديل يُعطى فرصة واحدة سريعة، وأي 429 ينتقل للموديل التالي فوراً
//  * (بدل انتظار طويل على نفس الموديل المزدحم). فقط لو فشلت كلها بـ429
//  * يُرمى RateLimitExceededException ليتم التراجع للباك اند.
//  * ══════════════════════════════════════════════════════════════════════
//  */
// class DirectOpenRouterAnalyzer(
//     private val openRouterApiKey: String
// ) : ContentAnalyzer {

//     override var isReady = true
//         private set

//     override val analyzerType = AnalyzerType.CLOUD

//     companion object {
//         private const val TAG = "DirectOpenRouterAnalyzer"
//         private const val UNSAFE_THRESHOLD = 60
//         private const val CHAT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
//         private const val MODELS_ENDPOINT = "https://openrouter.ai/api/v1/models"
//         private const val HTTP_TOO_MANY_REQUESTS = 429

//         // أقصى ضلع للصورة بعد التصغير (بالبكسل). 768 كافٍ لمعظم موديلات
//         // الـ Vision لاكتشاف محتوى غير لائق دون الحاجة لدقة الشاشة الكاملة.
//         private const val MAX_IMAGE_DIMENSION = 768
//         private const val JPEG_QUALITY = 75

//         // أقصى عدد موديلات نجرّبها بالتتابع بجولة تحليل واحدة
//         private const val MAX_MODELS_TO_TRY = 4

//         // مدة صلاحية كاش قائمة الموديلات المجانية (ساعة واحدة)
//         private const val MODEL_LIST_CACHE_MS = 60 * 60 * 1000L

//         // ✅ جديد: كلمات مفتاحية تُعرِّف موديلات تصنيف الأمان النصية البحتة
//         // (لا ترجع bounding boxes أبداً، فقط "آمن/غير آمن" كنص). أي موديل
//         // معرّفه (id) يحتوي أياً من هذه الكلمات يُعتبر "آخر الطابور" في
//         // ترتيب المحاولة — لا يُستبعد، فقط يُؤجَّل بعد موديلات الرؤية العامة.
//         private val SAFETY_CLASSIFIER_KEYWORDS = listOf(
//             "content-safety", "-safety", "safety-", "guard", "moderation", "moderator"
//         )

//         // ✅ جديد: كلمات مفتاحية تُعرِّف موديلات "التفكير الممتد" (reasoning/
//         // thinking) — أبطأ بطبيعتها بسبب خطوات التفكير الداخلية، حتى لو
//         // كانت قادرة على تحديد المناطق (regions) بدقة. تُجرَّب بعد موديلات
//         // الرؤية السريعة العادية، لكن قبل موديلات تصنيف الأمان النصية
//         // (اللي مالهاش تحديد مناطق إطلاقاً).
//         private val REASONING_MODEL_KEYWORDS = listOf("reasoning", "thinking")

//         // ✅ كاش مشترك بين كل نسخ الكلاس (companion) — يمنع استدعاء
//         // /models في كل تحليل، ويُحدَّث تلقائياً كل ساعة أو عند الفشل الكامل
//         @Volatile private var cachedFreeVisionModels: List<String> = emptyList()
//         @Volatile private var cacheTimestamp: Long = 0L

//         // يتحقق هل معرّف الموديل يطابق نمط موديلات تصنيف الأمان النصية
//         private fun isSafetyClassifierModel(modelId: String): Boolean {
//             val lower = modelId.lowercase()
//             return SAFETY_CLASSIFIER_KEYWORDS.any { lower.contains(it) }
//         }

//         // ✅ جديد: يتحقق هل معرّف الموديل يطابق نمط موديلات التفكير الممتد
//         private fun isReasoningModel(modelId: String): Boolean {
//             val lower = modelId.lowercase()
//             return REASONING_MODEL_KEYWORDS.any { lower.contains(it) }
//         }

//         // ✅ جديد: رتبة الأولوية — 0 = رؤية سريعة عادية (الأفضل والأسرع)،
//         // 1 = رؤية بتفكير ممتد (أبطأ لكن لسه بترجع مناطق)، 2 = تصنيف نصي
//         // بحت (الأسرع لكن بلا مناطق إطلاقاً — آخر الطابور)
//         private fun modelPriorityTier(modelId: String): Int = when {
//             isSafetyClassifierModel(modelId) -> 2
//             isReasoningModel(modelId)        -> 1
//             else                              -> 0
//         }
//     }

//     // ✅ جديد: تقليل المهلات من (8s/15s/10s) — لو مزوّد موديل معيّن مزدحم
//     // (رفض بطيء زي ما شفنا في اللوج: 429 بعد 5+ ثوانٍ)، أفضل نستسلم بسرعة
//     // وننتقل للموديل التالي بدل انتظار طويل يعطّل السلسلة كلها.
//     private val client = OkHttpClient.Builder()
//         .connectTimeout(5, TimeUnit.SECONDS)
//         .readTimeout(6, TimeUnit.SECONDS)
//         .writeTimeout(6, TimeUnit.SECONDS)
//         .build()

//     // ══════════════════════════════════════════════════════════════════════
//     // analyze: يجلب (أو يستخدم الكاش) قائمة الموديلات المجانية الداعمة
//     // للصور (مُرتَّبة: رؤية عامة أولاً، تصنيف نصي أخيراً)، ثم يجرّبها
//     // بالتتابع. أي 429 ينتقل فوراً للموديل التالي.
//     // فقط لو فشلت كل المحاولات بـ429 يُرمى RateLimitExceededException.
//     // ══════════════════════════════════════════════════════════════════════
//     override suspend fun analyze(bitmap: Bitmap): AnalysisResult = withContext(Dispatchers.IO) {
//         val base64Image = bitmapToBase64(bitmap)
//         val models = getFreeVisionModels()

//         if (models.isEmpty()) {
//             Log.e(TAG, "❌ لا توجد موديلات مجانية داعمة للصور متاحة حالياً")
//             throw RateLimitExceededException("لا توجد موديلات سحابية مجانية متاحة حالياً")
//         }

//         var allFailedWithRateLimit = true
//         var lastRateLimitMessage = "تم تجاوز الحد المجاني اليومي لكل الموديلات السحابية المتاحة"

//         for ((index, modelId) in models.take(MAX_MODELS_TO_TRY).withIndex()) {
//             try {
//                 val result = requestAnalysis(modelId, base64Image)
//                 Log.d(TAG, "✅ نجح التحليل بالموديل: $modelId")
//                 return@withContext result
//             } catch (e: RateLimitExceededException) {
//                 Log.w(TAG, "🛑 429 للموديل [$modelId] (${index + 1}/${minOf(models.size, MAX_MODELS_TO_TRY)}) — تجربة التالي...")
//                 lastRateLimitMessage = e.message ?: lastRateLimitMessage
//                 continue
//             } catch (e: Exception) {
//                 Log.e(TAG, "❌ خطأ غير متعلق بـ429 للموديل [$modelId]: ${e.message}")
//                 allFailedWithRateLimit = false
//                 // نُفرغ الكاش لهذا الموديل تحديداً لو كان 404 (موديل سُحب)
//                 // بإجبار تحديث القائمة بالمحاولة القادمة
//                 if (e.message?.contains("404") == true) {
//                     cacheTimestamp = 0L
//                 }
//                 continue
//             }
//         }

//         if (allFailedWithRateLimit) {
//             throw RateLimitExceededException(lastRateLimitMessage)
//         }
//         Log.e(TAG, "❌ فشلت كل الموديلات المُجرَّبة بأخطاء متنوعة، اعتبار المحتوى آمناً مؤقتاً")
//         AnalysisResult(false)
//     }

//     // ══════════════════════════════════════════════════════════════════════
//     // يرجع قائمة الموديلات المجانية الداعمة للصور — من الكاش لو لسا صالح،
//     // وإلا يجلبها حياً من OpenRouter عبر GET /api/v1/models
//     // ══════════════════════════════════════════════════════════════════════
//     private fun getFreeVisionModels(): List<String> {
//         val now = System.currentTimeMillis()
//         if (cachedFreeVisionModels.isNotEmpty() && (now - cacheTimestamp) < MODEL_LIST_CACHE_MS) {
//             return cachedFreeVisionModels
//         }

//         return try {
//             val fetched = fetchFreeVisionModelsFromApi()
//             if (fetched.isNotEmpty()) {
//                 cachedFreeVisionModels = fetched
//                 cacheTimestamp = now
//                 Log.d(TAG, "🔄 تحديث قائمة الموديلات المجانية (رؤية عامة أولاً): ${fetched.size} موديل متاح — $fetched")
//                 fetched
//             } else if (cachedFreeVisionModels.isNotEmpty()) {
//                 // فشل التحديث لكن عندنا كاش قديم — أفضل من لا شي
//                 Log.w(TAG, "⚠️ فشل تحديث القائمة، استخدام الكاش القديم")
//                 cachedFreeVisionModels
//             } else {
//                 emptyList()
//             }
//         } catch (e: Exception) {
//             Log.e(TAG, "❌ فشل جلب قائمة الموديلات: ${e.message}")
//             cachedFreeVisionModels // قد تكون فارغة أو قديمة، حسب الحالة
//         }
//     }

//     // ══════════════════════════════════════════════════════════════════════
//     // يستدعي GET /api/v1/models ويُصفّي: مجانية (pricing=0) + تدعم صور،
//     // ثم يُرتّب النتيجة (رؤية عامة أولاً، تصنيف أمان نصي أخيراً) — ✅ جديد
//     // ══════════════════════════════════════════════════════════════════════
//     private fun fetchFreeVisionModelsFromApi(): List<String> {
//         val request = Request.Builder()
//             .url(MODELS_ENDPOINT)
//             .addHeader("Authorization", "Bearer $openRouterApiKey")
//             .get()
//             .build()

//         client.newCall(request).execute().use { response ->
//             if (!response.isSuccessful) {
//                 Log.e(TAG, "❌ فشل جلب قائمة الموديلات: HTTP ${response.code}")
//                 return emptyList()
//             }

//             val bodyStr = response.body?.string() ?: return emptyList()
//             val json = JSONObject(bodyStr)
//             val dataArr = json.optJSONArray("data") ?: return emptyList()

//             val result = mutableListOf<String>()
//             for (i in 0 until dataArr.length()) {
//                 val model = dataArr.optJSONObject(i) ?: continue
//                 val id = model.optString("id", "")
//                 if (id.isEmpty() || !id.endsWith(":free")) continue

//                 // تأكيد إن التسعير فعلاً صفر (طبقة مجانية حقيقية)
//                 val pricing = model.optJSONObject("pricing")
//                 val promptPrice = pricing?.optString("prompt", "0") ?: "0"
//                 val isFree = promptPrice == "0" || promptPrice.toDoubleOrNull() == 0.0
//                 if (!isFree) continue

//                 // تأكيد دعم إدخال الصور (Vision) عبر حقل architecture
//                 val architecture = model.optJSONObject("architecture")
//                 val modality = architecture?.optString("modality", "") ?: ""
//                 val inputModalities = architecture?.optJSONArray("input_modalities")
//                 var supportsImage = modality.contains("image", ignoreCase = true)
//                 if (!supportsImage && inputModalities != null) {
//                     for (j in 0 until inputModalities.length()) {
//                         if (inputModalities.optString(j).equals("image", ignoreCase = true)) {
//                             supportsImage = true
//                             break
//                         }
//                     }
//                 }
//                 if (!supportsImage) continue

//                 // ✅ الاقتصار على عائلة Nvidia Nemotron فقط (nvidia/nemotron-*)
//                 if (!id.contains("nvidia/nemotron", ignoreCase = true)) continue

//                 result.add(id)
//             }

//             // ✅ جديد: إعادة ترتيب بدون استبعاد — 3 مستويات أولوية:
//             // 0) رؤية سريعة عادية أولاً (الأفضل: دقة + سرعة)
//             // 1) رؤية بتفكير ممتد (reasoning/thinking) بعدها — أبطأ لكن لسه دقيقة
//             // 2) تصنيف أمان نصي بحت أخيراً — الأسرع، لكن بلا مناطق إطلاقاً
//             // sortedBy مستقرة (stable) فتحافظ على الترتيب الأصلي داخل كل
//             // مجموعة كما أرجعه OpenRouter.
//             return result.sortedBy { modelId -> modelPriorityTier(modelId) }
//         }
//     }

//     // ══════════════════════════════════════════════════════════════════════
//     // طلب تحليل واحد للموديل المحدد — يرمي RateLimitExceededException عند 429
//     // ويسجّل زمن الاستجابة الفعلي (elapsedMs) لكل طلب في Logcat.
//     // ══════════════════════════════════════════════════════════════════════
//     private fun requestAnalysis(modelId: String, base64Image: String): AnalysisResult {
//         val prompt = """
//             Analyze this screen image for inappropriate or unsafe content.
//             The screen may contain MULTIPLE separate items (e.g., video
//             thumbnails, images, ads, profile pictures). Judge EACH item
//             independently — do not judge the screen as one unit.

//             For every unsafe item only, add one entry in "regions" with a
//             tight box around just that item. Skip safe items entirely.

//             Return ONLY this JSON, nothing else:
//             {
//               "is_unsafe": boolean,
//               "confidence": integer 0-100,
//               "reason": "short explanation",
//               "regions": [
//                 {"label": "string", "x_min": int, "y_min": int, "x_max": int, "y_max": int}
//               ]
//             }
//             Coordinates are 0-1000, relative to image width (x) and height
//             (y). x_min<x_max, y_min<y_max. is_unsafe=true if at least one
//             item is unsafe; false with empty regions only if all are safe.
//             Be quick and decisive — do not overthink simple cases.
//         """.trimIndent()

//         val requestBodyJson = buildRequestBody(modelId, prompt, base64Image)

//         val request = Request.Builder()
//             .url(CHAT_ENDPOINT)
//             .addHeader("Authorization", "Bearer $openRouterApiKey")
//             .addHeader("Content-Type", "application/json")
//             // الترويستان التاليتان اختياريتان، تساعدان OpenRouter في تصنيف
//             // التطبيق ضمن لوحاتهم الإحصائية، يمكن حذفهما بدون مشاكل
//             .addHeader("HTTP-Referer", "https://github.com/ghadhoo-buler")
//             .addHeader("X-Title", "Ghadhoo Buler Content Analyzer")
//             .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
//             .build()

//         Log.d(TAG, "🚀 جاري إرسال الصورة إلى OpenRouter ($modelId) للتحليل...")
//         val startTime = System.currentTimeMillis()

//         client.newCall(request).execute().use { response ->
//             val elapsedMs = System.currentTimeMillis() - startTime
//             val bodyStr = response.body?.string() ?: ""

//             if (!response.isSuccessful) {
//                 if (response.code == HTTP_TOO_MANY_REQUESTS) {
//                     Log.e(TAG, "🛑 تجاوز الحصة المجانية (429) للموديل $modelId بعد ${elapsedMs}ms: $bodyStr")
//                     throw RateLimitExceededException(extractRateLimitMessage(bodyStr))
//                 }
//                 Log.e(TAG, "❌ فشل الطلب للموديل $modelId بعد ${elapsedMs}ms: HTTP ${response.code} | body=$bodyStr")
//                 throw Exception("HTTP ${response.code} للموديل $modelId")
//             }

//             Log.d(TAG, "⏱️ زمن استجابة $modelId: ${elapsedMs}ms")

//             val responseText = extractContentText(bodyStr)
//             return parseResponse(responseText)
//         }
//     }

//     // استخراج رسالة واضحة من جسم رد 429 لعرضها للمستخدم إن لزم
//     private fun extractRateLimitMessage(body: String): String {
//         return try {
//             JSONObject(body).optJSONObject("error")?.optString("message")
//                 ?: "تم تجاوز الحد المجاني اليومي لتحليل الذكاء الاصطناعي السحابي"
//         } catch (e: Exception) {
//             "تم تجاوز الحد المجاني اليومي لتحليل الذكاء الاصطناعي السحابي"
//         }
//     }

//     // ══════════════════════════════════════════════════════════════════════
//     // تحويل Bitmap إلى نص base64 (data URL) لإرساله ضمن JSON.
//     // يتم أولاً تصغير الصورة إلى MAX_IMAGE_DIMENSION بأقصى ضلع للحفاظ على
//     // نسبة الأبعاد وتقليل حجم البيانات المرسلة ووقت معالجة الموديل.
//     // ══════════════════════════════════════════════════════════════════════
//     private fun bitmapToBase64(bitmap: Bitmap): String {
//         val resized = resizeIfNeeded(bitmap)

//         val outputStream = ByteArrayOutputStream()
//         resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
//         val bytes = outputStream.toByteArray()

//         Log.d(TAG, "🖼️ حجم الصورة بعد الضغط: ${bytes.size / 1024} KB (الأبعاد: ${resized.width}x${resized.height})")

//         return Base64.encodeToString(bytes, Base64.NO_WRAP)
//     }

//     private fun resizeIfNeeded(bitmap: Bitmap): Bitmap {
//         val largestDimension = maxOf(bitmap.width, bitmap.height)
//         if (largestDimension <= MAX_IMAGE_DIMENSION) return bitmap

//         val scale = MAX_IMAGE_DIMENSION.toFloat() / largestDimension
//         val newWidth = (bitmap.width * scale).toInt()
//         val newHeight = (bitmap.height * scale).toInt()

//         return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
//     }

//     // ══════════════════════════════════════════════════════════════════════
//     // بناء جسم الطلب بصيغة OpenAI-compatible (chat completions + image_url)
//     // ══════════════════════════════════════════════════════════════════════
//     private fun buildRequestBody(modelId: String, prompt: String, base64Image: String): JSONObject {
//         val imageContent = JSONObject().apply {
//             put("type", "image_url")
//             put("image_url", JSONObject().apply {
//                 put("url", "data:image/jpeg;base64,$base64Image")
//             })
//         }

//         val textContent = JSONObject().apply {
//             put("type", "text")
//             put("text", prompt)
//         }

//         val contentArray = JSONArray().apply {
//             put(textContent)
//             put(imageContent)
//         }

//         val userMessage = JSONObject().apply {
//             put("role", "user")
//             put("content", contentArray)
//         }

//         val messagesArray = JSONArray().apply {
//             put(userMessage)
//         }

//         return JSONObject().apply {
//             put("model", modelId)
//             put("messages", messagesArray)
//             // يفرض على الموديل إرجاع JSON فقط إن كان الموديل يدعم هذا الخيار
//             put("response_format", JSONObject().apply {
//                 put("type", "json_object")
//             })
//             // ✅ جديد: تقليل "مجهود التفكير" (reasoning effort) للموديلات
//             // اللي بتدعم تفكير ممتد (زي nemotron-*-reasoning). المهمة هنا
//             // بسيطة نسبياً (تصنيف + إحداثيات) ومطلوب سرعة استجابة أعلى من
//             // العمق، خصوصاً إن الطلب بيتكرر كل بضع ثوانٍ. الموديلات اللي
//             // مالهاش وضع تفكير أصلاً بتتجاهل الحقل ده بأمان.
//             put("reasoning", JSONObject().apply {
//                 put("effort", "low")
//             })
//             // ✅ جديد: سقف معقول لطول الرد — كافٍ لأكبر عدد متوقع من
//             // المناطق، ويمنع الموديل من الاستطراد أو التفكير المطوّل الزائد
//             put("max_tokens", 700)
//         }
//     }

//     // ══════════════════════════════════════════════════════════════════════
//     // استخراج نص الرد من جسم استجابة OpenRouter (نفس بنية OpenAI)
//     // ══════════════════════════════════════════════════════════════════════
//     private fun extractContentText(body: String): String {
//         return try {
//             val json = JSONObject(body)
//             val choices = json.optJSONArray("choices")
//             val firstChoice = choices?.optJSONObject(0)
//             val message = firstChoice?.optJSONObject("message")
//             var text = message?.optString("content", "{}") ?: "{}"

//             // تنظيف احتياطي في حال أضاف النموذج علامات Markdown
//             text = text.replace("```json", "").replace("```", "").trim()
//             text
//         } catch (e: Exception) {
//             Log.e(TAG, "❌ فشل استخراج النص من رد OpenRouter: ${e.message} | body=$body")
//             "{}"
//         }
//     }

//     // ══════════════════════════════════════════════════════════════════════
//     // تحليل الرد. يدعم صيغتين:
//     // 1) JSON القياسي (المطلوب بالـ prompt) — لمعظم موديلات الـ Vision العامة،
//     //    وهي التي تُجرَّب أولاً الآن بعد إعادة الترتيب أعلاه، فتُرجع مناطق
//     //    محددة (regions) في أغلب الأحيان.
//     // 2) صيغة نصية بسيطة يستخدمها موديل nvidia/nemotron-3.5-content-safety
//     //    (وموديلات تصنيف أمان محتوى مشابهة، تُجرَّب الآن أخيراً كاحتياط):
//     //    "User Safety: safe"
//     //    "User Safety: unsafe\nSafety Categories: Sexual, Profanity"
//     //    هذا الموديل لا يتبع تعليمات JSON لأنه مصمم لغرض تصنيف ثابت فقط
//     //    (لا يعرف الإحداثيات إطلاقاً)، لكنه مفيد كخط دفاع أخير سريع وموثوق
//     //    لأنه مخصص أصلاً لتصنيف أمان المحتوى.
//     // ══════════════════════════════════════════════════════════════════════
//     private fun parseResponse(body: String): AnalysisResult {
//         return try {
//             parseJsonResponse(body)
//         } catch (jsonError: Exception) {
//             try {
//                 parseTextSafetyResponse(body)
//             } catch (textError: Exception) {
//                 Log.e(TAG, "❌ فشل تحليل الرد بكلا الصيغتين (JSON ونصي): ${jsonError.message} | body=$body")
//                 AnalysisResult(false)
//             }
//         }
//     }

//     // ══════════════════════════════════════════════════════════════════════
//     // ✅ جديد: يستخرج RectF من كائن منطقة (region) واحد، بأسلوبين:
//     // 1) الحقول الصريحة x_min/y_min/x_max/y_max (المطلوبة في الـ prompt
//     //    الجديد) — لا لبس فيها إطلاقاً بغض النظر عن الموديل المستخدم.
//     // 2) صيغة احتياطية قديمة "box": [a, b, c, d] لو موديل تجاهل التعليمة
//     //    الجديدة رغم كل شيء — نفترض ترتيب [x_min, y_min, x_max, y_max]
//     //    (الأكثر شيوعاً بين موديلات الرؤية غير Gemini) بدل الترتيب القديم
//     //    [y_min, x_min, y_max, x_max] الذي كان يسبب انعكاس المحاور.
//     // في الحالتين: نتحقق إن min < max ونبدّلهما لو معكوسين (حماية إضافية
//     // من موديلات ضعيفة قد ترجع القيم بترتيب عشوائي)، ونقصّهما لمدى 0..1000.
//     // ══════════════════════════════════════════════════════════════════════
//     private fun extractRegionRect(region: JSONObject): RectF? {
//         val hasNamedFields = region.has("x_min") && region.has("y_min") &&
//                              region.has("x_max") && region.has("y_max")

//         var xMin: Int; var yMin: Int; var xMax: Int; var yMax: Int

//         if (hasNamedFields) {
//             xMin = region.optInt("x_min", -1)
//             yMin = region.optInt("y_min", -1)
//             xMax = region.optInt("x_max", -1)
//             yMax = region.optInt("y_max", -1)
//         } else {
//             val box = region.optJSONArray("box") ?: return null
//             if (box.length() != 4) return null
//             // احتياطي: نفترض ترتيب [x_min, y_min, x_max, y_max] القياسي
//             xMin = box.optInt(0, -1)
//             yMin = box.optInt(1, -1)
//             xMax = box.optInt(2, -1)
//             yMax = box.optInt(3, -1)
//         }

//         if (xMin < 0 || yMin < 0 || xMax < 0 || yMax < 0) return null

//         // حماية من انعكاس min/max (بعض الموديلات الضعيفة قد تخطئ بالترتيب)
//         if (xMin > xMax) { val t = xMin; xMin = xMax; xMax = t }
//         if (yMin > yMax) { val t = yMin; yMin = yMax; yMax = t }

//         val left   = xMin.coerceIn(0, 1000) / 1000f
//         val top    = yMin.coerceIn(0, 1000) / 1000f
//         val right  = xMax.coerceIn(0, 1000) / 1000f
//         val bottom = yMax.coerceIn(0, 1000) / 1000f

//         if (right <= left || bottom <= top) return null // صندوق تافه/فاسد

//         return RectF(left, top, right, bottom)
//     }

//     private fun parseJsonResponse(body: String): AnalysisResult {
//         val json       = JSONObject(body)
//         val isUnsafe   = json.optBoolean("is_unsafe",   false)
//         val confidence = json.optInt("confidence",      0)
//         val reason     = json.optString("reason",       "")
//         val regionsArr = json.optJSONArray("regions")

//         Log.d(TAG, "📊 is_unsafe=$isUnsafe | confidence=$confidence% | reason=$reason")

//         val regions = mutableListOf<RegionResult>()
//         if (regionsArr != null) {
//             for (i in 0 until regionsArr.length()) {
//                 val region = regionsArr.getJSONObject(i)
//                 val label  = region.optString("label", "")
//                 val rect   = extractRegionRect(region)

//                 if (rect != null) {
//                     regions.add(RegionResult(label = label, bounds = rect, confidence = 1.0f))
//                     Log.d(TAG, "📍 منطقة: $label → $rect")
//                 } else {
//                     Log.w(TAG, "⚠️ منطقة تم تجاهلها لعدم وجود إحداثيات صالحة: $region")
//                 }
//             }
//         }

//         if (isUnsafe) Log.w(TAG, "🚨 محتوى غير آمن! (${regions.size} منطقة)")
//         else          Log.d(TAG, "✅ المحتوى آمن")

//         return AnalysisResult(
//             isUnsafe   = isUnsafe && confidence >= UNSAFE_THRESHOLD,
//             regions    = if (regions.isNotEmpty()) regions else null,
//             confidence = confidence,
//             reason     = reason
//         )
//     }

//     // يحلل صيغة "User Safety: safe/unsafe" + "Safety Categories: ..." الاختيارية
//     private fun parseTextSafetyResponse(body: String): AnalysisResult {
//         val safetyLine = body.lineSequence()
//             .firstOrNull { it.contains("User Safety", ignoreCase = true) }
//             ?: throw Exception("لا يوجد سطر 'User Safety' بالرد")

//         val isUnsafe = safetyLine.contains("unsafe", ignoreCase = true)

//         val categoriesLine = body.lineSequence()
//             .firstOrNull { it.contains("Safety Categories", ignoreCase = true) }
//         val categories = categoriesLine
//             ?.substringAfter(":")
//             ?.trim()
//             ?: ""

//         Log.d(TAG, "📊 [نصي] isUnsafe=$isUnsafe | categories=$categories")
//         if (isUnsafe) Log.w(TAG, "🚨 محتوى غير آمن (تصنيف نصي)! فئات: $categories")
//         else          Log.d(TAG, "✅ المحتوى آمن (تصنيف نصي)")

//         // هذا الموديل لا يرجع درجة ثقة رقمية ولا مناطق محددة (bounding boxes)
//         // إطلاقاً — لذلك regions = null دايماً هنا، وOverlayManager سيلجأ
//         // تلقائياً لتغطية الشاشة كاملة في هذه الحالة فقط (راجع buildOverlayContent
//         // في OverlayManager.kt). بعد إعادة الترتيب أعلاه، هذا يحدث فقط عندما
//         // تفشل/تُستنفد كل موديلات الرؤية العامة أولاً.
//         return AnalysisResult(
//             isUnsafe   = isUnsafe,
//             regions    = null,
//             confidence = if (isUnsafe) 100 else 0,
//             reason     = if (categories.isNotEmpty()) categories else if (isUnsafe) "محتوى غير آمن" else "آمن"
//         )
//     }

//     override fun close() {
//         Log.d(TAG, "✅ DirectOpenRouterAnalyzer closed")
//     }
// }