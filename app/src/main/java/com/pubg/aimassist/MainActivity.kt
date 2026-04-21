package com.pubg.aimassist

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val REQUEST_CODE_SCREEN_CAPTURE = 100
    private val REQUEST_CODE_OVERLAY = 101
    private val REQUEST_CODE_ACCESSIBILITY = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQUEST_CODE_OVERLAY)
            } else {
                checkAccessibilityAndStart()
            }
        }

        findViewById<Button>(R.id.btn_open_game).setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage("com.tencent.ig")
            if (intent != null) startActivity(intent) else Toast.makeText(this, "PUBG Mobile not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAccessibilityAndStart() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        if (!isAccessibilityEnabled) {
            Toast.makeText(this, "Please enable Aim Assist accessibility service", Toast.LENGTH_LONG).show()
            startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), REQUEST_CODE_ACCESSIBILITY)
        } else {
            startScreenCapture()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = android.content.ComponentName(this, AimAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(service.flattenToString()) == true
    }

    private fun startScreenCapture() {
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_SCREEN_CAPTURE -> if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
                else startService(serviceIntent)
                Toast.makeText(this, "Aim Assist Active - Auto Headshot", Toast.LENGTH_LONG).show()
                finish()
            }
            REQUEST_CODE_ACCESSIBILITY -> checkAccessibilityAndStart()
            else -> {}
        }
    }
}
