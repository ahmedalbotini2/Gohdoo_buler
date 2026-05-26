package com.ghadhoo_buler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.WindowManager
import android.widget.ImageView
import android.util.Log

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayImageView: ImageView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // شدة الـ Blur — قابلة للتغيير من Flutter لاحقاً (1f - 25f)
    var blurRadius: Float = 20f

    fun showBlurOverlay(screenshot: Bitmap) {
        mainHandler.post {
            val blurred = applyBlur(screenshot) ?: return@post

            if (overlayImageView == null) {
                // أول مرة: إنشاء الـ ImageView وإضافته للـ WindowManager
                overlayImageView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    setImageBitmap(blurred)
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
                    PixelFormat.TRANSLUCENT
                )

                try {
                    windowManager.addView(overlayImageView, params)
                    Log.d("Ghadhoo", "✅ Blur Overlay أُضيف بنجاح")
                } catch (e: Exception) {
                    Log.e("Ghadhoo", "❌ فشل إضافة Blur Overlay: ${e.message}")
                    overlayImageView = null
                }
            } else {
                // تحديث الصورة المضببة في كل فريم
                overlayImageView?.setImageBitmap(blurred)
            }
        }
    }

    fun removeOverlay() {
        mainHandler.post {
            overlayImageView?.let { view ->
                try {
                    windowManager.removeView(view)
                    Log.d("Ghadhoo", "✅ Blur Overlay أُزيل")
                } catch (e: Exception) {
                    Log.e("Ghadhoo", "❌ فشل إزالة Overlay: ${e.message}")
                } finally {
                    overlayImageView = null
                }
            }
        }
    }

    // تطبيق الـ Blur على الـ Bitmap باستخدام RenderScript
    private fun applyBlur(original: Bitmap): Bitmap? {
        return try {
            val rs = RenderScript.create(context)

            // تصغير الصورة أولاً لتسريع الـ Blur وتقليل استهلاك الرام
            val scale = 0.3f
            val smallBitmap = Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt(),
                (original.height * scale).toInt(),
                false
            )

            // تطبيق Gaussian Blur عبر RenderScript
            val input = Allocation.createFromBitmap(rs, smallBitmap)
            val output = Allocation.createTyped(rs, input.type)
            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            script.setRadius(blurRadius.coerceIn(1f, 25f))
            script.setInput(input)
            script.forEach(output)

            val blurredBitmap = Bitmap.createBitmap(
                smallBitmap.width,
                smallBitmap.height,
                Bitmap.Config.ARGB_8888
            )
            output.copyTo(blurredBitmap)

            // تكبير الصورة مرة أخرى لتغطية الشاشة كاملة
            val finalBitmap = Bitmap.createScaledBitmap(
                blurredBitmap,
                original.width,
                original.height,
                false
            )

            // تحرير الموارد
            input.destroy()
            output.destroy()
            script.destroy()
            rs.destroy()
            smallBitmap.recycle()
            blurredBitmap.recycle()

            finalBitmap
        } catch (e: Exception) {
            Log.e("Ghadhoo", "❌ فشل تطبيق Blur: ${e.message}")
            null
        }
    }
}