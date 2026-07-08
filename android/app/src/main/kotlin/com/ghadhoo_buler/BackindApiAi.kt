package com.ghadhoo_buler

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class BackendApiAi(
    // ✅ عنوان سيرفر Laravel — يُمرَّر من الخارج ولا يُكتب هنا
    private val baseUrl: String,
    // ✅ المفتاح السري بين التطبيق والسيرفر (وليس مفتاح OpenRouter)
    private val appApiKey: String
) : ContentAnalyzer {

    override var isReady = true       // لا يحتاج تهيئة — جاهز فور الإنشاء
        private set

    // ✅ تصحيح: BackendApiAi يرجع مناطق دقيقة (regions) من السيرفر، تماماً
    // مثل DirectOpenRouterAnalyzer — لذلك هو CLOUD (حجب دقيق) وليس LOCAL
    // (حجب الشاشة كاملة). راجع تعليق تعريف AnalyzerType في ContentAnalyzer.kt.
    override val analyzerType = AnalyzerType.CLOUD

    companion object {
        private const val TAG             = "BackendApiAi"
        private const val ENDPOINT        = "/api/analyze"
        private const val HEALTH_ENDPOINT = "/api/health"
        private const val TIMEOUT_MS      = 10_000          // 10 ثواني
        private const val BOUNDARY        = "GhuddooBoundary2025"
        private const val UNSAFE_THRESHOLD = 60             // confidence% — فوقه نعتبره غير آمن
    }

    // ══════════════════════════════════════════════════════════════════════
    // analyze: مطلوب من ContentAnalyzer interface
    // يُرسل الـ bitmap للسيرفر ويُرجع AnalysisResult
    // ══════════════════════════════════════════════════════════════════════
    override suspend fun analyze(bitmap: Bitmap): AnalysisResult =
        withContext(Dispatchers.IO) {

            var connection: HttpURLConnection? = null

            return@withContext try {
                // 1. تحويل الـ bitmap لـ JPEG bytes
                val imageBytes = bitmapToJpegBytes(bitmap)

                // 2. فتح الاتصال
                val url = URL("$baseUrl$ENDPOINT")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod    = "POST"
                    connectTimeout   = TIMEOUT_MS
                    readTimeout      = TIMEOUT_MS
                    doOutput         = true
                    doInput          = true
                    setRequestProperty("X-App-Key",    appApiKey)
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
                    setRequestProperty("Accept",       "application/json")
                }

                // 3. كتابة الـ multipart body
                writeMultipartBody(connection, imageBytes)

                // 4. قراءة الرد
                val responseCode = connection.responseCode
                Log.d(TAG, "📡 Response code: $responseCode")

                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val responseBody = connection.inputStream
                            .bufferedReader()
                            .use { it.readText() }
                        parseResponse(responseBody)
                    }
                    HttpURLConnection.HTTP_UNAUTHORIZED -> {
                        Log.e(TAG, "❌ مفتاح التطبيق غلط (X-App-Key) — تحقق من MOBILE_APP_API_KEY على Render")
                        AnalysisResult(false)
                    }
                    429 -> {
                        // ✅ هذا 429 خاص بالباك اند نفسه (لو فعّل Rate Limiting)،
                        // وليس نفس الاستثناء الخاص بـ DirectOpenRouterAnalyzer.
                        // BackendApiAi هو "المحطة الأخيرة" في سلسلة التراجع، لذلك
                        // نتعامل معه هنا بأمان (نعتبر المحتوى آمناً) بدل رميه للأعلى.
                        Log.w(TAG, "⚠️ تجاوز الحد المسموح به على السيرفر نفسه")
                        AnalysisResult(false)
                    }
                    else -> {
                        val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        Log.e(TAG, "❌ خطأ من السيرفر ($responseCode): $err")
                        AnalysisResult(false)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ خطأ في الاتصال: ${e.message}")
                AnalysisResult(false)   // عند الخطأ نعتبر المحتوى آمناً (fail-safe)
            } finally {
                connection?.disconnect()
            }
        }

    // ══════════════════════════════════════════════════════════════════════
    // تحليل الرد من السيرفر
    // ══════════════════════════════════════════════════════════════════════
    private fun parseResponse(body: String): AnalysisResult {
        return try {
            val json       = JSONObject(body)
            val isUnsafe   = json.optBoolean("is_unsafe",   false)
            val confidence = json.optInt("confidence",      0)
            val reason     = json.optString("reason",       "")
            val regionsArr = json.optJSONArray("regions")

            Log.d(TAG, "📊 is_unsafe=$isUnsafe | confidence=$confidence% | reason=$reason")

            // تحويل regions لقائمة RegionResult (إحداثيات من 0.0 إلى 1.0)
            val regions = mutableListOf<RegionResult>()
            if (regionsArr != null) {
                for (i in 0 until regionsArr.length()) {
                    val region = regionsArr.getJSONObject(i)
                    val label  = region.optString("label", "")
                    val box    = region.optJSONArray("box")

                    if (box != null && box.length() == 4) {
                        // السيرفر يُرجع [y_min, x_min, y_max, x_max] بنطاق 0–1000
                        // نحوّلها لـ RectF بنطاق 0.0–1.0
                        val rect = RectF(
                            box.getInt(1) / 1000f,   // left  = x_min
                            box.getInt(0) / 1000f,   // top   = y_min
                            box.getInt(3) / 1000f,   // right = x_max
                            box.getInt(2) / 1000f    // bottom= y_max
                        )
                        regions.add(RegionResult(label = label, bounds = rect))
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

    // ══════════════════════════════════════════════════════════════════════
    // كتابة الـ multipart/form-data body
    // ══════════════════════════════════════════════════════════════════════
    private fun writeMultipartBody(conn: HttpURLConnection, imageBytes: ByteArray) {
        conn.outputStream.use { outputStream ->
            val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)

            // حقل الصورة
            writer.write("--$BOUNDARY\r\n")
            writer.write("Content-Disposition: form-data; name=\"image\"; filename=\"screen.jpg\"\r\n")
            writer.write("Content-Type: image/jpeg\r\n\r\n")
            writer.flush()

            outputStream.write(imageBytes)
            outputStream.flush()

            // نهاية الـ boundary
            writer.write("\r\n--$BOUNDARY--\r\n")
            writer.flush()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // تحويل Bitmap لـ JPEG بجودة 75% (كافية للتحليل وتوفر الـ bandwidth)
    // ══════════════════════════════════════════════════════════════════════
    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
        return output.toByteArray()
    }

    // ══════════════════════════════════════════════════════════════════════
    // checkHealth: تحقق من اتصال السيرفر
    // استخدمه عند بدء التطبيق لتحديد هل API متاح أم لا
    // ══════════════════════════════════════════════════════════════════════
    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        return@withContext try {
            val url = URL("$baseUrl$HEALTH_ENDPOINT")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod  = "GET"
                connectTimeout = 5_000
                readTimeout    = 5_000
            }
            val ok = connection.responseCode == HttpURLConnection.HTTP_OK
            Log.d(TAG, if (ok) "✅ السيرفر يعمل" else "❌ السيرفر لا يستجيب")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "❌ السيرفر غير متاح: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    override fun close() {
        // لا موارد تحتاج تحرير
        Log.d(TAG, "✅ BackendApiAi closed")
    }
}