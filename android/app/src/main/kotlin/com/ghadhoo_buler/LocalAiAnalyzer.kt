package com.ghadhoo_buler

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.ghadhoo_buler.ContentAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class LocalAiAnalyzer(private val context: Context) : ContentAnalyzer {

    override var isReady = false
        private set

    override val analyzerType = AnalyzerType.LOCAL

    var compiledModel: CompiledModel? = null
    private val inputWidth  = 224
    private val inputHeight = 224

    init {
        try {
            val modelPath = getModelFilePath(context, "nsfw.tflite")
            if (modelPath != null) {
                Log.d("GhadhooAI", "✅ ملف الموديل موجود في: $modelPath")
                compiledModel = CompiledModel.create(
                    modelPath, CompiledModel.Options(Accelerator.CPU)
                )
                isReady = true
                Log.d("GhadhooAI", "✅ CompiledModel تم تهيئته بنجاح")
            } else {
                Log.e("GhadhooAI", "❌ الموديل غير موجود في assets!")
            }
        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ خطأ في تهيئة CompiledModel: ${e.message}")
        }
    }

    // ── تحليل Bitmap (مطلوب من ContentAnalyzer interface) ───────────────────
    override suspend fun analyze(bitmap: Bitmap): AnalysisResult = withContext(Dispatchers.Default) {
        if (!isReady || compiledModel == null) return@withContext AnalysisResult(false)

        var scaled: Bitmap? = null
        return@withContext try {
            scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val score = runInference(scaled)
            Log.d("GhadhooAI", "📊 SFW=${"%.1f".format((1 - score) * 100)}% | NSFW=${"%.1f".format(score * 100)}%")

            val isUnsafe = score > 0.20f
            if (isUnsafe) Log.w("GhadhooAI", "🚨 محتوى غير آمن!")
            else Log.d("GhadhooAI", "✅ المحتوى آمن")

            AnalysisResult(isUnsafe = isUnsafe, regions = null)
        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ خطأ أثناء التحليل: ${e.message}")
            AnalysisResult(false)
        } finally {
            scaled?.recycle()
        }
    }

    // ── تحويل Image الخام من ImageReader إلى Bitmap ──────────────────────────
    fun imageToBitmap(image: Image): Bitmap? {
        return try {
            if (image.planes.isEmpty()) return null
            val plane       = image.planes[0]
            val buffer      = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride   = plane.rowStride
            val w = image.width
            val h = image.height

            val bitmap  = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val rowData = ByteArray(rowStride)
            val pixels  = IntArray(w * h)
            var idx = 0

            for (row in 0 until h) {
                buffer.position(row * rowStride)
                buffer.get(rowData, 0, minOf(rowStride, buffer.remaining()))
                for (col in 0 until w) {
                    val p = col * pixelStride
                    val r = rowData[p].toInt()     and 0xFF
                    val g = rowData[p + 1].toInt() and 0xFF
                    val b = rowData[p + 2].toInt() and 0xFF
                    val a = if (pixelStride >= 4) rowData[p + 3].toInt() and 0xFF else 255
                    pixels[idx++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            bitmap
        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ فشل imageToBitmap: ${e.message}")
            null
        }
    }

    private fun runInference(bitmap: Bitmap): Float {
        val inputBuffers  = compiledModel!!.createInputBuffers()
        val outputBuffers = compiledModel!!.createOutputBuffers()

        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val floatValues = FloatArray(inputWidth * inputHeight * 3)
        var idx = 0
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF).toFloat()
            val g = ((pixel shr 8)  and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()
            // Yahoo open_nsfw: BGR + Mean Subtraction
            floatValues[idx++] = b - 104.0f
            floatValues[idx++] = g - 117.0f
            floatValues[idx++] = r - 123.0f
        }
        inputBuffers[0].writeFloat(floatValues)
        compiledModel!!.run(inputBuffers, outputBuffers)
        return outputBuffers[0].readFloat()[1] // Index 1 = NSFW
    }

    private fun getModelFilePath(context: Context, modelName: String): String? {
        val file = File(context.cacheDir, modelName)
        if (file.exists()) {
            Log.d("GhadhooAI", "📁 الموديل موجود في الكاش: ${file.absolutePath}")
            return file.absolutePath
        }
        return try {
            Log.d("GhadhooAI", "📁 نسخ الموديل من assets...")
            context.assets.open(modelName).use { input ->
                FileOutputStream(file).use { output ->
                    val buf = ByteArray(4 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) output.write(buf, 0, n)
                }
            }
            Log.d("GhadhooAI", "✅ تم نسخ الموديل: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ فشل نسخ الموديل: ${e.message}")
            null
        }
    }

   override  fun close() {
        compiledModel = null
        isReady = false
    }
}