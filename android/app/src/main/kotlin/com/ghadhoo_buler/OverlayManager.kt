package com.ghadhoo_buler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var blurLayerView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var blurRadius: Float = 22f

    fun show(screenshot: Bitmap, result: AnalysisResult, analyzerType: AnalyzerType) {
        showBlurLayer()
    }

    fun showBlurLayer() {
        mainHandler.post {
            if (blurLayerView != null) return@post

            val screenW = getScreenWidth()
            val screenH = getScreenHeight()

            blurLayerView = View(context).apply {
                // شفاف تماماً — الـ Blur يأتي من الـ WindowManager وليس من الـ View
                setBackgroundColor(Color.TRANSPARENT)
            }

            val params = WindowManager.LayoutParams(
                screenW, screenH,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                // ✅ FLAG_BLUR_BEHIND يضبب كل ما خلف النافذة
                // ✅ FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL يمرران اللمس والسكرول للتطبيق تحته
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0

                // ✅ تحديد شدة الـ Blur لكل ما خلف النافذة (Android 12+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    blurBehindRadius = blurRadius.toInt().coerceIn(1, 150)
                }
            }

            try {
                windowManager.addView(blurLayerView, params)
                Log.d("Ghadhoo", "✅ طبقة Blur Behind أُضيفت $screenW×$screenH")
            } catch (e: Exception) {
                Log.e("Ghadhoo", "❌ فشل إضافة Overlay: ${e.message}")
                blurLayerView = null
            }
        }
    }

    fun removeOverlay() {
        mainHandler.post {
            blurLayerView?.let {
                try { windowManager.removeView(it) }
                catch (_: Exception) {}
                finally { blurLayerView = null }
            }
        }
    }

    fun updateBlurRadius(radius: Float) {
        blurRadius = radius.coerceIn(1f, 25f)
        // تحديث الـ Blur إذا كانت الطبقة ظاهرة حالياً
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mainHandler.post {
                blurLayerView?.let { view ->
                    try {
                        val params = view.layoutParams as WindowManager.LayoutParams
                        params.blurBehindRadius = blurRadius.toInt().coerceIn(1, 150)
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun getScreenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            windowManager.currentWindowMetrics.bounds.width()
        else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
            m.widthPixels
        }

    private fun getScreenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            windowManager.currentWindowMetrics.bounds.height()
        else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
            m.heightPixels
        }
}