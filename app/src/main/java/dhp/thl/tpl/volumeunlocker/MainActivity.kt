package dhp.thl.tpl.volumeunlocker

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var switchService: SwitchCompat
    private lateinit var tvDeviceAdminStatus: TextView
    private lateinit var btnDeviceAdmin: Button
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnBatteryOptimization: Button
    private lateinit var btnLockScreen: Button

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize variables
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, VolumeUnlockAdminReceiver::class.java)

        // Bind views
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        switchService = findViewById(R.id.switchService)
        tvDeviceAdminStatus = findViewById(R.id.tvDeviceAdminStatus)
        btnDeviceAdmin = findViewById(R.id.btnDeviceAdmin)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization)
        btnLockScreen = findViewById(R.id.btnLockScreen)

        // Setup Listeners
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    private fun setupListeners() {
        // Toggle service switch
        switchService.setOnCheckedChangeListener { _, isChecked ->
            val isRunning = isServiceRunning(VolumeUnlockService::class.java)
            if (isChecked && !isRunning) {
                val intent = Intent(this, VolumeUnlockService::class.java).apply {
                    action = VolumeUnlockService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "Đã khởi chạy dịch vụ", Toast.LENGTH_SHORT).show()
                updateUIState()
            } else if (!isChecked && isRunning) {
                val intent = Intent(this, VolumeUnlockService::class.java).apply {
                    action = VolumeUnlockService.ACTION_STOP
                }
                startService(intent)
                Toast.makeText(this, "Đã dừng dịch vụ", Toast.LENGTH_SHORT).show()
                updateUIState()
            }
        }

        // Device Admin Button
        btnDeviceAdmin.setOnClickListener {
            val isActive = devicePolicyManager.isAdminActive(adminComponent)
            if (isActive) {
                devicePolicyManager.removeActiveAdmin(adminComponent)
                Toast.makeText(this, "Đã hủy quyền Device Admin", Toast.LENGTH_SHORT).show()
                updateUIState()
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Ứng dụng cần quyền admin để hỗ trợ khóa màn hình nhanh khi thử nghiệm tính năng.")
                }
                startActivity(intent)
            }
        }

        // Battery Optimization Button
        btnBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general settings if direct request fails
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this, "Thiết bị này không cần cấu hình pin.", Toast.LENGTH_SHORT).show()
            }
        }

        // Quick Lock Screen Button
        btnLockScreen.setOnClickListener {
            val isActive = devicePolicyManager.isAdminActive(adminComponent)
            if (isActive) {
                try {
                    devicePolicyManager.lockNow()
                } catch (e: Exception) {
                    Toast.makeText(this, "Lỗi khóa màn hình: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Vui lòng kích hoạt quyền Device Admin trước!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUIState() {
        // 1. Service Running Status
        val isRunning = isServiceRunning(VolumeUnlockService::class.java)
        switchService.isChecked = isRunning

        if (isRunning) {
            statusText.text = "Trạng thái: ĐANG CHẠY"
            setStatusDotColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            statusText.text = "Trạng thái: ĐANG DỪNG"
            setStatusDotColor(ContextCompat.getColor(this, R.color.status_inactive))
        }

        // 2. Device Admin Status
        val isAdminActive = devicePolicyManager.isAdminActive(adminComponent)
        if (isAdminActive) {
            tvDeviceAdminStatus.text = "Quyền Admin: ĐÃ BẬT"
            btnDeviceAdmin.text = "Hủy quyền Device Admin"
            btnDeviceAdmin.setBackgroundColor(ContextCompat.getColor(this, R.color.status_inactive))
        } else {
            tvDeviceAdminStatus.text = "Quyền Admin: CHƯA CẤP"
            btnDeviceAdmin.text = "Kích hoạt Device Admin"
            btnDeviceAdmin.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }

        // 3. Battery Optimization Status
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }

        if (isIgnoring) {
            tvBatteryStatus.text = "Tối ưu hóa Pin: ĐÃ TẮT (Tốt nhất)"
            btnBatteryOptimization.visibility = View.GONE
        } else {
            tvBatteryStatus.text = "Tối ưu hóa Pin: ĐANG BẬT (Dễ bị hệ thống tắt)"
            btnBatteryOptimization.visibility = View.VISIBLE
            btnBatteryOptimization.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    private fun setStatusDotColor(color: Int) {
        val background = statusDot.background
        if (background is GradientDrawable) {
            background.setColor(color)
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
