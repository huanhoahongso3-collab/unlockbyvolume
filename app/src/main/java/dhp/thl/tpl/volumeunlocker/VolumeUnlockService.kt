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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.app.NotificationCompat

class VolumeUnlockService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var volumeObserver: ContentObserver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var silentPlayer: SilentAudioPlayer? = null
    
    private var lastWakeTime = 0L
    private var isServiceRunning = false

    companion object {
        private const val NOTIFICATION_ID = 9912
        private const val CHANNEL_ID = "VolumeUnlockServiceChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                startSilence()
            }
            AudioManager.AUDIOFOCUS_LOSS, 
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, 
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                stopSilence()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

        // Initialize silent player
        silentPlayer = SilentAudioPlayer()

        // Register ContentObserver for volume settings
        registerVolumeObserver()

        // Register BroadcastReceiver for volume changes
        registerVolumeReceiver()

        // Register BroadcastReceiver for screen states to manage audio focus
        registerScreenReceiver()

        // Request initial audio focus if screen is off
        if (!pm.isInteractive) {
            requestAudioFocus()
        }
    }

    private fun stopForegroundService() {
        isServiceRunning = false
        
        unregisterScreenReceiver()
        unregisterVolumeObserver()
        unregisterVolumeReceiver()
        abandonAudioFocus()
        stopSilence()

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null

        stopForeground(true)
        stopSelf()
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
            startSilence()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startSilence() {
        silentPlayer?.start()
    }

    private fun stopSilence() {
        silentPlayer?.stop()
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

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // Request audio focus when screen goes off to enable volume keys
                        requestAudioFocus()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        // Release focus and stop silence when user is using the phone
                        abandonAudioFocus()
                        stopSilence()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        screenReceiver = null
    }

    private fun handleVolumeChangeAttempt() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            triggerWake()
        }
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

    // Inner class to handle silent loop playback using AudioTrack
    private class SilentAudioPlayer {
        private var audioTrack: AudioTrack? = null
        private var isPlaying = false
        private var thread: Thread? = null

        fun start() {
            if (isPlaying) return
            isPlaying = true
            
            thread = Thread {
                try {
                    val sampleRate = 44100
                    val minBufferSize = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    
                    audioTrack = AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSize,
                        AudioTrack.MODE_STREAM
                    )
                    
                    audioTrack?.play()
                    
                    val buffer = ShortArray(minBufferSize) // Filled with 0s (silence)
                    while (isPlaying) {
                        val track = audioTrack ?: break
                        val written = track.write(buffer, 0, buffer.size)
                        if (written <= 0) {
                            Thread.sleep(50)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            thread?.start()
        }

        fun stop() {
            isPlaying = false
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            audioTrack = null
            thread = null
        }
    }
}
