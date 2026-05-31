package com.ghadhoo_buler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ImageView? = null 
    private val mainHandler = Handler(Looper.getMainLooper())

    var blurRadius: Float = 22f 

    fun showBlurOverlay(screenshot: Bitmap) {
        mainHandler.post {
            // 1. معالجة وتضبيب الصورة القادمة
            val blurred = gaussianBlur(screenshot) ?: return@post

            if (overlayView == null) {
                // 2. إنشاء الـ ImageView وضبطه بشكل صارم وعنيف ليمط اليمين واليسار
                overlayView = ImageView(context).apply {
                    setPadding(0, 0, 0, 0)
                    // FIT_XY: تقوم بمط البكسلات أفقياً وعمودياً لتملأ كل بكسل فارغ في اليمين واليسار غصباً عن النظام
                    scaleType = ImageView.ScaleType.FIT_XY 
                    setImageBitmap(blurred)
                }

                // 3. جلب الأبعاد الفيزيائية الحقيقية للشاشة بالبكسل لضمان ملء المساحة بالكامل
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val screenWidth = metrics.widthPixels
                val screenHeight = metrics.heightPixels

                // 4. إعدادات لوحة العرض الكاملة القسرية
                val params = WindowManager.LayoutParams(
                    screenWidth,
                    screenHeight,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or        
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or     
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or    
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,      
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.FILL // إجبار النافذة على التمدد الكامل والافتراش
                    x = 0
                    y = 0
                }

                try {
                    windowManager.addView(overlayView, params)
                    Log.d("Ghadhoo", "🛡️ تم تفعيل حجب الشاشة الكاملة القسري الأفقي والعمودي")
                } catch (e: Exception) {
                    Log.e("Ghadhoo", "❌ فشل إضافة الـ Overlay: ${e.message}")
                    overlayView = null 
                }
            } else {
                // تحديث الصورة داخل الـ ImageView المستقر لمنع الوميض
                overlayView?.setImageBitmap(blurred)
            }
        }
    }

    fun removeOverlay() {
        mainHandler.post {
            overlayView?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
                overlayView = null
            }
        }
    }

    private fun gaussianBlur(src: Bitmap): Bitmap? {
        return try {
            val scale  = 0.25f 
            val small  = Bitmap.createScaledBitmap(src, maxOf(1, (src.width * scale).toInt()), maxOf(1, (src.height * scale).toInt()), false)
            
            val rs     = android.renderscript.RenderScript.create(context)
            val input  = android.renderscript.Allocation.createFromBitmap(rs, small)
            val output = android.renderscript.Allocation.createTyped(rs, input.type)
            val script = ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs))
            
            script.setRadius(blurRadius.coerceIn(1f, 25f))
            script.setInput(input)
            script.forEach(output)
            
            val out = Bitmap.createBitmap(small.width, small.height, Bitmap.Config.ARGB_8888)
            output.copyTo(out)
            
            input.destroy(); output.destroy(); script.destroy(); rs.destroy(); small.recycle()
            
            // 🔥 الإصلاح السري هنا: بدلاً من تكبير الصورة لأبعاد اللقطة الأصلية النحيفة (src)، 
            // نقوم بتكبيرها مباشرة لتطابق الأبعاد الفيزيائية الحقيقية للشاشة بالبكسل!
            val metrics = context.resources.displayMetrics
            val result = Bitmap.createScaledBitmap(out, metrics.widthPixels, metrics.heightPixels, false)
            out.recycle()
            
            result
        } catch (e: Exception) {
            null
        }
    }
}