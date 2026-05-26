package com.ghadhoo_buler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class ScreenMonitorService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        var isMonitoring = false
            private set

        // ✅ تحديث شدة الـ Blur من Flutter مباشرة
        private var overlayManagerRef: OverlayManager? = null

        fun setBlurStrength(value: Float) {
            overlayManagerRef?.blurRadius = value.coerceIn(1f, 25f)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayManager: OverlayManager? = null
    private var localAiAnalyzer: LocalAiAnalyzer? = null

    private val handler = Handler(Looper.getMainLooper())
    private val captureIntervalMs = 1500L
    private var isCapturing = false

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                captureFrame()
                handler.postDelayed(this, captureIntervalMs)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultData != null) startMonitoring(resultCode, resultData)
            }
            ACTION_STOP -> stopMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring(resultCode: Int, resultData: Intent) {
        if (isMonitoring) return

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "safescreen_channel")
            .setContentTitle("غدو - الحماية نشطة")
            .setContentText("يتم الآن تحليل محتوى الشاشة محلياً.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        overlayManager = OverlayManager(this)
        overlayManagerRef = overlayManager  // ✅ ربط المرجع للتحكم من Flutter
        localAiAnalyzer = LocalAiAnalyzer(this)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        setupVirtualDisplay()

        isMonitoring = true
        isCapturing = true
        handler.post(captureRunnable)

        Log.d("Ghadhoo", "تم تشغيل خدمة المراقبة بنجاح.")
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }

        val width = 480
        val height = 800
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GhadhooCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }

    private fun captureFrame() {
        val image = imageReader?.acquireLatestImage() ?: return

        try {
            if (image.planes.isEmpty()) return

            val isUnsafe = localAiAnalyzer?.analyzeImage(image) ?: false

            if (isUnsafe) {
                Log.w("Ghadhoo", "محتوى غير آمن! جاري التضبيب...")

                // ✅ تحويل الـ Image إلى Bitmap وتمريره للـ OverlayManager للـ Blur
                val screenshot = imageToBitmap(image)
                if (screenshot != null) {
                    overlayManager?.showBlurOverlay(screenshot)
                }
            } else {
                overlayManager?.removeOverlay()
            }

        } catch (e: Exception) {
            Log.e("Ghadhoo", "خطأ أثناء التحليل: ${e.message}")
        } finally {
            image.close()
        }
    }

    // تحويل Image إلى Bitmap مع معالجة الـ Row Padding بشكل صحيح
    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height

            // ✅ الإصلاح: إنشاء Bitmap بالأبعاد الصحيحة بدون padding
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // نسخ البيانات صف بصف مع تخطي الـ padding في نهاية كل صف
            val rowData = ByteArray(rowStride)
            val pixels = IntArray(width * height)
            var pixelIndex = 0

            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(rowData, 0, minOf(rowStride, buffer.remaining()))

                for (col in 0 until width) {
                    val byteIndex = col * pixelStride
                    val r = rowData[byteIndex].toInt() and 0xFF
                    val g = rowData[byteIndex + 1].toInt() and 0xFF
                    val b = rowData[byteIndex + 2].toInt() and 0xFF
                    val a = if (pixelStride >= 4) rowData[byteIndex + 3].toInt() and 0xFF else 255
                    pixels[pixelIndex++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            Log.e("Ghadhoo", "فشل تحويل Image: ${e.message}")
            null
        }
    }

    private fun stopMonitoring() {
        isCapturing = false
        isMonitoring = false
        handler.removeCallbacks(captureRunnable)

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        overlayManager?.removeOverlay()
        overlayManagerRef = null
        localAiAnalyzer?.close()
        localAiAnalyzer = null

        stopForeground(true)
        stopSelf()
        Log.d("Ghadhoo", "تم إيقاف الخدمة وتحرير جميع الموارد.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "safescreen_channel",
                "Ghadhoo Protection Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
    }
}