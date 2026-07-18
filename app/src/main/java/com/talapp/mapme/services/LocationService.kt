package com.talapp.mapme.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.talapp.mapme.MainActivity
import com.talapp.mapme.data.Walk
import com.talapp.mapme.data.WalkDatabase
import com.talapp.mapme.data.WalkPoint
import com.talapp.mapme.data.WalkPoi
import com.google.android.gms.location.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationService : Service() {

    private val binder = LocalBinder()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Live Walk Tracking State
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _currentPoints = MutableStateFlow<List<WalkPoint>>(emptyList())
    val currentPoints: StateFlow<List<WalkPoint>> = _currentPoints.asStateFlow()

    private val _totalDistanceMeters = MutableStateFlow(0.0)
    val totalDistanceMeters: StateFlow<Double> = _totalDistanceMeters.asStateFlow()

    private val _elapsedTimeSeconds = MutableStateFlow(0L)
    val elapsedTimeSeconds: StateFlow<Long> = _elapsedTimeSeconds.asStateFlow()

    private val _activePois = MutableStateFlow<List<WalkPoi>>(emptyList())
    val activePois: StateFlow<List<WalkPoi>> = _activePois.asStateFlow()

    private var timerJob: Job? = null
    private var startTimeMillis = 0L
    private var pausedTimeSeconds = 0L

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_STOP = "ACTION_STOP"
        
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mapme_tracking_channel"
    }

    inner class LocalBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    private var isTrackingDrive = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isTrackingDrive = intent.getBooleanExtra("EXTRA_IS_DRIVE", false)
                startTracking()
            }
            ACTION_PAUSE -> pauseTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_NOT_STICKY
    }

    private fun startTracking() {
        if (_isTracking.value) return

        // Verify location permissions are granted before requesting updates
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        _isTracking.value = true
        if (startTimeMillis == 0L) {
            startTimeMillis = System.currentTimeMillis()
        } else {
            // Adjust start time to account for the paused duration
            startTimeMillis = System.currentTimeMillis() - (pausedTimeSeconds * 1000)
        }

        // 1. Build and display the initial notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MapMe: Recording Walk")
            .setContentText("GPS initializing...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(getMainActivityPendingIntent())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Start the tracking timer
        startTimer()

        // 3. Register location updates callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    processLocationUpdate(location)
                }
            }
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).apply {
            setMinUpdateIntervalMillis(1500L)
            setMinUpdateDistanceMeters(1.0f) // Record points when moving 1+ meters
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, android.os.Looper.getMainLooper())
        } catch (unlikely: SecurityException) {
            _isTracking.value = false
            stopSelf()
        }
    }

    private fun processLocationUpdate(location: Location) {
        val newPoint = WalkPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = location.time,
            speed = location.speed
        )

        val updatedList = _currentPoints.value + newPoint
        _currentPoints.value = updatedList

        if (_currentPoints.value.size > 1) {
            val lastPoint = _currentPoints.value[_currentPoints.value.size - 2]
            val results = FloatArray(1)
            Location.distanceBetween(
                lastPoint.latitude, lastPoint.longitude,
                newPoint.latitude, newPoint.longitude,
                results
            )
            _totalDistanceMeters.value += results[0]
        }
        updateNotification()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000
                _elapsedTimeSeconds.value = elapsed
                updateNotification()
                delay(1000L)
            }
        }
    }

    private fun pauseTracking() {
        if (!_isTracking.value) return
        _isTracking.value = false
        pausedTimeSeconds = _elapsedTimeSeconds.value
        timerJob?.cancel()
        
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MapMe: Recording Paused")
            .setContentText("Duration: ${formatDuration(_elapsedTimeSeconds.value)} | Distance: ${formatDistance(_totalDistanceMeters.value)}")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(getMainActivityPendingIntent())
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun stopTracking() {
        _isTracking.value = false
        timerJob?.cancel()
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null

        serviceScope.launch {
            if (_currentPoints.value.isNotEmpty()) {
                val db = WalkDatabase.getDatabase(applicationContext)
                val dateStr = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())
                val timeStr = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
                val prefix = if (isTrackingDrive) "Drive" else "Walk"
                val title = "$prefix on $dateStr ($timeStr)"
                val walk = Walk(
                    title = title,
                    startTime = startTimeMillis,
                    endTime = System.currentTimeMillis(),
                    totalDistanceMeters = _totalDistanceMeters.value,
                    totalDurationMillis = _elapsedTimeSeconds.value * 1000,
                    pointsJson = Gson().toJson(_currentPoints.value),
                    poisJson = Gson().toJson(_activePois.value)
                )
                db.walkDao().insertWalk(walk)
            }

            withContext(Dispatchers.Main) {
                // Reset stats
                _currentPoints.value = emptyList()
                _activePois.value = emptyList()
                _totalDistanceMeters.value = 0.0
                _elapsedTimeSeconds.value = 0L
                pausedTimeSeconds = 0L
                startTimeMillis = 0L

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
    }

    fun addPoi(text: String?, imageBase64: String?) {
        val loc = _currentPoints.value.lastOrNull() ?: return
        val newPoi = WalkPoi(
            latitude = loc.latitude,
            longitude = loc.longitude,
            timestamp = System.currentTimeMillis(),
            text = text,
            imageBase64 = imageBase64
        )
        _activePois.value = _activePois.value + newPoi
    }

    private fun updateNotification() {
        val durationText = formatDuration(_elapsedTimeSeconds.value)
        val distanceText = formatDistance(_totalDistanceMeters.value)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MapMe: Recording Walk")
            .setContentText("Duration: $durationText | Distance: $distanceText")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(getMainActivityPendingIntent())
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun getMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Walk Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays real-time tracking duration and distance."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format("%02d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    private fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            String.format("%.0f m", meters)
        } else {
            String.format("%.2f km", meters / 1000.0)
        }
    }
}

