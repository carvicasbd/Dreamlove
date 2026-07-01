package com.carles.movilidadseguraespana

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.*

/**
 * Repositorio diseñado para feeds DATEX II grandes.
 * Nunca convierte un XML completo en String: XmlPullParser consume el flujo directamente.
 */
class MemorySafeRepository(private val context: Context) {
    companion object {
        private const val RENFE_CERCANIAS = "https://gtfsrt.renfe.com/vehicle_positions.json"
        private const val RENFE_LD = "https://gtfsrt.renfe.com/vehicle_positions_LD.json"
        private const val RADARES_CATALUNYA = "https://transit.gencat.cat/web/.content/documents/seguretat_viaria/radars.txt"
        private const val RADARES_DGT = "https://infocar.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml"
        private const val INCIDENCIAS_DGT = "https://nap.dgt.es/datex2/v3/dgt/SituationPublication/datex2_v37.xml"
        private const val RECARGA_DGT = "https://infocar.dgt.es/datex2/v3/miterd/EnergyInfrastructureTablePublication/electrolineras.xml"
        private const val MAX_TEXT_CHARS = 12_000_000
        private const val MAX_DATEX_ITEMS = 5_000
    }

    suspend fun load(profile: MobilityProfile, demoMode: Boolean): Pair<List<MapItem>, List<SourceState>> {
        if (demoMode) return demoItems(profile) to listOf(
            SourceState("MODO DEMOSTRACIÓN", "Datos simulados claramente separados", true, System.currentTimeMillis())
        )

        val results = mutableListOf<Pair<List<MapItem>, SourceState>>()

        if (profile == MobilityProfile.TRAIN || profile == MobilityProfile.PUBLIC_TRANSPORT) {
            results += safe("Renfe Cercanías/Rodalies") { parseRenfe(RENFE_CERCANIAS, "Cercanías/Rodalies") }
            results += safe("Renfe AV/LD/MD") { parseRenfe(RENFE_LD, "Alta, Media y Larga Distancia") }
        }

        if (profile in listOf(MobilityProfile.CAR, MobilityProfile.TRUCK, MobilityProfile.MOTORCYCLE)) {
            // Se procesan en serie para impedir que varios XML grandes ocupen memoria simultáneamente.
            results += safe("Radares DGT") { parseDatex(RADARES_DGT, ItemType.RADAR_FIXED, "DGT", "Radar fijo") }
            results += safe("Radares Cataluña") { parseCataloniaRadars() }
            results += safe("Incidencias DGT") { parseDatex(INCIDENCIAS_DGT, ItemType.TRAFFIC, "DGT NAP", "Incidencia de tráfico") }
            results += safe("Recarga eléctrica") { parseDatex(RECARGA_DGT, ItemType.CHARGER, "DGT NAP / MITERD", "Punto de recarga") }
        }

        if (profile == MobilityProfile.BICYCLE) {
            results += emptyList<MapItem>() to SourceState("GBFS", "Selecciona proveedor en Ajustes", false)
        }
        if (profile == MobilityProfile.PUBLIC_TRANSPORT) {
            results += emptyList<MapItem>() to SourceState("CRTM Madrid", "GTFS según disponibilidad", false)
            results += emptyList<MapItem>() to SourceState("EMT Madrid", "Requiere credenciales gratuitas", false)
            results += emptyList<MapItem>() to SourceState("TRAM Barcelona", "Proveedor pendiente de recurso estable", false)
            results += emptyList<MapItem>() to SourceState("Bilbobus", "Proveedor GTFS-Realtime preparado", false)
        }
        results += emptyList<MapItem>() to SourceState("AEMET", "Requiere API key gratuita", false)
        results += emptyList<MapItem>() to SourceState("DGT 3.0", "Pendiente de autorización", false)

        return results.flatMap { it.first } to results.map { it.second }
    }

    private suspend fun safe(
        name: String,
        loader: suspend () -> List<MapItem>
    ): Pair<List<MapItem>, SourceState> = try {
        val items = loader()
        items to SourceState(
            name,
            if (items.isEmpty()) "Sin datos publicados" else "Activo: ${items.size} elementos",
            true,
            System.currentTimeMillis()
        )
    } catch (error: Exception) {
        emptyList<MapItem>() to SourceState(name, "No disponible: ${error.message?.take(80) ?: "error"}", false)
    }

    private suspend fun parseRenfe(url: String, service: String): List<MapItem> = withContext(Dispatchers.IO) {
        val root = JSONObject(downloadSmallText(url, 25_000))
        val entities = root.optJSONArray("entity") ?: return@withContext emptyList()
        val headerTimestamp = root.optJSONObject("header")?.optLong("timestamp", 0L) ?: 0L
        buildList {
            for (index in 0 until entities.length()) {
                val entity = entities.optJSONObject(index) ?: continue
                val vehicle = entity.optJSONObject("vehicle") ?: continue
                val position = vehicle.optJSONObject("position") ?: continue
                if (!position.has("latitude") || !position.has("longitude")) continue
                val trip = vehicle.optJSONObject("trip")
                val descriptor = vehicle.optJSONObject("vehicle")
                val route = firstText(trip, "routeId", "route_id")
                val tripId = firstText(trip, "tripId", "trip_id")
                val label = firstText(descriptor, "label", "id")
                add(
                    MapItem(
                        id = "renfe_${service}_${entity.optString("id", index.toString())}",
                        type = ItemType.TRAIN,
                        title = label ?: route ?: tripId ?: "Tren",
                        description = "$service · Posición oficial publicada por Renfe",
                        latitude = position.optDouble("latitude"),
                        longitude = position.optDouble("longitude"),
                        source = "Renfe Data",
                        timestamp = vehicle.optLong("timestamp", headerTimestamp).let {
                            if (it > 0L) it * 1000L else System.currentTimeMillis()
                        },
                        route = route
                    )
                )
            }
        }
    }

    private suspend fun parseCataloniaRadars(): List<MapItem> = withContext(Dispatchers.IO) {
        val lines = downloadSmallText(RADARES_CATALUNYA, 25_000)
            .lineSequence()
            .filter { it.isNotBlank() && !it.trim().startsWith("#") }

        val result = mutableListOf<MapItem>()
        lines.forEachIndexed { index, line ->
            val parts = splitFlexible(line)
            if (parts.size < 5) return@forEachIndexed
            val numbers = parts.mapNotNull(::parseNumber)
            var lat = numbers.firstOrNull { it in 35.0..44.5 }
            var lon = numbers.firstOrNull { it in -10.0..5.0 }
            val x = numbers.firstOrNull { it in 100_000.0..900_000.0 }
            val y = numbers.firstOrNull { it in 3_800_000.0..5_000_000.0 }
            if ((lat == null || lon == null) && x != null && y != null) {
                val converted = utm31ToWgs84(x, y)
                lat = converted.first
                lon = converted.second
            }
            if (lat == null || lon == null || lat !in 39.0..43.2 || lon !in -0.5..3.6) return@forEachIndexed
            val road = parts.firstOrNull { it.matches(Regex("[A-Za-z]+[- ]?\\d+.*")) } ?: "Carretera"
            val speed = numbers.map { it.toInt() }.firstOrNull { it in 20..130 }
            result += MapItem(
                id = "sct_${index}_${lat}_${lon}",
                type = ItemType.RADAR_FIXED,
                title = "Radar publicado · $road",
                description = speed?.let { "Límite publicado: $it km/h" } ?: "Velocidad no identificada",
                latitude = lat,
                longitude = lon,
                source = "Servei Català de Trànsit",
                severity = Severity.CAUTION,
                speedLimit = speed,
                route = road
            )
        }
        result.distinctBy { "${it.route}_${(it.latitude * 10_000).roundToInt()}_${(it.longitude * 10_000).roundToInt()}" }
    }

    private suspend fun parseDatex(
        url: String,
        type: ItemType,
        source: String,
        fallbackTitle: String
    ): List<MapItem> = withContext(Dispatchers.IO) {
        openNetworkStream(url, 45_000).use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, null) }
            val result = ArrayList<MapItem>(minOf(MAX_DATEX_ITEMS, 2_000))
            var event = parser.eventType
            var lat: Double? = null
            var lon: Double? = null
            var title: String? = null
            var description: String? = null
            var road: String? = null
            var speed: Int? = null
            var recordDepth = -1
            var recordTag: String? = null
            var sequence = 0
            val textTags = setOf(
                "latitude", "longitude", "value", "roadNumber", "roadName",
                "locationDescriptor", "description", "comment", "predefinedLocationName", "name"
            )

            while (event != XmlPullParser.END_DOCUMENT && result.size < MAX_DATEX_ITEMS) {
                val tag = parser.name?.substringAfter(':')
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (tag != null && isRecordTag(tag)) {
                            recordDepth = parser.depth
                            recordTag = tag
                            lat = null
                            lon = null
                            title = null
                            description = null
                            road = null
                            speed = null
                        }
                        if (tag in textTags) {
                            val value = runCatching { parser.nextText().trim() }.getOrDefault("")
                            when (tag) {
                                "latitude" -> parseNumber(value)?.let { lat = it }
                                "longitude" -> parseNumber(value)?.let { lon = it }
                                "roadNumber", "roadName" -> if (value.isNotBlank()) road = value
                                "predefinedLocationName", "name", "locationDescriptor" -> if (value.isNotBlank() && title == null) title = value
                                "description", "comment" -> if (value.isNotBlank() && description == null) description = value
                                "value" -> parseNumber(value)?.toInt()?.takeIf { it in 10..150 }?.let { speed = it }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (recordDepth > 0 && parser.depth == recordDepth && tag == recordTag) {
                            val safeLat = lat
                            val safeLon = lon
                            if (safeLat != null && safeLon != null && safeLat in 27.0..44.5 && safeLon in -19.0..5.0) {
                                result += MapItem(
                                    id = "${type.name.lowercase()}_${sequence++}_${safeLat}_${safeLon}",
                                    type = type,
                                    title = (title ?: road ?: fallbackTitle).take(100),
                                    description = (description ?: buildString {
                                        append(fallbackTitle)
                                        road?.let { append(" · ").append(it) }
                                        speed?.let { append(" · ").append(it).append(" km/h") }
                                    }).take(240),
                                    latitude = safeLat,
                                    longitude = safeLon,
                                    source = source,
                                    severity = when (type) {
                                        ItemType.TRAFFIC -> Severity.IMPORTANT
                                        ItemType.RADAR_FIXED -> Severity.CAUTION
                                        else -> Severity.INFO
                                    },
                                    speedLimit = speed,
                                    route = road
                                )
                            }
                            recordDepth = -1
                            recordTag = null
                        }
                    }
                }
                event = parser.next()
            }
            result.distinctBy { "${(it.latitude * 100_000).roundToInt()}_${(it.longitude * 100_000).roundToInt()}_${it.type}" }
        }
    }

    private fun downloadSmallText(url: String, timeout: Int): String {
        openNetworkStream(url, timeout).use { stream ->
            val reader = InputStreamReader(stream, Charsets.UTF_8)
            val buffer = CharArray(16 * 1024)
            val result = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (result.length + count > MAX_TEXT_CHARS) {
                    error("Respuesta demasiado grande para cargarse como texto")
                }
                result.append(buffer, 0, count)
            }
            return result.toString()
        }
    }

    private fun openNetworkStream(url: String, timeout: Int): InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Accept-Encoding", "gzip")
        connection.setRequestProperty("User-Agent", "MovilidadSeguraEspana/1.0.1 private-use")
        val status = connection.responseCode
        if (status !in 200..299) {
            connection.disconnect()
            error("HTTP $status")
        }
        val raw = connection.inputStream
        val decoded = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(raw, 64 * 1024) else raw
        return object : FilterInputStream(BufferedInputStream(decoded, 64 * 1024)) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    private fun firstText(obj: JSONObject?, first: String, second: String): String? {
        if (obj == null) return null
        return obj.optString(first).takeIf { it.isNotBlank() && it != "null" }
            ?: obj.optString(second).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun isRecordTag(tag: String): Boolean {
        val value = tag.lowercase(Locale.ROOT)
        return value.contains("predefinedlocation") || value.contains("situationrecord") ||
            value.contains("energyinfrastructuresite") || value.contains("parkingrecord")
    }

    private fun splitFlexible(line: String): List<String> {
        val delimiter = when {
            line.count { it == ';' } >= 3 -> ';'
            line.count { it == '\t' } >= 3 -> '\t'
            line.count { it == '|' } >= 3 -> '|'
            else -> ','
        }
        return line.split(delimiter).map { it.trim().trim('"') }
    }

    private fun parseNumber(raw: String): Double? = raw.trim()
        .replace(" ", "")
        .replace(',', '.')
        .replace(Regex("[^0-9+\\-.]"), "")
        .toDoubleOrNull()

    private fun utm31ToWgs84(easting: Double, northing: Double): Pair<Double, Double> {
        val a = 6378137.0
        val eccentricity = 0.00669438
        val k0 = 0.9996
        val x = easting - 500000.0
        val m = northing / k0
        val mu = m / (a * (1 - eccentricity / 4 - 3 * eccentricity.pow(2) / 64 - 5 * eccentricity.pow(3) / 256))
        val e1 = (1 - sqrt(1 - eccentricity)) / (1 + sqrt(1 - eccentricity))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            151 * e1.pow(3) / 96 * sin(6 * mu)
        val prime = eccentricity / (1 - eccentricity)
        val n1 = a / sqrt(1 - eccentricity * sin(phi1).pow(2))
        val t1 = tan(phi1).pow(2)
        val c1 = prime * cos(phi1).pow(2)
        val r1 = a * (1 - eccentricity) / (1 - eccentricity * sin(phi1).pow(2)).pow(1.5)
        val d = x / (n1 * k0)
        val lat = phi1 - n1 * tan(phi1) / r1 *
            (d.pow(2) / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1.pow(2) - 9 * prime) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1.pow(2) - 252 * prime - 3 * c1.pow(2)) * d.pow(6) / 720)
        val lon = (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
            (5 - 2 * c1 + 28 * t1 - 3 * c1.pow(2) + 8 * prime + 24 * t1.pow(2)) * d.pow(5) / 120) / cos(phi1)
        return Math.toDegrees(lat) to 3.0 + Math.toDegrees(lon)
    }

    private fun demoItems(profile: MobilityProfile): List<MapItem> {
        val data = listOf(
            MapItem("d1", ItemType.TRAIN, "Tren 03142", "Próxima estación: Zaragoza · +4 min", 41.6488, -0.8891, "MODO DEMOSTRACIÓN", isDemo = true),
            MapItem("d2", ItemType.RADAR_FIXED, "Radar fijo A-2", "Límite publicado: 100 km/h", 41.573, 1.874, "MODO DEMOSTRACIÓN", severity = Severity.CAUTION, speedLimit = 100, isDemo = true),
            MapItem("d3", ItemType.TRAFFIC, "Retención por accidente", "Carril derecho afectado", 40.45, -3.68, "MODO DEMOSTRACIÓN", severity = Severity.IMPORTANT, isDemo = true),
            MapItem("d4", ItemType.CHARGER, "Recarga rápida", "4 conectores disponibles", 40.4168, -3.7038, "MODO DEMOSTRACIÓN", isDemo = true),
            MapItem("d5", ItemType.BIKE, "Bicicletas públicas", "8 bicicletas · 5 plazas libres", 41.3874, 2.1686, "MODO DEMOSTRACIÓN", isDemo = true),
            MapItem("d6", ItemType.WEATHER, "Aviso de viento", "Rachas fuertes durante la tarde", 42.15, -0.41, "MODO DEMOSTRACIÓN", severity = Severity.CAUTION, isDemo = true)
        )
        return when (profile) {
            MobilityProfile.TRAIN -> data.filter { it.type == ItemType.TRAIN }
            MobilityProfile.CAR, MobilityProfile.TRUCK, MobilityProfile.MOTORCYCLE -> data.filter {
                it.type in listOf(ItemType.RADAR_FIXED, ItemType.TRAFFIC, ItemType.CHARGER, ItemType.WEATHER)
            }
            MobilityProfile.BICYCLE -> data.filter { it.type in listOf(ItemType.BIKE, ItemType.WEATHER) }
            MobilityProfile.PUBLIC_TRANSPORT -> data.filter { it.type in listOf(ItemType.TRAIN, ItemType.BIKE) }
        }
    }
}
