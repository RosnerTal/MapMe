package com.talapp.mapme.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.talapp.mapme.data.Walk
import com.talapp.mapme.data.WalkDatabase
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
        // Allow all hosts to support direct installation, sideloading, and all car head units
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return MapMeCarSession()
    }
}

/**
 * Session managing connection to LocationService and lifecycle-aware state invalidation.
 */
class MapMeCarSession : Session(), DefaultLifecycleObserver {
    private var locationService: LocationService? = null
    private var isBound = false
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

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

    override fun onCreateScreen(intent: Intent): Screen {
        lifecycle.addObserver(this)
        bindLocationService()
        return MapMeCarMainScreen(carContext, this)
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
                    svc.isTrackingDrive
                )
            ) { _ -> Unit }
                .collect {
                    try {
                        carContext.getCarService(ScreenManager::class.java).top.invalidate()
                    } catch (_: Exception) {}
                }
        }
    }

    fun startTracking(isDrive: Boolean) {
        val intent = Intent(carContext, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            putExtra("EXTRA_IS_DRIVE", isDrive)
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
    }
}

/**
 * In-Car Main Screen: Active Tracking HUD when recording, or Drive Hub with Start actions when idle.
 */
class MapMeCarMainScreen(
    carContext: CarContext,
    private val session: MapMeCarSession
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        return try {
            buildTemplate()
        } catch (e: Exception) {
            e.printStackTrace()
            val pane = Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle("MapMe Drive Hub")
                        .addText("Ready to track. Tap below to start.")
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("🚗 Start Drive")
                        .setOnClickListener {
                            session.startTracking(isDrive = true)
                            invalidate()
                        }
                        .build()
                )
                .build()

            @Suppress("DEPRECATION")
            PaneTemplate.Builder(pane)
                .setHeader(Header.Builder().setTitle("MapMe").build())
                .build()
        }
    }

    private fun buildTemplate(): Template {
        val isTracking = session.isTracking
        val isDrive = session.isTrackingDrive
        val duration = session.elapsedTimeSeconds
        val distance = session.totalDistanceMeters
        val speed = session.currentSpeedKmh
        val poisCount = session.activePois.size

        return if (isTracking) {
            val modeTitle = if (isDrive) "🚗 Drive Recording" else "🚶 Walk Recording"
            val paneBuilder = Pane.Builder()

            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("⏱️ Duration: ${formatDuration(duration)}")
                    .addText("📏 Distance: ${formatDistance(distance)}  •  ⚡ Speed: ${String.format("%.1f", speed)} km/h")
                    .addText("📍 Waypoints: $poisCount saved")
                    .build()
            )

            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("⏸️ Pause")
                    .setOnClickListener {
                        session.pauseTracking()
                        CarToast.makeText(carContext, "Recording paused", CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )

            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("⏹️ Stop & Save")
                    .setOnClickListener {
                        session.stopTracking()
                        CarToast.makeText(carContext, "Trip saved to MapMe!", CarToast.LENGTH_LONG).show()
                        invalidate()
                    }
                    .build()
            )

            val actionStrip = ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("📍 Mark POI")
                        .setOnClickListener {
                            screenManager.push(CarMarkPoiScreen(carContext, session))
                        }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("📜 History")
                        .setOnClickListener {
                            screenManager.push(CarTripListScreen(carContext, session))
                        }
                        .build()
                )
                .build()

            val header = Header.Builder()
                .setTitle(modeTitle)
                .build()

            @Suppress("DEPRECATION")
            PaneTemplate.Builder(paneBuilder.build())
                .setHeader(header)
                .setActionStrip(actionStrip)
                .build()
        } else {
            // Idle / Ready state
            val paneBuilder = Pane.Builder()

            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Ready to Track")
                    .addText("Tap an activity below to start GPS recording on your car screen.")
                    .build()
            )

            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("📜 Trip History & Logs")
                    .addText("View completed trips and recorded waypoints.")
                    .setOnClickListener {
                        screenManager.push(CarTripListScreen(carContext, session))
                    }
                    .build()
            )

            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("🚗 Start Drive")
                    .setOnClickListener {
                        session.startTracking(isDrive = true)
                        CarToast.makeText(carContext, "Drive tracking started!", CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )

            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("🚶 Start Walk")
                    .setOnClickListener {
                        session.startTracking(isDrive = false)
                        CarToast.makeText(carContext, "Walk tracking started!", CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )

            val actionStrip = ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("📜 History")
                        .setOnClickListener {
                            screenManager.push(CarTripListScreen(carContext, session))
                        }
                        .build()
                )
                .build()

            val header = Header.Builder()
                .setTitle("MapMe v${com.talapp.mapme.BuildConfig.VERSION_NAME}")
                .build()

            @Suppress("DEPRECATION")
            PaneTemplate.Builder(paneBuilder.build())
                .setHeader(header)
                .setActionStrip(actionStrip)
                .build()
        }
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
                        CarToast.makeText(carContext, "Waypoint saved: $poi", CarToast.LENGTH_SHORT).show()
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
 * Screen listing recent trips and walks recorded in the database.
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
                    .addText("$dateStr  •  $dist  •  $dur")
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
            "${pois.size} waypoints: " + pois.mapNotNull { it.text }.joinToString(", ")
        }

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(currentWalk.title)
                    .addText("Started: $dateStr")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Statistics")
                    .addText("📏 Distance: $dist  •  ⏱️ Duration: $dur")
                    .addText("⚡ Avg Speed: ${String.format("%.1f", avgSpeedKmh)} km/h")
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
        String.format("%.2f km", nonNegative / 1000.0)
    }
}
