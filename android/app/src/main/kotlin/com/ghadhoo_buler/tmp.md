/*package com.ghadhoo_buler

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ✅ جديد: نتذكر آخر المناطق المعروضة حتى نقدر نعيد بناءها عند تغيير
    // اللون/شدة البلور بدون الحاجة لنتيجة تحليل جديدة
    private var currentRegions: List<RegionResult>? = null

    val isOverlayVisible: Boolean get() = overlayView != null

    var overlayColor: Int = Color.BLACK
        set(value) { field = value; mainHandler.post { applyColorToCurrentOverlay() } }

    var blurRadius: Float = 15f
        set(value) {
            field = value.coerceIn(1f, 25f)
            mainHandler.post { applyBlurToCurrentOverlay() }
        }

    private val marginPx: Int get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
    ).toInt()

    private val cornerPx: Float get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 12f, context.resources.displayMetrics
    )

    // ✅ هامش بسيط حول كل منطقة مكتشفة (بالـ dp) لتفادي قص حدود العنصر تماماً
    private val regionPaddingPx: Int get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 6f, context.resources.displayMetrics
    ).toInt()

    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    // ── showOverlay ──────────────────────────────────────────────────────────
    // regions == null أو فاضية → يغطي الشاشة بالكامل (السلوك الافتراضي القديم،
    // يُستخدم مع المحلل المحلي الذي لا يحدد مواقع).
    // regions غير فاضية → يحجب فقط تلك المناطق بدقة (يُستخدم مع المحلل السحابي).
    fun showOverlay(regions: List<RegionResult>? = null) {
        mainHandler.post {
            currentRegions = regions

            // إذا كان هناك overlay معروض بالفعل، أعد بناءه بالمناطق الجديدة
            // بدل إضافة view مكرر فوق الآخر
            if (overlayView != null) {
                rebuildOverlayViews()
                return@post
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                baseFlags,
                PixelFormat.TRANSLUCENT
            )
            overlayParams = params

            val container = FrameLayout(context)
            overlayView = container

            try {
                windowManager.addView(container, params)
                buildOverlayContent(container)
                Log.d("Ghadhoo", "✅ Overlay أُضيف (مناطق محددة=${regions?.size ?: 0})")
            } catch (e: Exception) {
                Log.e("Ghadhoo", "❌ addView: ${e.message}")
                overlayView = null; overlayParams = null
            }
        }
    }

    private fun rebuildOverlayViews() {
        val container = overlayView as? FrameLayout ?: return
        container.removeAllViews()
        buildOverlayContent(container)
    }

    // ── يبني محتوى الـ overlay اعتماداً على وجود مناطق محددة أم لا ──────────
    private fun buildOverlayContent(container: FrameLayout) {
        val regions = currentRegions
        if (regions != null && regions.isNotEmpty()) {
            buildRegionOverlays(container, regions)
        } else {
            buildFullScreenOverlay(container)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // حجب مناطق محددة فقط (Android 12+: بلور حقيقي خلف كل منطقة عبر
    // FLAG_BLUR_BEHIND على نافذة كاملة + قص بصري بواسطة view مموّه محلي
    // كحل أبسط ومتوافق مع كل الإصدارات: نستخدم لون/تمويه صلب على نفس
    // أبعاد المنطقة فقط بدل الشاشة كاملة)
    // ════════════════════════════════════════════════════════════════════
    private fun buildRegionOverlays(container: FrameLayout, regions: List<RegionResult>) {
        val (screenW, screenH) = screenSize()
        val pad = regionPaddingPx

        for (region in regions) {
            val r: RectF = region.bounds // نسب 0f..1f (left, top, right, bottom)

            val leftPx   = (r.left   * screenW).toInt() - pad
            val topPx    = (r.top    * screenH).toInt() - pad
            val rightPx  = (r.right  * screenW).toInt() + pad
            val bottomPx = (r.bottom * screenH).toInt() + pad

            val w = (rightPx - leftPx).coerceAtLeast(1)
            val h = (bottomPx - topPx).coerceAtLeast(1)

            val regionView = View(context).apply {
                background = GradientDrawable().apply {
                    shape        = GradientDrawable.RECTANGLE
                    cornerRadius = cornerPx
                    setColor(overlayColor)
                }
            }

            val lp = FrameLayout.LayoutParams(w, h).apply {
                leftMargin = leftPx.coerceIn(0, screenW)
                topMargin  = topPx.coerceIn(0, screenH)
                gravity    = Gravity.TOP or Gravity.START
            }

            container.addView(regionView, lp)
        }
    }

    // ── السلوك القديم: تغطية الشاشة بالكامل (للمحلل المحلي بدون مناطق) ─────
    private fun buildFullScreenOverlay(container: FrameLayout) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            container.setBackgroundColor(0x55000000.toInt())
            applyBlurFlagToWindow()
        } else {
            buildColorOverlay(container)
        }
    }

    // Android 12+: نفعّل FLAG_BLUR_BEHIND على مستوى النافذة كلها فقط في
    // حالة التغطية الكاملة (لا يصلح هذا الفلاغ لتمويه مناطق جزئية فقط)
    private fun applyBlurFlagToWindow() {
        val params = overlayParams ?: return
        val view   = overlayView   ?: return
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        params.blurBehindRadius = (blurRadius * 6).toInt().coerceIn(1, 150)
        try { windowManager.updateViewLayout(view, params) }
        catch (e: Exception) { Log.e("Ghadhoo", "❌ updateViewLayout(blur): ${e.message}") }
    }

    private fun removeBlurFlagFromWindow() {
        val params = overlayParams ?: return
        val view   = overlayView   ?: return
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        try { windowManager.updateViewLayout(view, params) }
        catch (e: Exception) { Log.e("Ghadhoo", "❌ updateViewLayout(unblur): ${e.message}") }
    }

    // ── Android 11-: لون + هامش + حواف (تغطية كاملة فقط) ────────────────────
    private fun buildColorOverlay(container: FrameLayout) {
        container.setBackgroundColor(Color.TRANSPARENT)
        val shape = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, context.resources.displayMetrics
            )
            setColor(overlayColor)
        }
        val coloredView = View(context).apply { background = shape }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            val m = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
            ).toInt()
            setMargins(m, m, m, m); gravity = Gravity.CENTER
        }
        container.addView(coloredView, lp)
    }

    private fun applyColorToCurrentOverlay() {
        // يُطبَّق على كل عناصر الحجب الحالية (مناطق أو شاشة كاملة) بإعادة بنائها
        if (overlayView == null) return
        rebuildOverlayViews()
    }

    private fun applyBlurToCurrentOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val regions = currentRegions
        // البلور الكامل (FLAG_BLUR_BEHIND) يُحدَّث فقط في حالة التغطية الكاملة
        if (regions == null || regions.isEmpty()) {
            val params = overlayParams ?: return
            val view   = overlayView   ?: return
            params.blurBehindRadius = (blurRadius * 6).toInt().coerceIn(1, 150)
            try { windowManager.updateViewLayout(view, params) }
            catch (e: Exception) { Log.e("Ghadhoo", "❌ updateViewLayout: ${e.message}") }
        }
        // في حالة المناطق المحددة لا حاجة لإعادة شيء، لأنها تستخدم overlayColor فقط
        // (التمويه الجزئي الحقيقي لمناطق صغيرة عبر BackdropFilter غير مدعوم على
        // مستوى WindowManager بدون تعقيد إضافي؛ نستخدم لوناً صلباً بدله حالياً)
    }

    // ── removeOverlay ────────────────────────────────────────────────────────
    fun removeOverlay() {
        mainHandler.post {
            overlayView?.let { view ->
                try { windowManager.removeView(view) }
                catch (e: Exception) { Log.e("Ghadhoo", "❌ removeView: ${e.message}") }
                finally {
                    overlayView = null
                    overlayParams = null
                    currentRegions = null
                }
            }
        }
    }
}*/
