package com.ghadhoo_buler

import android.graphics.Bitmap

// ── نتيجة التحليل ─────────────────────────────────────────────────────────────
data class AnalysisResult(
    val isUnsafe: Boolean,
    // null = حجب كامل الشاشة ، وإلا قائمة مناطق (x, y, width, height) بالنسب 0.0-1.0
    val regions: List<NsfwRegion>? = null
)

data class NsfwRegion(
    val x: Float,       // نسبة من عرض الشاشة (0.0 - 1.0)
    val y: Float,       // نسبة من ارتفاع الشاشة (0.0 - 1.0)
    val width: Float,
    val height: Float,
    val confidence: Float
)

// ── الواجهة المشتركة لكل المحللين ────────────────────────────────────────────
interface ContentAnalyzer {

    // هل المحلل جاهز للعمل؟
    val isReady: Boolean

    // نوع المحلل — يحدد طريقة الحجب في OverlayManager
    val analyzerType: AnalyzerType

    // تحليل الصورة وإرجاع النتيجة
    suspend fun analyze(bitmap: Bitmap): AnalysisResult

    // تحرير الموارد
    fun close() {}
}

enum class AnalyzerType {
    LOCAL,  // Blur كامل الشاشة
    CLOUD   // Blur دقيق على المناطق فقط
}