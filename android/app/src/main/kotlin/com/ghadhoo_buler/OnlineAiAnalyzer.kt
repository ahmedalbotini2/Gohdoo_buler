package com.ghadhoo_buler

import android.graphics.Bitmap
import android.graphics.RectF

// ── نتيجة التحليل ─────────────────────────────────────────────────────────────
data class AnalysisResult(
    val isUnsafe:   Boolean,
    // ✅ تعديل: استخدام RegionResult بدل NsfwRegion لتوافق BackendApiAi
    // null = حجب كامل الشاشة، وإلا قائمة مناطق محددة
    val regions:    List<RegionResult>? = null,
    // ✅ إضافة: confidence وreason قادمان من BackendApiAi
    val confidence: Int                 = 0,
    val reason:     String              = ""
)

// ✅ تعديل: توحيد NsfwRegion مع RegionResult الموجود في BackendApiAi
// bounds = RectF(left, top, right, bottom) بنطاق 0.0–1.0
// هذا أسهل للرسم على Canvas من (x, y, width, height)
data class RegionResult(
    val label:      String,
    val bounds:     RectF,
    // ✅ إضافة: confidence خاص بالمنطقة — مفيد لاحقاً لتحديد شدة التغطية
    val confidence: Float  = 1.0f
) {
    // دوال مساعدة للوصول السريع للإحداثيات
    val left:   Float get() = bounds.left
    val top:    Float get() = bounds.top
    val right:  Float get() = bounds.right
    val bottom: Float get() = bounds.bottom

    // تحويل لـ (x, y, width, height) إذا احتجته
    val x:      Float get() = bounds.left
    val y:      Float get() = bounds.top
    val width:  Float get() = bounds.width()
    val height: Float get() = bounds.height()
}

// ── الواجهة المشتركة لكل المحللين ────────────────────────────────────────────
interface ContentAnalyzer {

    val isReady:      Boolean
    val analyzerType: AnalyzerType

    suspend fun analyze(bitmap: Bitmap): AnalysisResult

    fun close() {}
}

// ✅ تعديل: إضافة تعليق يوضح سلوك كل نوع في OverlayManager
enum class AnalyzerType {
    LOCAL,  // حجب كامل الشاشة — يستخدمه LocalAiAnalyzer وQuantizedAiAnalyzer
    CLOUD   // حجب دقيق على المناطق فقط — يستخدمه BackendApiAi
}