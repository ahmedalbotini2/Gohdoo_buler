package com.ghadhoo_buler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream


class NSFWAnalyzer(context: Context) {
    private var interpreter: Interpreter? = null

    init {
        try {
            val modelFile = loadModelFile(context, "quantized_model.tflite")
            interpreter = Interpreter(modelFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun analyzeImage(image: Image): Boolean {
        val bitmap = imageToBitmap(image) ?: return false
        
        // استخدام المسار الكامل لمنع التعارض
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(224, 224, org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod.BILINEAR))
            .build()

        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        val output = Array(1) { FloatArray(5) }
        interpreter?.run(tensorImage.buffer, output)

        // الفئات: [Drawings, Hentai, Neutral, Porn, Sexy]
        val hentaiProb = output[0][1]
        val pornProb = output[0][3]
        val sexyProb = output[0][4]

        return pornProb > 0.6f || hentaiProb > 0.6f || sexyProb > 0.7f
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}