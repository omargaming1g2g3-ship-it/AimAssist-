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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                if (targetX != -1 && targetY != -1) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime >= TAP_DELAY_MS) {
                        performTap(targetX, targetY)
                        lastTapTime = now
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed(this, 50)
            }
        }, 100)
    }

    private fun performTap(x: Int, y: Int) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(gestureBuilder.build(), null, null)
    }
}
