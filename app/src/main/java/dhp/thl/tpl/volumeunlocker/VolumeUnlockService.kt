package dhp.thl.tpl.volumeunlocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat

class VolumeUnlockService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var volumeObserver: ContentObserver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var mediaSession: MediaSessionCompat? = null
    
    private var lastWakeTime = 0L
    private var isServiceRunning = false

    companion object {
        private const val NOTIFICATION_ID = 9912
        private const val CHANNEL_ID = "VolumeUnlockServiceChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START && !isServiceRunning) {
            startForegroundService()
        } else if (action == ACTION_STOP) {
            stopForegroundService()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        isServiceRunning = true
        
        // Start Foreground Service
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Acquire partial wake lock to keep CPU alive when screen is off
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VolumeUnlock::CpuWakeLock").apply {
            acquire()
        }

        // Setup MediaSession for when no music is playing
        setupMediaSession()

        // Register ContentObserver for volume settings (to catch changes when other music is playing)
        registerVolumeObserver()

        // Register BroadcastReceiver for volume changes
        registerVolumeReceiver()
    }

    private fun stopForegroundService() {
        isServiceRunning = false
        
        unregisterVolumeObserver()
        unregisterVolumeReceiver()
        releaseMediaSession()

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null

        stopForeground(true)
        stopSelf()
    }

    private fun setupMediaSession() {
        try {
            mediaSession = MediaSessionCompat(this, "VolumeUnlockSession").apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or 
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )

                val state = PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE)
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                    .build()
                setPlaybackState(state)

                // Setup remote volume provider to intercept keys when no other media is active
                val volumeProvider = object : VolumeProviderCompat(
                    VOLUME_CONTROL_RELATIVE, 
                    100, 
                    50
                ) {
                    override fun onAdjustVolume(direction: Int) {
                        // Intercept volume keys and change volume programmatically
                        adjustSystemVolume(direction)
                        handleVolumeChangeAttempt()
                    }
                }
                setPlaybackToRemote(volumeProvider)
                isActive = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseMediaSession() {
        mediaSession?.let {
            try {
                it.isActive = false
                it.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        mediaSession = null
    }

    private fun registerVolumeObserver() {
        val handler = Handler(Looper.getMainLooper())
        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                handleVolumeChangeAttempt()
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver!!
        )
    }

    private fun unregisterVolumeObserver() {
        volumeObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        volumeObserver = null
    }

    private fun registerVolumeReceiver() {
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    handleVolumeChangeAttempt()
                }
            }
        }
        registerReceiver(
            volumeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        )
    }

    private fun unregisterVolumeReceiver() {
        volumeReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        volumeReceiver = null
    }

    private fun adjustSystemVolume(direction: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val adjustDir = if (direction > 0) {
                AudioManager.ADJUST_RAISE
            } else if (direction < 0) {
                AudioManager.ADJUST_LOWER
            } else {
                0
            }

            if (adjustDir != 0) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    adjustDir,
                    AudioManager.FLAG_SHOW_UI
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleVolumeChangeAttempt() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            triggerWake()
        }
    }

    private fun triggerWake() {
        val now = System.currentTimeMillis()
        if (now - lastWakeTime > 1500) { // Debounce wakes
            lastWakeTime = now
            wakeScreen()
            vibrateFeedback()
        }
    }

    private fun wakeScreen() {
        try {
            val intent = Intent(this, WakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                         Intent.FLAG_ACTIVITY_SINGLE_TOP)
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

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume Unlock Active")
            .setContentText("Dịch vụ đang chạy. Bấm nút âm lượng để bật màn hình.")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Volume Unlock Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kênh thông báo cho dịch vụ Volume Unlock"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopForegroundService()
        super.onDestroy()
    }
}
