package com.example.stepcounter3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class StepService : Service(), SensorEventListener {

    // 1. Binder to let MainActivity talk to us
    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): StepService = this@StepService
    }

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private lateinit var notificationManager: NotificationManager

    // 2. Data Flow (MainActivity will watch this)
    private val _totalSteps = MutableStateFlow(0)
    val totalSteps: StateFlow<Int> = _totalSteps

    // 3. Logic Variables (Copied from your MainActivity)
    private var stepOffset = 0
    private var lastSeenSensorValue = 0
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        notificationManager = getSystemService(NotificationManager::class.java)

        // Load saved state (Exact logic from your Activity)
        val shared = getSharedPreferences("myPrefs", Context.MODE_PRIVATE)
        stepOffset = shared.getInt("stepOffset", 0)
        lastSeenSensorValue = shared.getInt("lastSeenSensorValue", 0)

        _totalSteps.value = lastSeenSensorValue + stepOffset

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Start the "I am alive" notification
        startForegroundService()

        // Start listening to sensors
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, "step_channel")
            .setContentTitle("Step Mapper is Running") //
            .setContentText("Counting steps in background...")
            .setSmallIcon(R.mipmap.ic_launcher_round) // Ensure you have an icon here
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(1, notification)
        }
    }

    // 🔥 YOUR EXACT LOGIC MOVED HERE
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val rawSensorSteps = event.values[0].toInt()

        if (rawSensorSteps < lastSeenSensorValue) {
            stepOffset += lastSeenSensorValue
            getSharedPreferences("myPrefs", Context.MODE_PRIVATE).edit()
                .putInt("stepOffset", stepOffset)
                .apply()
        }

        lastSeenSensorValue = rawSensorSteps
        getSharedPreferences("myPrefs", Context.MODE_PRIVATE).edit()
            .putInt("lastSeenSensorValue", lastSeenSensorValue)
            .apply()

        val adjustedTotal = rawSensorSteps + stepOffset
        _totalSteps.value = adjustedTotal
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "step_channel", "Step Tracking", NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}