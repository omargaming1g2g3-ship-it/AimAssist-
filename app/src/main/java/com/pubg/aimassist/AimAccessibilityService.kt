package com.pubg.aimassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class AimAccessibilityService : AccessibilityService() {
    companion object {
        private var targetX = -1
        private var targetY = -1
        private var lastTapTime = 0L
        private const val TAP_DELAY_MS = 100L

        fun setTarget(x: Int, y: Int) {
            targetX = x
            targetY = y
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isServiceActive = true
    private val tapRunnable = object : Runnable {
        override fun run() {
            if (!isServiceActive) return
            if (targetX != -1 && targetY != -1) {
                val now = System.currentTimeMillis()
                if (now - lastTapTime >= TAP_DELAY_MS) {
                    performTap(targetX, targetY)
                    lastTapTime = now
                }
            }
            handler.postDelayed(this, 50)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    override fun onInterrupt() { }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceActive = true
        handler.postDelayed(tapRunnable, 100)
    }

    override fun onDestroy() {
        isServiceActive = false
        handler.removeCallbacks(tapRunnable)
        super.onDestroy()
    }

    private fun performTap(x: Int, y: Int) {
        try {
            val path = Path()
            path.moveTo(x.toFloat(), y.toFloat())
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            dispatchGesture(gestureBuilder.build(), null, null)
        } catch (e: Exception) {
            // تجاهل
        }
    }
}
