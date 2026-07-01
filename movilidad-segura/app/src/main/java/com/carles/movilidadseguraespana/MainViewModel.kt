package com.carles.movilidadseguraespana

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MobilityRepository(application)
    private val prefs = application.getSharedPreferences("movilidad_settings", 0)
    private val _state = MutableStateFlow(
        MobilityUiState(
            simpleMode = prefs.getBoolean("simple_mode", false),
            demoMode = prefs.getBoolean("demo_mode", false),
            radarDistanceMeters = prefs.getInt("radar_distance", 750),
            speedTolerance = prefs.getInt("speed_tolerance", 3)
        )
    )
    val state: StateFlow<MobilityUiState> = _state.asStateFlow()

    fun selectProfile(profile: MobilityProfile) {
        _state.value = _state.value.copy(selectedProfile = profile)
        refresh()
    }

    fun refresh() {
        val profile = _state.value.selectedProfile ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = "Actualizando fuentes oficiales…")
            val (items, sources) = repository.load(profile, _state.value.demoMode)
            val active = sources.count { it.active }
            _state.value = _state.value.copy(
                items = items,
                sources = sources,
                loading = false,
                message = if (items.isEmpty()) "No hay datos cartográficos disponibles ahora" else "$active fuentes activas · ${items.size} elementos"
            )
        }
    }

    fun setSimpleMode(enabled: Boolean) {
        prefs.edit().putBoolean("simple_mode", enabled).apply()
        _state.value = _state.value.copy(simpleMode = enabled)
    }

    fun setDemoMode(enabled: Boolean) {
        prefs.edit().putBoolean("demo_mode", enabled).apply()
        _state.value = _state.value.copy(demoMode = enabled)
        if (_state.value.selectedProfile != null) refresh()
    }

    fun setRadarDistance(distance: Int) {
        prefs.edit().putInt("radar_distance", distance).apply()
        _state.value = _state.value.copy(radarDistanceMeters = distance)
    }

    fun setSpeedTolerance(tolerance: Int) {
        prefs.edit().putInt("speed_tolerance", tolerance).apply()
        _state.value = _state.value.copy(speedTolerance = tolerance)
    }

    fun setJourneyActive(active: Boolean) {
        _state.value = _state.value.copy(journeyActive = active)
    }

    fun updateLocation(location: Location) {
        _state.value = _state.value.copy(
            currentLatitude = location.latitude,
            currentLongitude = location.longitude,
            currentSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else null
        )
    }

    fun nearestRadar(): Pair<MapItem, Int>? {
        val state = _state.value
        val lat = state.currentLatitude ?: return null
        val lon = state.currentLongitude ?: return null
        return state.items.asSequence()
            .filter { it.type in listOf(ItemType.RADAR_FIXED, ItemType.RADAR_SECTION, ItemType.MOBILE_ZONE) }
            .map { item ->
                val result = FloatArray(1)
                Location.distanceBetween(lat, lon, item.latitude, item.longitude, result)
                item to result[0].roundToInt()
            }
            .filter { it.second <= state.radarDistanceMeters }
            .minByOrNull { it.second }
    }
}
