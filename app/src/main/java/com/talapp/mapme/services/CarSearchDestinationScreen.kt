package com.talapp.mapme.services

import android.location.Location
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import java.util.Locale

/**
 * Android Auto Destination Search Screen:
 * - Search by typing or car voice input (steering wheel microphone automatically supported by SearchTemplate).
 * - Instant 1-tap category shortcuts for Gas, Parking, Food, etc.
 * - Distance calculation from current vehicle location for all results.
 * - 1-tap turn-by-turn route calculation and navigation launch.
 */
class CarSearchDestinationScreen(
    carContext: CarContext,
    private val session: MapMeCarSession
) : Screen(carContext), DefaultLifecycleObserver, SearchTemplate.SearchCallback {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null

    private var currentQuery: String = ""
    private var searchResults: List<NavSearchResult> = emptyList()
    private var isSearching: Boolean = false
    private var isCalculatingRoute: Boolean = false

    private val quickCategories = listOf(
        "Gas Station ⛽" to "gas station",
        "Parking 🅿️" to "parking",
        "Coffee & Food ☕" to "coffee",
        "Supermarket 🛒" to "supermarket",
        "Hospital 🏥" to "hospital"
    )

    init {
        lifecycle.addObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        screenScope.cancel()
    }

    override fun onSearchTextChanged(searchText: String) {
        currentQuery = searchText
        if (searchText.trim().length >= 2) {
            triggerDebouncedSearch(searchText.trim())
        } else {
            searchJob?.cancel()
            searchResults = emptyList()
            isSearching = false
            invalidate()
        }
    }

    override fun onSearchSubmitted(searchText: String) {
        currentQuery = searchText
        if (searchText.isNotBlank()) {
            executeSearch(searchText.trim())
        }
    }

    private fun triggerDebouncedSearch(query: String) {
        searchJob?.cancel()
        searchJob = screenScope.launch {
            delay(400L) // 400ms debounce
            executeSearch(query)
        }
    }

    private fun executeSearch(query: String) {
        searchJob?.cancel()
        isSearching = true
        invalidate()

        val (biasLat, biasLon) = if (session.mapSurfaceRenderer?.hasLocation() == true) {
            val (lat, lon) = session.mapSurfaceRenderer!!.getVehicleLocation()
            Pair(lat, lon)
        } else {
            Pair(null, null)
        }

        searchJob = screenScope.launch {
            val results = NavigationEngine.searchDestinations(
                query = query,
                biasLat = biasLat,
                biasLon = biasLon,
                limit = 6
            )
            withContext(Dispatchers.Main) {
                searchResults = results
                isSearching = false
                invalidate()
            }
        }
    }

    private fun onDestinationSelected(res: NavSearchResult) {
        if (isCalculatingRoute) return
        isCalculatingRoute = true
        CarToast.makeText(carContext, "Calculating route to ${res.title}...", CarToast.LENGTH_SHORT).show()
        invalidate()

        screenScope.launch {
            val (startLat, startLon) = if (session.mapSurfaceRenderer?.hasLocation() == true) {
                session.mapSurfaceRenderer!!.getVehicleLocation()
            } else {
                Pair(32.0853, 34.7818) // Tel Aviv fallback
            }

            val route = NavigationEngine.calculateRoute(
                startLat = startLat,
                startLon = startLon,
                destLat = res.latitude,
                destLon = res.longitude,
                destinationTitle = res.title
            )

            withContext(Dispatchers.Main) {
                isCalculatingRoute = false
                if (route != null) {
                    session.startNavigation(route)
                    CarToast.makeText(carContext, "Starting navigation to ${res.title}", CarToast.LENGTH_LONG).show()
                    screenManager.pop()
                } else {
                    CarToast.makeText(carContext, "Could not find driving route. Please try another destination.", CarToast.LENGTH_LONG).show()
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        when {
            isCalculatingRoute -> {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("Calculating optimal route...")
                        .addText("Finding best roads & maneuvers")
                        .build()
                )
            }
            searchResults.isNotEmpty() -> {
                for (res in searchResults.take(6)) {
                    val distStr = if (session.mapSurfaceRenderer?.hasLocation() == true) {
                        val (vLat, vLon) = session.mapSurfaceRenderer!!.getVehicleLocation()
                        val distMeters = FloatArray(1)
                        Location.distanceBetween(vLat, vLon, res.latitude, res.longitude, distMeters)
                        val m = distMeters[0]
                        if (m < 1000) String.format(Locale.US, "%.0f m • ", m)
                        else String.format(Locale.US, "%.1f km • ", m / 1000.0)
                    } else ""

                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle(res.title)
                            .addText("$distStr${res.subtitle}")
                            .setOnClickListener {
                                onDestinationSelected(res)
                            }
                            .build()
                    )
                }
            }
            currentQuery.isNotBlank() && !isSearching -> {
                listBuilder.setNoItemsMessage("No destinations found for '$currentQuery'")
            }
            else -> {
                // Quick Category Presets when search bar is idle
                for ((label, q) in quickCategories) {
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle(label)
                            .addText("Tap to search nearby")
                            .setOnClickListener {
                                currentQuery = q
                                executeSearch(q)
                            }
                            .build()
                    )
                }
            }
        }

        return SearchTemplate.Builder(this)
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search destination or speak...")
            .setShowKeyboardByDefault(currentQuery.isBlank())
            .setLoading(isSearching)
            .setItemList(listBuilder.build())
            .build()
    }
}
