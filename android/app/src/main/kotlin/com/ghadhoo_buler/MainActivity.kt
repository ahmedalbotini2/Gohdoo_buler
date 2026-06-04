package com.ghadhoo_buler

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val OVERLAY_PERMISSION_REQ_CODE     = 1000
    private val MEDIA_PROJECTION_REQ_CODE       = 1001
    private val NOTIFICATION_PERMISSION_REQ_CODE = 1002
    private var pendingResult: MethodChannel.Result? = null

    companion object {
        const val CHANNEL = "com.ghadhoo_buler/screen_monitor"
        var methodChannel: MethodChannel? = null
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)

        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "startMonitoring" -> startMonitoringFlow(result)
                "stopMonitoring"  -> { stopMonitoring(); result.success(null) }
                "isMonitoring"    -> result.success(ScreenMonitorService.isMonitoring)

                // Android 12+: ضبط شدة الـ blur
                "setBlurRadius" -> {
                    val radius = call.argument<Double>("radius")?.toFloat() ?: 15f
                    ScreenMonitorService.setBlurRadius(radius)
                    result.success(null)
                }

                // Android 11-: ضبط لون الحجب (يُرسَل كـ int من Flutter)
                "setOverlayColor" -> {
                    val colorInt = (call.argument<Long>("color") ?: Color.BLACK.toLong()).toInt()
                    ScreenMonitorService.setOverlayColor(colorInt)
                    result.success(null)
                }

                // Flutter يستعلم عن إصدار Android لعرض الـ UI المناسب
                "getAndroidVersion" -> result.success(Build.VERSION.SDK_INT)

                else -> result.notImplemented()
            }
        }
    }

    private fun startMonitoringFlow(result: MethodChannel.Result) {
        this.pendingResult = result

        // ✅ المشكلة ٣: طلب إذن الإشعارات أولاً في Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQ_CODE
                )
                return // ننتظر نتيجة الإذن — سيُكمل في onRequestPermissionsResult
            }
        }
        requestOverlayPermission()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
        } else {
            requestMediaProjection()
        }
    }

    // ✅ نتيجة طلب إذن الإشعارات
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQ_CODE) {
            // سواء وافق أو رفض نكمل — الإشعار قد لا يظهر لكن الخدمة تعمل
            requestOverlayPermission()
        }
    }

    private fun requestMediaProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), MEDIA_PROJECTION_REQ_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION_REQ_CODE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this))
                    requestMediaProjection()
                else {
                    pendingResult?.error("PERMISSION_DENIED", "Overlay permission denied", null)
                    pendingResult = null
                }
            }
            MEDIA_PROJECTION_REQ_CODE -> {
                if (resultCode == RESULT_OK && data != null) {
                    startMonitorService(resultCode, data)
                    pendingResult?.success(null)
                } else {
                    pendingResult?.error("PERMISSION_DENIED", "Media Projection denied", null)
                }
                pendingResult = null
            }
        }
    }

    private fun startMonitorService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenMonitorService::class.java).apply {
            action = ScreenMonitorService.ACTION_START
            putExtra(ScreenMonitorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenMonitorService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopMonitoring() {
        startService(Intent(this, ScreenMonitorService::class.java).apply {
            action = ScreenMonitorService.ACTION_STOP
        })
    }
}