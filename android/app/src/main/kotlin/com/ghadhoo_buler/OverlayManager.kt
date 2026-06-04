package com.ghadhoo_buler

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        TypedValue.COMPLEX_UNIT_DIP, 24f, context.resources.displayMetrics
    )

    // ── showOverlay ──────────────────────────────────────────────────────────
    fun showOverlay() {
        mainHandler.post {
            if (overlayView != null) {
                overlayView?.visibility = View.VISIBLE
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // ════════════════════════════════════════════════════
                // Android 12+: FLAG_BLUR_BEHIND + blurBehindRadius
                // ════════════════════════════════════════════════════
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    baseFlags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                    PixelFormat.TRANSLUCENT
                )
                params.blurBehindRadius = (blurRadius * 6).toInt().coerceIn(1, 150)
                overlayParams = params

                val container = FrameLayout(context).apply {
                    setBackgroundColor(0x55000000.toInt())
                }
                overlayView = container

                try {
                    windowManager.addView(container, params)
                    Log.d("Ghadhoo", "✅ [A12] Overlay blur=${params.blurBehindRadius}")
                } catch (e: Exception) {
                    Log.e("Ghadhoo", "❌ [A12] addView: ${e.message}")
                    overlayView = null; overlayParams = null
                }

            } else {
                // ════════════════════════════════════════════════════
                // Android 11-: لون صلب + هامش + حواف مدوّرة
                // ════════════════════════════════════════════════════
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    baseFlags,
                    PixelFormat.TRANSLUCENT
                )
                overlayParams = params

                val container = FrameLayout(context)
                buildColorOverlay(container)
                overlayView = container

                try {
                    windowManager.addView(container, params)
                    Log.d("Ghadhoo", "✅ [A11] Overlay color=$overlayColor")
                } catch (e: Exception) {
                    Log.e("Ghadhoo", "❌ [A11] addView: ${e.message}")
                    overlayView = null; overlayParams = null
                }
            }
        }
    }

    // ── Android 11-: لون + هامش + حواف ─────────────────────────────────────
    private fun buildColorOverlay(container: FrameLayout) {
        container.setBackgroundColor(Color.TRANSPARENT)
        val shape = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            setColor(overlayColor)
        }
        val coloredView = View(context).apply { background = shape }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { val m = marginPx; setMargins(m, m, m, m); gravity = Gravity.CENTER }
        container.addView(coloredView, lp)
    }

    private fun applyColorToCurrentOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        val container = overlayView as? FrameLayout ?: return
        container.removeAllViews()
        buildColorOverlay(container)
    }

    private fun applyBlurToCurrentOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val params = overlayParams ?: return
        val view   = overlayView   ?: return
        params.blurBehindRadius = (blurRadius * 6).toInt().coerceIn(1, 150)
        try { windowManager.updateViewLayout(view, params) }
        catch (e: Exception) { Log.e("Ghadhoo", "❌ updateViewLayout: ${e.message}") }
    }

    // ── removeOverlay ────────────────────────────────────────────────────────
    fun removeOverlay() {
        mainHandler.post {
            overlayView?.let { view ->
                try { windowManager.removeView(view) }
                catch (e: Exception) { Log.e("Ghadhoo", "❌ removeView: ${e.message}") }
                finally { overlayView = null; overlayParams = null }
            }
        }
    }
}