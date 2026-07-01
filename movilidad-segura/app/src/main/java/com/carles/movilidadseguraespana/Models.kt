package com.carles.movilidadseguraespana

enum class MobilityProfile(val title: String, val subtitle: String, val emoji: String) {
    TRAIN("Voy en tren", "Trenes, retrasos y próximas estaciones", "🚆"),
    CAR("Voy en coche", "Tráfico, radares, límites y recarga", "🚗"),
    TRUCK("Soy camionero", "Incidencias, restricciones y áreas útiles", "🚛"),
    MOTORCYCLE("Voy en moto", "Peligros, tiempo y tramos de riesgo", "🏍️"),
    BICYCLE("Voy en bicicleta", "Bicis públicas, estaciones y meteorología", "🚲"),
    PUBLIC_TRANSPORT("Uso transporte público", "Autobús, metro, tranvía y Cercanías", "🚌")
}

enum class ItemType {
    TRAIN, BUS, TRAM, RADAR_FIXED, RADAR_SECTION, MOBILE_ZONE,
    TRAFFIC, CHARGER, PARKING, BIKE, WEATHER, STOP, INFO
}

enum class Severity { INFO, CAUTION, IMPORTANT, DANGER }

data class MapItem(
    val id: String,
    val type: ItemType,
    val title: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val source: String,
    val timestamp: Long = System.currentTimeMillis(),
    val severity: Severity = Severity.INFO,
    val speedLimit: Int? = null,
    val route: String? = null,
    val direction: String? = null,
    val isDemo: Boolean = false
)

data class SourceState(
    val name: String,
    val status: String,
    val active: Boolean,
    val lastUpdate: Long? = null
)

data class MobilityUiState(
    val selectedProfile: MobilityProfile? = null,
    val items: List<MapItem> = emptyList(),
    val sources: List<SourceState> = emptyList(),
    val loading: Boolean = false,
    val message: String = "Selecciona cómo te desplazas hoy",
    val simpleMode: Boolean = false,
    val demoMode: Boolean = false,
    val radarDistanceMeters: Int = 750,
    val speedTolerance: Int = 3,
    val currentLatitude: Double? = null,
    val currentLongitude: Double? = null,
    val currentSpeedKmh: Float? = null,
    val journeyActive: Boolean = false
)
