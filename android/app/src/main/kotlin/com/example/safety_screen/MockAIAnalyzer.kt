package com.example.safety_screen

import android.media.Image
import android.util.Log

class MockAIAnalyzer {

    fun analyzeImage(image: Image): Boolean {
        // Simplified mock analysis: 
        // We will sample a few pixels to determine if the screen is too bright
        val planes = image.planes
        if (planes.isEmpty()) return false
        
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        
        val width = image.width
        val height = image.height

        var brightPixelCount = 0
        var totalSampled = 0

        // Sample pixels in the center area
        val startX = width / 4
        val endX = width * 3 / 4
        val startY = height / 4
        val endY = height * 3 / 4

        val stepX = maxOf(1, (endX - startX) / 10)
        val stepY = maxOf(1, (endY - startY) / 10)

        try {
            for (y in startY until endY step stepY) {
                for (x in startX until endX step stepX) {
                    val index = y * rowStride + x * pixelStride
                    if (index + 3 < buffer.capacity()) {
                        val r = buffer.get(index).toInt() and 0xFF
                        val g = buffer.get(index + 1).toInt() and 0xFF
                        val b = buffer.get(index + 2).toInt() and 0xFF
                        
                        // Calculate brightness (luma)
                        val luma = (0.299 * r + 0.587 * g + 0.114 * b)
                        if (luma > 200) { // arbitrary high brightness threshold
                            brightPixelCount++
                        }
                        totalSampled++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SafeScreen", "Error analyzing image", e)
            return false
        }

        // If more than 30% of sampled pixels are very bright, treat as "UNSAFE"
        val isUnsafe = totalSampled > 0 && (brightPixelCount.toFloat() / totalSampled) > 0.3f
        Log.d("SafeScreen", "Analysis complete: bright=$brightPixelCount/$totalSampled unsafe=$isUnsafe")
        return isUnsafe
    }
}
