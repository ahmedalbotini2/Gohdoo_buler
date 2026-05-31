package com.ghadhoo_buler

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val OVERLAY_PERMISSION_REQ_CODE = 1000
    private val MEDIA_PROJECTION_REQ_CODE = 1001
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
                "stopMonitoring" -> {
                    stopMonitoring()
                    result.success(null)
                }
                "isMonitoring" -> result.success(ScreenMonitorService.isMonitoring)
                "setBlurStrength" -> {
                    val value = call.argument<Double>("value")?.toFloat() ?: 20f
                  //  ScreenMonitorService.setBlurStrength(value)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun startMonitoringFlow(result: MethodChannel.Result) {
        this.pendingResult = result
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

    private fun requestMediaProjection() {
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            MEDIA_PROJECTION_REQ_CODE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            OVERLAY_PERMISSION_REQ_CODE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    requestMediaProjection()
                } else {
                    pendingResult?.error("PERMISSION_DENIED", "Overlay permission denied", null)
                    pendingResult = null
                }
            }
            MEDIA_PROJECTION_REQ_CODE -> {
                if (resultCode == RESULT_OK && data != null) {
                    startMonitorService(resultCode, data)
                    pendingResult?.success(null)
                } else {
                    pendingResult?.error("PERMISSION_DENIED", "Media Projection permission denied", null)
                }
                pendingResult = null
            }
        }
    }

    private fun startMonitorService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenMonitorService::class.java).apply {
            action = ScreenMonitorService.ACTION_START
            putExtra(ScreenMonitorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenMonitorService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopMonitoring() {
        val serviceIntent = Intent(this, ScreenMonitorService::class.java).apply {
            action = ScreenMonitorService.ACTION_STOP
        }
        startService(serviceIntent)
    }
}