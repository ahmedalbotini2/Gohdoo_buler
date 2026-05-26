package com.ghadhoo_buler

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showOverlay() {
        // ✅ الإصلاح: تشغيل addView على Main Thread دائماً لتجنب الـ CalledFromWrongThreadException
        mainHandler.post {
            if (overlayView == null) {
                overlayView = View(context).apply {
                    setBackgroundColor(Color.BLACK)
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.OPAQUE
                )

                try {
                    windowManager.addView(overlayView, params)
                    println("Ghadhoo: تم إضافة الـ Overlay بنجاح")
                } catch (e: Exception) {
                    println("Ghadhoo: فشل إضافة الـ Overlay: ${e.message}")
                    overlayView = null // ✅ إعادة التعيين عند الفشل لإتاحة المحاولة مرة أخرى
                }
            }
        }
    }

    fun removeOverlay() {
        mainHandler.post {
            overlayView?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    println("Ghadhoo: فشل إزالة الـ Overlay: ${e.message}")
                } finally {
                    // ✅ تعيين null دائماً سواء نجح الحذف أم لا
                    overlayView = null
                }
            }
        }
    }
}