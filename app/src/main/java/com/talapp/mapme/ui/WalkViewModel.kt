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

@OptIn(ExperimentalCoroutinesApi::class)
class WalkViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: WalkRepository
    private val _locationService = MutableStateFlow<LocationService?>(null)
    val locationService = _locationService.asStateFlow()

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser = _currentUser.asStateFlow()

    private val _isDarkMap = MutableStateFlow(true)
    val isDarkMap = _isDarkMap.asStateFlow()

    private val _showWalks = MutableStateFlow(true)
    val showWalks = _showWalks.asStateFlow()

    private val _showDrives = MutableStateFlow(true)
    val showDrives = _showDrives.asStateFlow()

    fun toggleMapStyle() {
        _isDarkMap.value = !_isDarkMap.value
    }

    fun toggleShowWalks() {
        _showWalks.value = !_showWalks.value
    }

    fun toggleShowDrives() {
        _showDrives.value = !_showDrives.value
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
        val database = WalkDatabase.getDatabase(application)
        repository = WalkRepository(database.walkDao())

        // Bind to tracking service
        val intent = Intent(application, LocationService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Listen to Auth State changes
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
            syncWalks()
        }

        // Automatically sync when database changes
        viewModelScope.launch {
            repository.allWalks.collect {
                syncWalks()
            }
        }
    }

    // List of all completed walks
    val allWalks: StateFlow<List<Walk>> = repository.allWalks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Lifetime statistics
    val totalWalks: StateFlow<Int> = allWalks.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalDistanceMeters: StateFlow<Double> = allWalks.map { list ->
        list.sumOf { it.totalDistanceMeters }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalDurationMillis: StateFlow<Long> = allWalks.map { list ->
        list.sumOf { it.totalDurationMillis }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // Bound Service tracking states
    val isTracking: Flow<Boolean> = _locationService.flatMapLatest { service ->
        service?.isTracking ?: flowOf(false)
    }

    val activePoints: Flow<List<WalkPoint>> = _locationService.flatMapLatest { service ->
        service?.currentPoints ?: flowOf(emptyList())
    }

    val activePois: Flow<List<com.talapp.mapme.data.WalkPoi>> = _locationService.flatMapLatest { service ->
        service?.activePois ?: flowOf(emptyList())
    }

    fun addActivePoi(text: String?, imageBase64: String?) {
        _locationService.value?.addPoi(text, imageBase64)
    }

    val activeDistanceMeters: Flow<Double> = _locationService.flatMapLatest { service ->
        service?.totalDistanceMeters ?: flowOf(0.0)
    }

    val activeDurationSeconds: Flow<Long> = _locationService.flatMapLatest { service ->
        service?.elapsedTimeSeconds ?: flowOf(0L)
    }

    fun startWalk() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
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
        auth.signInAnonymously()
    }

    fun signInWithGoogleCredential(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
    }

    fun signOut() {
        auth.signOut()
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
            } catch (e: Exception) {
                e.printStackTrace()
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
    }
}
