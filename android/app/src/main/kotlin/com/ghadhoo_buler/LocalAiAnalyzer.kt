package com.ghadhoo_buler

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import java.io.FileOutputStream

class LocalAiAnalyzer(context: Context) {

    private var compiledModel: CompiledModel? = null
    private var isInitialized = false

    private val inputWidth = 224
    private val inputHeight = 224

    init {
        try {
            val modelPath = getModelFilePath(context, "nsfw.tflite")

            if (modelPath != null) {
                Log.d("GhadhooAI", "✅ ملف الموديل موجود في: $modelPath")
                compiledModel = CompiledModel.create(
                    modelPath,
                    CompiledModel.Options(Accelerator.CPU)
                )
                isInitialized = true
                Log.d("GhadhooAI", "✅ CompiledModel تم تهيئته بنجاح")
            } else {
                Log.e("GhadhooAI", "❌ الموديل غير موجود في assets!")
            }
        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ خطأ في تهيئة CompiledModel: ${e.message}")
        }
    }

    fun analyzeImage(image: Image): Boolean {
        if (!isInitialized || compiledModel == null) {
            Log.w("GhadhooAI", "⚠️ الموديل غير مهيأ → تخطي التحليل")
            return false
        }

        var rawBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null

        return try {
            rawBitmap = imageToBitmap(image) ?: run {
                Log.w("GhadhooAI", "⚠️ فشل تحويل Image إلى Bitmap")
                return false
            }

            scaledBitmap = Bitmap.createScaledBitmap(rawBitmap, inputWidth, inputHeight, true)

            val inputBuffers = compiledModel!!.createInputBuffers()
            val outputBuffers = compiledModel!!.createOutputBuffers()

            val pixels = IntArray(inputWidth * inputHeight)
            scaledBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

            // ✅ الإصلاح الجوهري: موديل Yahoo open_nsfw يتوقع:
            // 1. ترتيب BGR وليس RGB
            // 2. Mean Subtraction وليس تطبيع 0-1
            //    القيم المطروحة هي متوسطات ImageNet القياسية
            val floatValues = FloatArray(inputWidth * inputHeight * 3)
            var idx = 0
            for (pixel in pixels) {
                val r = ((pixel shr 16) and 0xFF).toFloat()
                val g = ((pixel shr 8) and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()

                floatValues[idx++] = b - 104.0f  // B أولاً
                floatValues[idx++] = g - 117.0f  // G ثانياً
                floatValues[idx++] = r - 123.0f  // R أخيراً
            }
            inputBuffers[0].writeFloat(floatValues)

            compiledModel!!.run(inputBuffers, outputBuffers)

            // Yahoo open_nsfw: Index 0 = SFW، Index 1 = NSFW
            val outputValues = outputBuffers[0].readFloat()
            val sfwScore  = outputValues[0] * 100f
            val nsfwScore = outputValues[1] * 100f

            Log.d("GhadhooAI", "📊 SFW=${"%.1f".format(sfwScore)}% | NSFW=${"%.1f".format(nsfwScore)}%")

            // ✅ threshold 80% كما يوصي مشروع open_nsfw الأصلي
            // ✅ threshold منخفض للتشدد في الحجب (ملابس داخلية، شعر مكشوف، عري جزئي)
            val isUnsafe = nsfwScore > 20.0f
            if (isUnsafe) Log.w("GhadhooAI", "🚨 محتوى غير آمن!")
            else Log.d("GhadhooAI", "✅ المحتوى آمن")

            isUnsafe

        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ خطأ أثناء التحليل: ${e.message}")
            false
        } finally {
            rawBitmap?.recycle()
            scaledBitmap?.recycle()
        }
    }

    private fun getModelFilePath(context: Context, modelName: String): String? {
        val file = File(context.cacheDir, modelName)
        if (file.exists()) {
            Log.d("GhadhooAI", "📁 الموديل موجود في الكاش: ${file.absolutePath}")
            return file.absolutePath
        }

        return try {
            Log.d("GhadhooAI", "📁 نسخ الموديل من assets...")
            context.assets.open(modelName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    val buffer = ByteArray(4 * 1024)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }
            }
            Log.d("GhadhooAI", "✅ تم نسخ الموديل: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ فشل نسخ الموديل: ${e.message}")
            null
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            if (image.planes.isEmpty()) return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } catch (e: Exception) {
            Log.e("GhadhooAI", "❌ فشل imageToBitmap: ${e.message}")
            null
        }
    }

    fun close() {
        compiledModel = null
        isInitialized = false
    }
}