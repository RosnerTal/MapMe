package com.talapp.mapme.services

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.talapp.mapme.R
import com.talapp.mapme.data.Walk
import com.talapp.mapme.data.WalkDatabase
import com.talapp.mapme.data.WalkPoint
import com.talapp.mapme.data.WalkPoi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main CarAppService entry point for Android Auto & Automotive OS.
 */
class MapMeCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return MapMeCarSession()
    }
}

/**
 * Session managing connection to LocationService, live GPS updates, map surface renderer,
 * and lifecycle-aware state invalidation.
 */
class MapMeCarSession : Session(), DefaultLifecycleObserver {
    private var locationService: LocationService? = null
    private var isBound = false
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var liveLocationCallback: LocationCallback? = null

    var mapSurfaceRenderer: CarMapSurfaceRenderer? = null
        private set

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? LocationService.LocalBinder
            locationService = binder?.getService()
            observeLocationService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            locationService = null
            observeJob?.cancel()
        }
    }

    val isTracking: Boolean get() = locationService?.isTracking?.value ?: false
    val isTrackingDrive: Boolean get() = locationService?.isTrackingDrive?.value ?: false
    val elapsedTimeSeconds: Long get() = locationService?.elapsedTimeSeconds?.value ?: 0L
    val totalDistanceMeters: Double get() = locationService?.totalDistanceMeters?.value ?: 0.0
    val currentSpeedKmh: Float get() = locationService?.currentSpeedKmh?.value ?: 0f
    val activePois: List<WalkPoi> get() = locationService?.activePois?.value ?: emptyList()
    val currentPoints: List<WalkPoint> get() = locationService?.currentPoints?.value ?: emptyList()

    override fun onCreateScreen(intent: Intent): Screen {
        lifecycle.addObserver(this)
        bindLocationService()

        // Register the live Map Surface Renderer on the vehicle's Android Auto screen
        val renderer = CarMapSurfaceRenderer(carContext, this)
        mapSurfaceRenderer = renderer
        try {
            carContext.getCarService(androidx.car.app.AppManager::class.java).setSurfaceCallback(renderer)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Immediately start streaming live GPS updates to the map surface renderer
        startContinuousLocationUpdates()

        return MapMeCarMainScreen(carContext, this)
    }

    @SuppressLint("MissingPermission")
    private fun startContinuousLocationUpdates() {
        try {
            val fused = LocationServices.getFusedLocationProviderClient(carContext)
            fusedLocationClient = fused

            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    mapSurfaceRenderer?.updateVehicleLocation(loc)
                }
            }

            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).apply {
                setMinUpdateIntervalMillis(500L)
                setMinUpdateDistanceMeters(0.5f)
            }.build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (loc in result.locations) {
                        mapSurfaceRenderer?.updateVehicleLocation(loc)
                    }
                }
            }
            liveLocationCallback = callback
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun bindLocationService() {
        if (!isBound) {
            try {
                val intent = Intent(carContext, LocationService::class.java)
                carContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                isBound = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        sessionScope.cancel()
        liveLocationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        liveLocationCallback = null
        fusedLocationClient = null

        if (isBound) {
            try {
                carContext.unbindService(serviceConnection)
            } catch (_: Exception) {}
            isBound = false
        }
    }

    private fun observeLocationService() {
        observeJob?.cancel()
        val svc = locationService ?: return
        observeJob = sessionScope.launch {
            combine(
                listOf(
                    svc.isTracking,
                    svc.elapsedTimeSeconds,
                    svc.totalDistanceMeters,
                    svc.currentSpeedKmh,
                    svc.activePois,
                    svc.isTrackingDrive,
                    svc.currentPoints
                )
            ) { _ -> Unit }
                .collect {
                    try {
                        carContext.getCarService(ScreenManager::class.java).top.invalidate()
                        mapSurfaceRenderer?.requestRender()
                    } catch (_: Exception) {}
                }
        }
    }

    /**
     * Start Drive tracking (All in-car tracking is Drive mode).
     */
    fun startTracking(isDrive: Boolean = true) {
        val intent = Intent(carContext, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            putExtra("EXTRA_IS_DRIVE", true)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                carContext.startForegroundService(intent)
            } else {
                carContext.startService(intent)
            }
            bindLocationService()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pauseTracking() {
        try {
            val intent = Intent(carContext, LocationService::class.java).apply {
                action = LocationService.ACTION_PAUSE
            }
            carContext.startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopTracking() {
        try {
            val intent = Intent(carContext, LocationService::class.java).apply {
                action = LocationService.ACTION_STOP
            }
            carContext.startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addPoi(tag: String) {
        locationService?.addPoi(tag, null)
        mapSurfaceRenderer?.requestRender()
    }
}

/**
 * Full-screen in-car navigation map screen:
 * Displays the rotating real-time map from startup with Play/Pause/Stop action controls directly over the map.
 */
class MapMeCarMainScreen(
    carContext: CarContext,
    private val session: MapMeCarSession
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        return try {
            buildNavigationTemplate()
        } catch (e: Exception) {
            e.printStackTrace()
            buildSafeFallback()
        }
    }

    private fun buildNavigationTemplate(): Template {
        val isTracking = session.isTracking
        val currentPoints = session.currentPoints
        val isPaused = !isTracking && currentPoints.isNotEmpty()

        // 1. Top Action Strip (Play / Pause / Stop / POI / History)
        val actionStripBuilder = ActionStrip.Builder()

        val playCarIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_play_arrow)
        ).build()

        val pauseCarIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_pause)
        ).build()

        val stopCarIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_stop)
        ).build()

        val poiCarIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_poi)
        ).build()

        val historyCarIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_history)
        ).build()

        when {
            isTracking -> {
                // Active recording: Show Pause, Stop & Save, and POI
                actionStripBuilder.addAction(
                    Action.Builder()
                        .setTitle("Pause")
                        .setIcon(pauseCarIcon)
                        .setOnClickListener {
                            session.pauseTracking()
                            CarToast.makeText(carContext, "Recording paused", CarToast.LENGTH_SHORT).show()
                            invalidate()
                        }
                        .build()
                )
                actionStripBuilder.addAction(
                    Action.Builder()
                        .setTitle("Stop")
                        .setIcon(stopCarIcon)
                        .setOnClickListener {
                            session.stopTracking()
                            CarToast.makeText(carContext, "Trip saved to MapMe!", CarToast.LENGTH_LONG).show()
                            invalidate()
                        }
                        .build()
                )
                actionStripBuilder.addAction(
                    Action.Builder()
                        .setTitle("POI")
                        .setIcon(poiCarIcon)
                        .setOnClickListener {
                            screenManager.push(CarMarkPoiScreen(carContext, session))
                        }
                        .build()
                )
            }
            isPaused -> {
                // Paused state: Show Resume, Stop & Save, and POI
                actionStripBuilder.addAction(
                    Action.Builder()
                        .setTitle("Resume")
                        .setIcon(playCarIcon)
                        .setOnClickListener {
                            session.startTracking(isDrive = true)
                            CarToast.makeText(carContext, "🚗 Recording resumed", CarToast.LENGTH_SHORT).show()
                            invalidate()
                        }
                        .build()
                )
                actionStripBuilder.addAction(
                    Action.Builder()
                        .setTitle("Stop")
                        .setIcon(stopCarIcon)
                        .setOnClickListener {
                            session.stopTracking()
                            CarToast.makeText(carContext, "Trip saved to MapMe!", CarToast.LENGTH_LONG).show()
                            invalidate()
                        }
                        .build()
                )
                actionStripBuilder.addAction(
                    Action.Builder()
                        .setTitle("POI")
                        .setIcon(poiCarIcon)
                        .setOnClickListener {
                            screenManager.push(CarMarkPoiScreen(carContext, session))
                        }
                        .build()
                )
            }
            else -> {
                // Idle / Ready state: Show Drive (Play) and History
                actionStripBuilder.addAction(
                    Action.Builder()
                        .setTitle("Drive")
                        .setIcon(playCarIcon)
                        .setOnClickListener {
                            session.startTracking(isDrive = true)
                            CarToast.makeText(carContext, "🚗 Drive tracking started!", CarToast.LENGTH_SHORT).show()
                            invalidate()
                        }
                        .build()
                )
                actionStripBuilder.addAction(
                    Action.Builder()
                        .setTitle("History")
                        .setIcon(historyCarIcon)
                        .setOnClickListener {
                            screenManager.push(CarTripListScreen(carContext, session))
                        }
                        .build()
                )
            }
        }

        // 2. Map Action Strip (Pan mode, Recenter, and Heading-Up / North-Up toggle)
        val myLocationIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_my_location)
        ).build()

        val exploreIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_explore)
        ).build()

        val mapActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(
                Action.Builder()
                    .setIcon(myLocationIcon)
                    .setOnClickListener {
                        session.mapSurfaceRenderer?.recenterOnVehicle()
                        CarToast.makeText(carContext, "Centered on vehicle", CarToast.LENGTH_SHORT).show()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(exploreIcon)
                    .setOnClickListener {
                        session.mapSurfaceRenderer?.toggleHeadingMode()
                        val isHeading = session.mapSurfaceRenderer?.isHeadingUp == true
                        val modeLabel = if (isHeading) "Heading-Up (Drive)" else "North-Up"
                        CarToast.makeText(carContext, modeLabel, CarToast.LENGTH_SHORT).show()
                    }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStripBuilder.build())
            .setMapActionStrip(mapActionStrip)
            .build()
    }

    private fun buildSafeFallback(): Template {
        val playCarIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_play_arrow)
        ).build()

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Drive")
                    .setIcon(playCarIcon)
                    .setOnClickListener {
                        session.startTracking(isDrive = true)
                        invalidate()
                    }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }
}

/**
 * Screen for marking waypoints with 1-tap presets during an active trip.
 */
class CarMarkPoiScreen(
    carContext: CarContext,
    private val session: MapMeCarSession
) : Screen(carContext) {

    private val poiOptions = listOf(
        "Scenic View / Lookout 🌄",
        "Pit Stop / Fuel ⛽",
        "Food & Coffee ☕",
        "Hazard / Road Condition ⚠️",
        "Photo Spot 📸",
        "General Waypoint 📍"
    )

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        for (poi in poiOptions) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(poi)
                    .setOnClickListener {
                        session.addPoi(poi)
                        CarToast.makeText(carContext, "Waypoint saved: ", CarToast.LENGTH_SHORT).show()
                        screenManager.pop()
                    }
                    .build()
            )
        }

        val header = Header.Builder()
            .setTitle("Mark Waypoint / POI")
            .setStartHeaderAction(Action.BACK)
            .build()

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .build()
    }
}

/**
 * Screen listing recent trips and drives recorded in the database.
 */
class CarTripListScreen(
    carContext: CarContext,
    private val session: MapMeCarSession
) : Screen(carContext), DefaultLifecycleObserver {

    private var trips: List<Walk> = emptyList()
    private var isLoading = true
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fetchJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        val db = WalkDatabase.getDatabase(carContext)
        fetchJob = screenScope.launch {
            try {
                db.walkDao().getAllWalks().collect { list ->
                    trips = list
                    isLoading = false
                    invalidate()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isLoading = false
                invalidate()
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        screenScope.cancel()
    }

    override fun onGetTemplate(): Template {
        val header = Header.Builder()
            .setTitle("Trip History")
            .setStartHeaderAction(Action.BACK)
            .build()

        val listBuilder = ItemList.Builder()
            .setNoItemsMessage(if (isLoading) "Loading trips..." else "No trips recorded yet.")

        for (trip in trips.take(6)) {
            val dist = formatDistance(trip.totalDistanceMeters)
            val dur = formatDuration(trip.totalDurationMillis / 1000)
            val dateStr = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                .format(java.util.Date(trip.startTime))

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(trip.title)
                    .addText("  •    •  ")
                    .setOnClickListener {
                        screenManager.push(CarTripDetailScreen(carContext, trip.id))
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .build()
    }
}

/**
 * Screen showing detailed metrics and waypoints for a specific past trip.
 */
class CarTripDetailScreen(
    carContext: CarContext,
    private val tripId: Long
) : Screen(carContext), DefaultLifecycleObserver {

    private var walk: Walk? = null
    private var pois: List<WalkPoi> = emptyList()
    private var isLoading = true
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        val db = WalkDatabase.getDatabase(carContext)
        screenScope.launch(Dispatchers.IO) {
            try {
                val loadedWalk = db.walkDao().getWalkById(tripId)
                val loadedPois = try {
                    val type = object : TypeToken<List<WalkPoi>>() {}.type
                    Gson().fromJson<List<WalkPoi>>(loadedWalk?.poisJson ?: "[]", type) ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                withContext(Dispatchers.Main) {
                    walk = loadedWalk
                    pois = loadedPois
                    isLoading = false
                    invalidate()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isLoading = false
                    invalidate()
                }
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        screenScope.cancel()
    }

    override fun onGetTemplate(): Template {
        val header = Header.Builder()
            .setTitle("Trip Details")
            .setStartHeaderAction(Action.BACK)
            .build()

        val currentWalk = walk
        if (isLoading || currentWalk == null) {
            val pane = Pane.Builder()
                .addRow(Row.Builder().setTitle(if (isLoading) "Loading trip details..." else "Trip details not found").build())
                .build()
            return PaneTemplate.Builder(pane)
                .setHeader(header)
                .build()
        }

        val dateStr = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT
        ).format(java.util.Date(currentWalk.startTime))

        val dist = formatDistance(currentWalk.totalDistanceMeters)
        val dur = formatDuration(currentWalk.totalDurationMillis / 1000)

        val avgSpeedKmh = if (currentWalk.totalDurationMillis > 0) {
            (currentWalk.totalDistanceMeters / (currentWalk.totalDurationMillis / 1000.0)) * 3.6
        } else {
            0.0
        }

        val poiSummary = if (pois.isEmpty()) {
            "No waypoints recorded"
        } else {
            " waypoints: " + pois.mapNotNull { it.text }.joinToString(", ")
        }

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(currentWalk.title)
                    .addText("Started: ")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Statistics")
                    .addText("📏 Distance:   •  ⏱️ Duration: ")
                    .addText("⚡ Avg Speed:  km/h")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Waypoints / POIs")
                    .addText(poiSummary)
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Back to History")
                    .setOnClickListener {
                        screenManager.pop()
                    }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setHeader(header)
            .build()
    }
}

private fun formatDuration(seconds: Long): String {
    val nonNegative = if (seconds < 0) 0L else seconds
    val h = nonNegative / 3600
    val m = (nonNegative % 3600) / 60
    val s = nonNegative % 60
    return if (h > 0) {
        String.format("%02d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

private fun formatDistance(meters: Double): String {
    val nonNegative = if (meters < 0) 0.0 else meters
    return if (nonNegative < 1000) {
        String.format("%.0f m", nonNegative)
    } else {
        String.format("%.1f km", nonNegative / 1000.0)
    }
}
