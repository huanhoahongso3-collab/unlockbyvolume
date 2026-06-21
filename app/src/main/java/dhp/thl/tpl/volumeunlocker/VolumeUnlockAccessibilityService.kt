package dhp.thl.tpl.volumeunlocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import         android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeUnlockAccessibilityService : AccessibilityService() {

    companion object {
        var instance: VolumeUnlockAccessibilityService? = null
            private set
    }

    private var lastWakeTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                // Only handle the KEY_DOWN event to prevent multiple triggers
                if (action == KeyEvent.ACTION_DOWN) {
                    triggerWake()
                }
                return true // Consume key event when screen is off
            }
        }
        return false // Let system handle key event when screen is on
    }

    private fun triggerWake() {
        val now = System.currentTimeMillis()
        if (now - lastWakeTime > 500) {
            lastWakeTime = now
            wakeScreen()
            vibrateFeedback()
        }
    }

    private fun wakeScreen() {
        try {
            val intent = Intent(this, WakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                         Intent.FLAG_ACTIVITY_CLEAR_TASK or 
                         Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun vibrateFeedback() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun lockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Not used
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
