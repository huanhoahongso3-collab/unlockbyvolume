package dhp.thl.tpl.volumeunlocker

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var btnLockScreen: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnLockScreen = findViewById(R.id.btnLockScreen)

        // Setup Listeners
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    private fun setupListeners() {
        // Accessibility Service Button
        btnAccessibility.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Không thể mở cài đặt Hỗ trợ", Toast.LENGTH_SHORT).show()
            }
        }

        // Quick Lock Screen Button
        btnLockScreen.setOnClickListener {
            val service = VolumeUnlockAccessibilityService.instance
            if (service != null) {
                val success = service.lockScreen()
                if (!success) {
                    Toast.makeText(this, "Yêu cầu khóa màn hình thất bại (Cần Android 9+)", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Vui lòng kích hoạt dịch vụ Accessibility trước!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUIState() {
        val isRunning = VolumeUnlockAccessibilityService.instance != null

        if (isRunning) {
            statusText.text = "Trạng thái: ĐANG CHẠY"
            setStatusDotColor(ContextCompat.getColor(this, R.color.status_active))
            
            tvAccessibilityStatus.text = "Quyền Accessibility: ĐÃ BẬT"
            btnAccessibility.text = "Mở cài đặt Hỗ trợ (Tắt dịch vụ)"
            btnAccessibility.setBackgroundColor(ContextCompat.getColor(this, R.color.status_inactive))
        } else {
            statusText.text = "Trạng thái: ĐANG DỪNG"
            setStatusDotColor(ContextCompat.getColor(this, R.color.status_inactive))
            
            tvAccessibilityStatus.text = "Quyền Accessibility: CHƯA CẤP"
            btnAccessibility.text = "Kích hoạt Accessibility Service"
            btnAccessibility.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    private fun setStatusDotColor(color: Int) {
        val background = statusDot.background
        if (background is GradientDrawable) {
            background.setColor(color)
        }
    }
}
