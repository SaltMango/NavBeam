package io.github.saltmango.navbeam.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.saltmango.navbeam.data.SharedLocation
import io.github.saltmango.navbeam.dash.nav.GeoPoint
import io.github.saltmango.navbeam.dash.nav.Route
import io.github.saltmango.navbeam.dash.nav.Router
import io.github.saltmango.navbeam.util.LocationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.FlowPreview
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest

data class RouteStop(
    val id: String = java.util.UUID.randomUUID().toString(),
    val location: SharedLocation? = null
)

data class RouteState(
    val stops: List<RouteStop> = emptyList(),
    val pendingNavigate: Boolean = false,
    val route: Route? = null,
    val routing: Boolean = false,
    val distanceText: String? = null,      // "218 km"
    val durationText: String? = null,      // "4h 50m"
    val etaText: String? = null,           // "13:32"
    val trafficDelaySeconds: Double = 0.0, // extra seconds due to traffic vs static duration
) {
    val destination: SharedLocation?
        get() = stops.lastOrNull { it.location != null }?.location
}

@OptIn(FlowPreview::class)
class RouteViewModel(app: Application) : AndroidViewModel(app) {
    private val TAG = "RouteViewModel"
    private val _state = MutableStateFlow(RouteState())
    val state = _state.asStateFlow()

    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val repo = io.github.saltmango.navbeam.data.SyncRepository.get(app)

    private val placesClient by lazy { Places.createClient(app) }

    private val _predictions = MutableStateFlow<List<AutocompletePrediction>>(emptyList())
    val predictions = _predictions.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _saved = MutableStateFlow<List<io.github.saltmango.navbeam.data.SavedLocation>>(emptyList())
    val saved = _saved.asStateFlow()

    private val _savedRoutes = MutableStateFlow<List<io.github.saltmango.navbeam.data.SavedRoute>>(emptyList())
    val savedRoutes = _savedRoutes.asStateFlow()

    private val routePrefs = app.getSharedPreferences("route_settings", Context.MODE_PRIVATE)

    private val _recents = MutableStateFlow<List<SharedLocation>>(emptyList())
    val recents = _recents.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError = _searchError.asStateFlow()

    private val _activeStopIndex = MutableStateFlow<Int?>(null)
    val activeStopIndex = _activeStopIndex.asStateFlow()

    init {
        reloadSaved()
        loadRecents()
        viewModelScope.launch { repo.revision.collect { reloadSaved() } }

        // Debounce search query suggestions (saves API billing costs)
        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .distinctUntilChanged()
                .collect { query ->
                    fetchPredictions(query)
                }
        }
    }

    private fun reloadSaved() = viewModelScope.launch {
        _saved.value = withContext(Dispatchers.IO) { repo.savedLocations() }
        _savedRoutes.value = withContext(Dispatchers.IO) { repo.savedRoutes() }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _searchError.value = null
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _predictions.value = emptyList()
        _searchError.value = null
    }

    private fun fetchPredictions(query: String) {
        if (query.isBlank()) {
            _predictions.value = emptyList()
            _searchError.value = null
            return
        }
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .build()
        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                if (_searchQuery.value == query) {
                    _predictions.value = response.autocompletePredictions
                    _searchError.value = null
                }
            }
            .addOnFailureListener { exception ->
                if (_searchQuery.value == query) {
                    _searchError.value = "Search unavailable. Please check your network."
                }
                Log.e(TAG, "Prediction fetch failed", exception)
            }
    }

    private fun loadRecents() {
        val jsonStr = routePrefs.getString("recents_json", null) ?: return
        runCatching {
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<SharedLocation>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(SharedLocation(obj.getString("name"), obj.getDouble("lat"), obj.getDouble("lng")))
            }
            _recents.value = list
        }
    }

    private fun addRecent(loc: SharedLocation) {
        val list = _recents.value.toMutableList()
        list.removeAll { it.lat == loc.lat && it.lng == loc.lng }
        list.add(0, loc)
        if (list.size > 3) list.removeAt(list.size - 1)
        _recents.value = list

        runCatching {
            val arr = org.json.JSONArray()
            list.forEach { item ->
                val obj = org.json.JSONObject()
                obj.put("name", item.name)
                obj.put("lat", item.lat)
                obj.put("lng", item.lng)
                arr.put(obj)
            }
            routePrefs.edit().putString("recents_json", arr.toString()).apply()
        }
    }

    fun selectRecent(loc: SharedLocation) {
        val index = _activeStopIndex.value ?: 0
        setStopLocation(index, loc)
        _activeStopIndex.value = null
    }

    fun selectPrediction(prediction: AutocompletePrediction) {
        val placeId = prediction.placeId
        val fields = listOf(
            Place.Field.LOCATION,
            Place.Field.DISPLAY_NAME,
            Place.Field.FORMATTED_ADDRESS
        )
        val request = FetchPlaceRequest.newInstance(placeId, fields)
        placesClient.fetchPlace(request)
            .addOnSuccessListener { response ->
                val place = response.place
                val loc = place.location
                if (loc != null) {
                    val name = place.displayName ?: place.formattedAddress ?: prediction.getPrimaryText(null).toString()
                    val index = _activeStopIndex.value ?: 0
                    setStopLocation(index, SharedLocation(name, loc.latitude, loc.longitude))
                    _activeStopIndex.value = null
                    clearSearch()
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Fetch place failed", exception)
            }
    }

    fun saveCurrentDestination(name: String, note: String = "") {
        val d = _state.value.destination ?: return
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addSaved(name.ifBlank { d.name }, d.lat, d.lng, note) } }
    }

    fun renameSaved(loc: io.github.saltmango.navbeam.data.SavedLocation, name: String, note: String) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.renameSaved(loc, name, note) } }

    fun deleteSaved(loc: io.github.saltmango.navbeam.data.SavedLocation) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteSaved(loc) } }

    fun selectSaved(loc: io.github.saltmango.navbeam.data.SavedLocation) {
        val index = _activeStopIndex.value ?: 0
        setStopLocation(index, SharedLocation(name = loc.name, lat = loc.lat, lng = loc.lng))
        _activeStopIndex.value = null
    }

    /**
     * Handles incoming shared text by extracting coordinates directly.
     * The old complex URL expansion logic has been removed.
     */
    fun handleSharedText(text: String) {
        val coords = LocationParser.extractCoords(text)
        if (coords != null) {
            val dest = SharedLocation(name = "Shared location", lat = coords.first, lng = coords.second)
            _state.value = RouteState(
                stops = listOf(RouteStop(location = dest)),
                pendingNavigate = true
            )
            computeRouteFromCurrentStops()
        } else {
            Log.w(TAG, "Could not extract coordinates from shared text: $text")
        }
    }

    fun computeRoute(destLat: Double, destLng: Double, name: String? = null) {
        val initialName = name ?: "Dropped pin"
        val dest = SharedLocation(name = initialName, lat = destLat, lng = destLng)
        _state.value = _state.value.copy(
            stops = listOf(RouteStop(location = dest)),
            routing = true
        )
        computeRouteFromCurrentStops()
    }

    fun setActiveStopIndex(index: Int?) {
        _activeStopIndex.value = index
    }

    fun addStop() {
        val currentStops = _state.value.stops.toMutableList()
        currentStops.add(RouteStop(location = null))
        _state.value = _state.value.copy(stops = currentStops)
        _activeStopIndex.value = currentStops.lastIndex
        clearSearch()
    }

    fun removeStop(index: Int) {
        val currentStops = _state.value.stops.toMutableList()
        if (index in currentStops.indices) {
            currentStops.removeAt(index)
            _state.value = _state.value.copy(stops = currentStops)
            val active = _activeStopIndex.value
            if (active != null) {
                if (active >= currentStops.size) {
                    _activeStopIndex.value = if (currentStops.isNotEmpty()) currentStops.lastIndex else null
                } else if (active == index) {
                    _activeStopIndex.value = null
                }
            }
            computeRouteFromCurrentStops()
        }
    }

    fun moveStop(fromIndex: Int, toIndex: Int) {
        val currentStops = _state.value.stops.toMutableList()
        if (fromIndex in currentStops.indices && toIndex in currentStops.indices) {
            val removed = currentStops.removeAt(fromIndex)
            currentStops.add(toIndex, removed)
            _state.value = _state.value.copy(stops = currentStops)
            val active = _activeStopIndex.value
            if (active == fromIndex) {
                _activeStopIndex.value = toIndex
            } else if (active == toIndex) {
                _activeStopIndex.value = fromIndex
            }
        }
    }

    fun finalizeStopOrder() {
        computeRouteFromCurrentStops()
    }

    fun setStopLocation(index: Int, location: SharedLocation) {
        val currentStops = _state.value.stops.toMutableList()
        while (currentStops.size <= index) {
            currentStops.add(RouteStop())
        }
        currentStops[index] = currentStops[index].copy(location = location)
        _state.value = _state.value.copy(stops = currentStops)
        computeRouteFromCurrentStops()
    }

    fun selectLocationFromMap(lat: Double, lng: Double) {
        val index = _activeStopIndex.value ?: 0
        val currentStops = _state.value.stops
        if (currentStops.isEmpty()) {
            _activeStopIndex.value = 0
        }
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    val geocoder = android.location.Geocoder(getApplication(), Locale.getDefault())
                    val addresses = geocoder.getFromLocation(lat, lng, 1)
                    addresses?.firstOrNull()?.let { addr ->
                        val street = addr.thoroughfare ?: addr.featureName
                        val city = addr.locality ?: addr.subAdminArea
                        if (street != null && city != null) "$street, $city"
                        else street ?: city
                    }
                }.getOrNull()
            } ?: "Dropped pin"

            setStopLocation(_activeStopIndex.value ?: 0, SharedLocation(name, lat, lng))
            _activeStopIndex.value = null
        }
    }

    @SuppressLint("MissingPermission")
    fun computeRouteFromCurrentStops() {
        val resolvedStops = _state.value.stops.mapNotNull { it.location }
        if (resolvedStops.isEmpty()) {
            _state.value = _state.value.copy(
                route = null,
                routing = false,
                distanceText = null,
                durationText = null,
                etaText = null,
                trafficDelaySeconds = 0.0
            )
            return
        }

        _state.value = _state.value.copy(routing = true)

        val origin = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()

        if (origin == null) {
            Log.w(TAG, "computeRouteFromCurrentStops: no last-known location — GPS not fixed yet")
            _state.value = _state.value.copy(routing = false)
            return
        }

        viewModelScope.launch {
            val stopGeoPoints = resolvedStops.map { GeoPoint(it.lat, it.lng) }
            val r = Router.route(
                getApplication(),
                GeoPoint(origin.latitude, origin.longitude),
                stopGeoPoints
            )

            if (r != null) {
                val lastStop = resolvedStops.last()
                addRecent(lastStop)

                _state.value = _state.value.copy(
                    route = r,
                    routing = false,
                    distanceText = fmtKm(r.totalMeters),
                    durationText = fmtDuration(r.totalSeconds),
                    etaText = fmtEta(r.totalSeconds),
                    trafficDelaySeconds = maxOf(0.0, r.totalSeconds - r.staticSeconds),
                )
            } else {
                _state.value = _state.value.copy(routing = false)
            }
        }
    }

    fun saveActiveRouteOffline(routeName: String = "", dashViewModel: DashViewModel? = null) {
        val currentRoute = _state.value.route ?: return
        val currentDest = _state.value.destination ?: return
        val origin = currentRoute.geometry.firstOrNull() ?: return
        val dest = currentRoute.geometry.lastOrNull() ?: return

        viewModelScope.launch {
            val fromName = "Start Location"
            val toName = currentDest.name.ifBlank { "Destination" }
            val name = routeName.ifBlank { "$fromName to $toName" }

            val outboundJson = io.github.saltmango.navbeam.dash.nav.RouteJsonCodec.toJson(currentRoute)

            val returnRoute = withContext(Dispatchers.IO) {
                Router.route(getApplication(), dest, listOf(origin))
            } ?: io.github.saltmango.navbeam.dash.nav.RouteJsonCodec.createReverseRoute(currentRoute)

            val returnJson = io.github.saltmango.navbeam.dash.nav.RouteJsonCodec.toJson(returnRoute)

            val savedItem = io.github.saltmango.navbeam.data.SavedRoute(
                name = name,
                fromName = fromName,
                toName = toName,
                fromLat = origin.lat,
                fromLng = origin.lng,
                toLat = dest.lat,
                toLng = dest.lng,
                distanceMeters = currentRoute.totalMeters,
                durationSeconds = currentRoute.totalSeconds,
                routeJson = outboundJson,
                returnRouteJson = returnJson,
                isDownloaded = true
            )

            withContext(Dispatchers.IO) {
                repo.addSavedRoute(savedItem)
            }
            io.github.saltmango.navbeam.util.Telemetry.logEvent(
                "offline_route_saved",
                "distance_km" to (savedItem.distanceMeters / 1000.0),
            )

            dashViewModel?.prefetchRoute(currentRoute.geometry)
            dashViewModel?.prefetchRoute(returnRoute.geometry)
        }
    }

    fun loadSavedRoute(savedRoute: io.github.saltmango.navbeam.data.SavedRoute, isReturn: Boolean = false, dashViewModel: DashViewModel? = null) {
        val jsonStr = if (isReturn) savedRoute.returnRouteJson.ifBlank { savedRoute.routeJson } else savedRoute.routeJson
        val r = io.github.saltmango.navbeam.dash.nav.RouteJsonCodec.fromJson(jsonStr)
            ?: io.github.saltmango.navbeam.dash.nav.RouteJsonCodec.fromJson(savedRoute.routeJson)

        if (r == null) {
            Log.e(TAG, "Failed to load saved offline route ${savedRoute.name}")
            return
        }

        val destName = if (isReturn) savedRoute.fromName else savedRoute.toName
        val destLat = if (isReturn) savedRoute.fromLat else savedRoute.toLat
        val destLng = if (isReturn) savedRoute.fromLng else savedRoute.toLng

        val destLocation = SharedLocation(name = destName, lat = destLat, lng = destLng)

        _state.value = RouteState(
            stops = listOf(RouteStop(location = destLocation)),
            route = r,
            routing = false,
            distanceText = fmtKm(r.totalMeters),
            durationText = fmtDuration(r.totalSeconds),
            etaText = fmtEta(r.totalSeconds),
            trafficDelaySeconds = 0.0
        )

        dashViewModel?.setDestination(name = destName, lat = destLat, lng = destLng)
        dashViewModel?.prefetchRoute(r.geometry)
        io.github.saltmango.navbeam.util.Telemetry.logEvent(
            "offline_route_loaded",
            "direction" to if (isReturn) "return" else "outbound",
        )
    }

    fun deleteSavedRoute(savedRoute: io.github.saltmango.navbeam.data.SavedRoute) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.deleteSavedRoute(savedRoute)
            }
        }
    }

    fun onNavigated() { _state.value = _state.value.copy(pendingNavigate = false) }
    fun clear() { _state.value = RouteState(); _activeStopIndex.value = null }

    private fun fmtKm(m: Double) = "%.0f km".format(m / 1000.0)
    private fun fmtDuration(sec: Double): String {
        val total = (sec / 60.0).toInt()
        return if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
    }
    private fun fmtEta(sec: Double): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (sec * 1000).toLong()))
}
