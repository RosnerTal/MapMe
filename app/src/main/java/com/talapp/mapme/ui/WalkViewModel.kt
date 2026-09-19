package com.talapp.mapme.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.talapp.mapme.data.Walk
import com.talapp.mapme.data.WalkDatabase
import com.talapp.mapme.data.WalkRepository
import com.talapp.mapme.data.WalkPoint
import com.talapp.mapme.services.LocationService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import android.location.Location
import android.speech.tts.TextToSpeech
import com.google.android.gms.location.LocationServices
import com.talapp.mapme.services.NavigationEngine
import com.talapp.mapme.services.NavMode
import com.talapp.mapme.services.NavRoute
import com.talapp.mapme.services.NavSearchResult
import com.talapp.mapme.services.NavStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

@OptIn(ExperimentalCoroutinesApi::class)
class WalkViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: WalkRepository = WalkRepository(WalkDatabase.getDatabase(application).walkDao())
    private val _locationService = MutableStateFlow<LocationService?>(null)
    val locationService = _locationService.asStateFlow()

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser = _currentUser.asStateFlow()

    private val _isDarkMap = MutableStateFlow(true)
    val isDarkMap = _isDarkMap.asStateFlow()

    private val _showWalks = MutableStateFlow(true)
    val showWalks = _showWalks.asStateFlow()

    private val _showDrives = MutableStateFlow(true)
    val showDrives = _showDrives.asStateFlow()

    private val _showPois = MutableStateFlow(true)
    val showPois = _showPois.asStateFlow()

    private val _lastSyncedTime = MutableStateFlow<String?>("Not synced yet")
    val lastSyncedTime = _lastSyncedTime.asStateFlow()

    // Active In-App Navigation State
    private val _activeNavRoute = MutableStateFlow<NavRoute?>(null)
    val activeNavRoute = _activeNavRoute.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating = _isNavigating.asStateFlow()

    private val _navMode = MutableStateFlow(NavMode.WALKING)
    val navMode = _navMode.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex = _currentStepIndex.asStateFlow()

    private val _distanceToNextStepMeters = MutableStateFlow(0.0)
    val distanceToNextStepMeters = _distanceToNextStepMeters.asStateFlow()

    private val _remainingDistanceMeters = MutableStateFlow(0.0)
    val remainingDistanceMeters = _remainingDistanceMeters.asStateFlow()

    private val _remainingDurationSeconds = MutableStateFlow(0.0)
    val remainingDurationSeconds = _remainingDurationSeconds.asStateFlow()

    private val _isVoiceMuted = MutableStateFlow(false)
    val isVoiceMuted = _isVoiceMuted.asStateFlow()

    // Destination Search & Route Preview State
    private val _searchResults = MutableStateFlow<List<NavSearchResult>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _selectedDestination = MutableStateFlow<NavSearchResult?>(null)
    val selectedDestination = _selectedDestination.asStateFlow()

    private val _previewNavRoute = MutableStateFlow<NavRoute?>(null)
    val previewNavRoute = _previewNavRoute.asStateFlow()

    private val _isCalculatingRoute = MutableStateFlow(false)
    val isCalculatingRoute = _isCalculatingRoute.asStateFlow()

    private val _lastKnownLocation = MutableStateFlow<Location?>(null)
    val lastKnownLocation = _lastKnownLocation.asStateFlow()

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var searchJob: Job? = null
    private var lastReRouteTime = 0L

    fun toggleMapStyle() {
        _isDarkMap.value = !_isDarkMap.value
    }

    fun toggleShowWalks() {
        _showWalks.value = !_showWalks.value
    }

    fun toggleShowDrives() {
        _showDrives.value = !_showDrives.value
    }

    fun toggleShowPois() {
        _showPois.value = !_showPois.value
    }

    // Bound Service tracking states
    val isTracking: Flow<Boolean> = _locationService.flatMapLatest { service ->
        service?.isTracking ?: flowOf(false)
    }

    val isTrackingDrive: Flow<Boolean> = _locationService.flatMapLatest { service ->
        service?.isTrackingDrive ?: flowOf(false)
    }

    val activePoints: Flow<List<WalkPoint>> = _locationService.flatMapLatest { service ->
        service?.currentPoints ?: flowOf(emptyList())
    }

    val activePois: Flow<List<com.talapp.mapme.data.WalkPoi>> = _locationService.flatMapLatest { service ->
        service?.activePois ?: flowOf(emptyList())
    }

    val activeDistanceMeters: Flow<Double> = _locationService.flatMapLatest { service ->
        service?.totalDistanceMeters ?: flowOf(0.0)
    }

    val activeDurationSeconds: Flow<Long> = _locationService.flatMapLatest { service ->
        service?.elapsedTimeSeconds ?: flowOf(0L)
    }

    fun addActivePoi(text: String?, imageBase64: String?) {
        _locationService.value?.addPoi(text, imageBase64)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LocationService.LocalBinder
            _locationService.value = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _locationService.value = null
        }
    }

    init {
        // Safe Firebase Auth initialization
        try {
            _currentUser.value = auth.currentUser
            auth.addAuthStateListener { firebaseAuth ->
                _currentUser.value = firebaseAuth.currentUser
                syncWalks()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Bind to tracking service
        try {
            val intent = Intent(application, LocationService::class.java)
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Automatically sync when database changes
        viewModelScope.launch {
            repository.allWalks.collect {
                syncWalks()
            }
        }

        // Fetch initial user GPS location
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    _lastKnownLocation.value = loc
                }
            }
        } catch (_: SecurityException) {}

        // Stream location updates into navigation engine & keep lastKnownLocation fresh
        viewModelScope.launch {
            activePoints.collect { pts ->
                pts.lastOrNull()?.let { pt ->
                    val loc = Location("GPS").apply {
                        latitude = pt.latitude
                        longitude = pt.longitude
                        speed = pt.speed
                        time = pt.timestamp
                    }
                    _lastKnownLocation.value = loc
                    if (_isNavigating.value) {
                        updateNavigationProgress(loc)
                    }
                }
            }
        }
    }

    enum class Timeframe { WEEK, MONTH, THREE_MONTHS, LIFETIME }

    private val _selectedTimeframe = MutableStateFlow(Timeframe.WEEK)
    val selectedTimeframe = _selectedTimeframe.asStateFlow()

    fun setTimeframe(timeframe: Timeframe) {
        _selectedTimeframe.value = timeframe
    }

    // List of all completed walks
    val allWalks: StateFlow<List<Walk>> = repository.allWalks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered walks based on the selected timeframe
    val filteredWalks: StateFlow<List<Walk>> = combine(allWalks, _selectedTimeframe) { walks, timeframe ->
        val cutoffTime = when (timeframe) {
            Timeframe.WEEK -> System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            Timeframe.MONTH -> System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L
            Timeframe.THREE_MONTHS -> System.currentTimeMillis() - 90 * 24 * 60 * 60 * 1000L
            Timeframe.LIFETIME -> 0L
        }
        if (cutoffTime == 0L) walks else walks.filter { it.startTime >= cutoffTime }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Statistics based on selected timeframe
    val totalWalks: StateFlow<Int> = filteredWalks.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalDistanceMeters: StateFlow<Double> = filteredWalks.map { list ->
        list.sumOf { it.totalDistanceMeters }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalDurationMillis: StateFlow<Long> = filteredWalks.map { list ->
        list.sumOf { it.totalDurationMillis }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun startWalk(isDrive: Boolean = false) {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            putExtra("EXTRA_IS_DRIVE", isDrive)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pauseWalk() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun stopWalk() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun deleteWalk(walkId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteWalkById(walkId)
            val user = auth.currentUser
            if (user != null) {
                try {
                    val walkDoc = firestore.collection("users").document(user.uid)
                        .collection("walks").document(walkId.toString())
                    com.google.android.gms.tasks.Tasks.await(walkDoc.delete())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun getWalkFlow(walkId: Long): Flow<Walk?> = flow {
        emit(repository.getWalkById(walkId))
    }

    fun signInAnonymously() {
        auth.signInAnonymously().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                _currentUser.value = auth.currentUser
            } else {
                android.widget.Toast.makeText(getApplication(), "Firebase Auth Failed: " + task.exception?.localizedMessage, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun signInWithGoogleCredential(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                _currentUser.value = auth.currentUser
            } else {
                android.widget.Toast.makeText(getApplication(), "Firebase Auth Credentials Failed: " + task.exception?.localizedMessage, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun signOut() {
        auth.signOut()
        _currentUser.value = null
    }

    fun syncWalks() {
        viewModelScope.launch(Dispatchers.IO) {
            val user = auth.currentUser ?: return@launch
            try {
                // 1. Upload unsynced local walks to Firestore
                val unsynced = repository.getUnsyncedWalks()
                for (walk in unsynced) {
                    val walkDoc = firestore.collection("users").document(user.uid)
                        .collection("walks").document(walk.id.toString())

                    val data = hashMapOf(
                        "id" to walk.id,
                        "title" to walk.title,
                        "startTime" to walk.startTime,
                        "endTime" to walk.endTime,
                        "totalDistanceMeters" to walk.totalDistanceMeters,
                        "totalDurationMillis" to walk.totalDurationMillis,
                        "pointsJson" to walk.pointsJson,
                        "poisJson" to walk.poisJson,
                        "syncedAt" to com.google.firebase.Timestamp.now()
                    )

                    com.google.android.gms.tasks.Tasks.await(walkDoc.set(data))
                    repository.markWalkSynced(walk.id)
                }

                // 2. Download remote walks from Firestore
                val querySnapshot = com.google.android.gms.tasks.Tasks.await(
                    firestore.collection("users").document(user.uid)
                        .collection("walks").get()
                )

                for (doc in querySnapshot.documents) {
                    val remoteId = doc.getLong("id") ?: continue
                    val existing = repository.getWalkById(remoteId)
                    if (existing == null) {
                        val walk = Walk(
                            id = remoteId,
                            title = doc.getString("title") ?: "Walk",
                            startTime = doc.getLong("startTime") ?: 0L,
                            endTime = doc.getLong("endTime") ?: 0L,
                            totalDistanceMeters = doc.getDouble("totalDistanceMeters") ?: 0.0,
                            totalDurationMillis = doc.getLong("totalDurationMillis") ?: 0L,
                            pointsJson = doc.getString("pointsJson") ?: "[]",
                            poisJson = doc.getString("poisJson") ?: "[]",
                            isSynced = true
                        )
                        repository.insertWalk(walk)
                    }
                }
                
                val nowStr = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
                _lastSyncedTime.value = "Last synced today at $nowStr"
            } catch (e: Exception) {
                e.printStackTrace()
                _lastSyncedTime.value = "Sync failed"
            }
        }
    }

    // --------------------------------------------------------------------------
    // In-App Turn-by-Turn Navigation & Destination Search
    // --------------------------------------------------------------------------

    fun initTts() {
        if (textToSpeech == null) {
            textToSpeech = TextToSpeech(getApplication()) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.language = java.util.Locale.getDefault()
                    isTtsReady = true
                }
            }
        }
    }

    fun speakCue(cue: String) {
        if (_isVoiceMuted.value || cue.isBlank()) return
        initTts()
        if (isTtsReady) {
            textToSpeech?.speak(cue, TextToSpeech.QUEUE_FLUSH, null, "nav_cue_${System.currentTimeMillis()}")
        }
    }

    fun searchDestinations(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        _isSearching.value = true
        searchJob = viewModelScope.launch {
            delay(350L) // 350ms debounce
            val bias = _lastKnownLocation.value
            val results = NavigationEngine.searchDestinations(
                query = trimmed,
                biasLat = bias?.latitude,
                biasLon = bias?.longitude,
                limit = 8
            )
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _isSearching.value = false
        _selectedDestination.value = null
        _previewNavRoute.value = null
        _isCalculatingRoute.value = false
    }

    fun selectDestinationAndPreviewRoute(destination: NavSearchResult, mode: NavMode = _navMode.value) {
        _selectedDestination.value = destination
        _navMode.value = mode
        _isCalculatingRoute.value = true
        viewModelScope.launch {
            val currentLoc = _lastKnownLocation.value
            val startLat = currentLoc?.latitude ?: destination.latitude
            val startLon = currentLoc?.longitude ?: destination.longitude
            val route = NavigationEngine.calculateRoute(
                startLat = startLat,
                startLon = startLon,
                destLat = destination.latitude,
                destLon = destination.longitude,
                destinationTitle = destination.title,
                mode = mode
            )
            _previewNavRoute.value = route
            _isCalculatingRoute.value = false
        }
    }

    fun setNavMode(mode: NavMode) {
        _navMode.value = mode
        val dest = _selectedDestination.value
        if (dest != null) {
            selectDestinationAndPreviewRoute(dest, mode)
        }
    }

    fun startInAppNavigation(route: NavRoute? = null) {
        val routeToStart = route ?: _previewNavRoute.value ?: return
        _activeNavRoute.value = routeToStart
        _currentStepIndex.value = 0
        _remainingDistanceMeters.value = routeToStart.totalDistanceMeters
        _remainingDurationSeconds.value = routeToStart.totalDurationSeconds
        _isNavigating.value = true

        // Ensure active tracking is running so the user's travel is mapped and logged
        val isDrive = (routeToStart.mode == NavMode.DRIVING)
        startWalk(isDrive)

        // Announce initial maneuver
        initTts()
        val firstStep = routeToStart.steps.firstOrNull()
        val initialCue = firstStep?.cue ?: ("Starting navigation to " + routeToStart.destinationTitle)
        speakCue(initialCue)

        clearSearch()
    }

    fun stopInAppNavigation() {
        _activeNavRoute.value = null
        _isNavigating.value = false
        _currentStepIndex.value = 0
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {}
    }

    fun toggleVoiceMute() {
        _isVoiceMuted.value = !_isVoiceMuted.value
    }

    private fun updateNavigationProgress(loc: Location) {
        val route = _activeNavRoute.value ?: return
        val steps = route.steps
        val stepIdx = _currentStepIndex.value

        // 1. Check destination arrival
        val destLoc = Location("Destination").apply {
            latitude = route.destinationLat
            longitude = route.destinationLon
        }
        val distToDestination = loc.distanceTo(destLoc)
        _remainingDistanceMeters.value = distToDestination.toDouble()

        if (distToDestination <= 25.0) {
            speakCue("You have arrived at your destination: ${route.destinationTitle}")
            stopInAppNavigation()
            return
        }

        // 2. Track step maneuver progress
        if (stepIdx < steps.size) {
            val currentStep = steps[stepIdx]
            val stepLoc = Location("Step").apply {
                latitude = currentStep.latitude
                longitude = currentStep.longitude
            }
            val distToStep = loc.distanceTo(stepLoc).toDouble()
            _distanceToNextStepMeters.value = distToStep

            // Maneuver completion threshold: 25m for walking, 45m for driving
            val stepThreshold = if (route.mode == NavMode.WALKING) 25.0 else 45.0
            if (distToStep <= stepThreshold && stepIdx < steps.size - 1) {
                val nextIdx = stepIdx + 1
                _currentStepIndex.value = nextIdx
                val nextStep = steps[nextIdx]
                speakCue(nextStep.cue)
            }
        }

        // 3. Off-route detection & auto re-routing
        val now = System.currentTimeMillis()
        if (now - lastReRouteTime > 12000L) {
            val offRouteThreshold = if (route.mode == NavMode.WALKING) 50.0 else 90.0
            if (NavigationEngine.isOffRoute(loc.latitude, loc.longitude, route.waypoints, offRouteThreshold)) {
                lastReRouteTime = now
                viewModelScope.launch {
                    val reCalculated = NavigationEngine.calculateRoute(
                        startLat = loc.latitude,
                        startLon = loc.longitude,
                        destLat = route.destinationLat,
                        destLon = route.destinationLon,
                        destinationTitle = route.destinationTitle,
                        mode = route.mode
                    )
                    if (reCalculated != null) {
                        _activeNavRoute.value = reCalculated
                        _currentStepIndex.value = 0
                        speakCue("Re-routing...")
                        reCalculated.steps.firstOrNull()?.let { speakCue(it.cue) }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Unbind service to avoid memory leaks
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: Exception) {
            // Service might have already been unbound
        }
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
    }
}
