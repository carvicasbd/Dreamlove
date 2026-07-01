package com.carles.movilidadseguraespana

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.*

class MobilityRepository(private val context: Context) {
    companion object {
        const val RENFE_CERCANIAS = "https://gtfsrt.renfe.com/vehicle_positions.json"
        const val RENFE_LD = "https://gtfsrt.renfe.com/vehicle_positions_LD.json"
        const val RADARES_CATALUNYA = "https://transit.gencat.cat/web/.content/documents/seguretat_viaria/radars.txt"
        const val RADARES_DGT = "https://infocar.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml"
        const val INCIDENCIAS_DGT = "https://nap.dgt.es/datex2/v3/dgt/SituationPublication/datex2_v37.xml"
        const val RECARGA_DGT = "https://infocar.dgt.es/datex2/v3/miterd/EnergyInfrastructureTablePublication/electrolineras.xml"
    }

    suspend fun load(profile: MobilityProfile, demoMode: Boolean): Pair<List<MapItem>, List<SourceState>> = coroutineScope {
        if (demoMode) return@coroutineScope demoItems(profile) to demoSources()

        val tasks = mutableListOf<kotlinx.coroutines.Deferred<Pair<List<MapItem>, SourceState>>>()
        if (profile == MobilityProfile.TRAIN || profile == MobilityProfile.PUBLIC_TRANSPORT) {
            tasks += async { safeSource("Renfe Cercanías/Rodalies") { parseRenfe(RENFE_CERCANIAS, "Cercanías/Rodalies") } }
            tasks += async { safeSource("Renfe AV/LD/MD") { parseRenfe(RENFE_LD, "Alta, Media y Larga Distancia") } }
        }
        if (profile in listOf(MobilityProfile.CAR, MobilityProfile.TRUCK, MobilityProfile.MOTORCYCLE)) {
            tasks += async { safeSource("Radares DGT") { parseGenericDatex(RADARES_DGT, ItemType.RADAR_FIXED, "DGT", "Radar fijo") } }
            tasks += async { safeSource("Radares Cataluña") { parseCataloniaRadars() } }
            tasks += async { safeSource("Incidencias DGT") { parseGenericDatex(INCIDENCIAS_DGT, ItemType.TRAFFIC, "DGT NAP", "Incidencia de tráfico") } }
            tasks += async { safeSource("Recarga eléctrica") { parseGenericDatex(RECARGA_DGT, ItemType.CHARGER, "DGT NAP / MITERD", "Punto de recarga") } }
        }
        if (profile == MobilityProfile.BICYCLE) {
            tasks += async { Pair(emptyList(), SourceState("GBFS", "Selecciona proveedor en Ajustes", false)) }
        }
        if (profile == MobilityProfile.PUBLIC_TRANSPORT) {
            tasks += async { Pair(emptyList(), SourceState("CRTM Madrid", "Catálogo preparado; GTFS dinámico según disponibilidad", false)) }
            tasks += async { Pair(emptyList(), SourceState("EMT Madrid", "Requiere credenciales gratuitas", false)) }
            tasks += async { Pair(emptyList(), SourceState("TRAM Barcelona", "Proveedor preparado; recurso sujeto al portal", false)) }
            tasks += async { Pair(emptyList(), SourceState("Bilbobus", "Proveedor GTFS-Realtime preparado", false)) }
        }
        tasks += async { Pair(emptyList(), SourceState("AEMET", "Requiere API key gratuita", false)) }
        tasks += async { Pair(emptyList(), SourceState("DGT 3.0", "Pendiente de autorización", false)) }

        val results = tasks.awaitAll()
        results.flatMap { it.first } to results.map { it.second }
    }

    private suspend fun safeSource(
        name: String,
        loader: suspend () -> List<MapItem>
    ): Pair<List<MapItem>, SourceState> = try {
        val items = loader()
        items to SourceState(name, if (items.isEmpty()) "Sin datos publicados" else "Activo: ${items.size} elementos", true, System.currentTimeMillis())
    } catch (e: Exception) {
        emptyList<MapItem>() to SourceState(name, "No disponible: ${e.message?.take(70) ?: "error"}", false)
    }

    private suspend fun parseRenfe(url: String, service: String): List<MapItem> = withContext(Dispatchers.IO) {
        val text = download(url, cacheKey(url), 25_000)
        val root = JSONObject(text)
        val entities = root.optJSONArray("entity") ?: return@withContext emptyList()
        val headerTs = root.optJSONObject("header")?.optLong("timestamp", 0L) ?: 0L
        buildList {
            for (i in 0 until entities.length()) {
                val entity = entities.optJSONObject(i) ?: continue
                val vehicle = entity.optJSONObject("vehicle") ?: continue
                val position = vehicle.optJSONObject("position") ?: continue
                if (!position.has("latitude") || !position.has("longitude")) continue
                val trip = vehicle.optJSONObject("trip")
                val descriptor = vehicle.optJSONObject("vehicle")
                val route = firstText(trip, "routeId", "route_id")
                val tripId = firstText(trip, "tripId", "trip_id")
                val label = firstText(descriptor, "label", "id")
                val title = label ?: route ?: tripId ?: entity.optString("id", "Tren")
                add(
                    MapItem(
                        id = "renfe_${service}_${entity.optString("id", i.toString())}",
                        type = ItemType.TRAIN,
                        title = title,
                        description = "$service · Posición oficial publicada por Renfe",
                        latitude = position.optDouble("latitude"),
                        longitude = position.optDouble("longitude"),
                        source = "Renfe Data",
                        timestamp = vehicle.optLong("timestamp", headerTs).let { if (it > 0) it * 1000 else System.currentTimeMillis() },
                        route = route
                    )
                )
            }
        }
    }

    private suspend fun parseCataloniaRadars(): List<MapItem> = withContext(Dispatchers.IO) {
        val text = download(RADARES_CATALUNYA, "radars_catalunya", 25_000)
        val lines = text.lineSequence().filter { it.isNotBlank() && !it.trim().startsWith("#") }.toList()
        val items = mutableListOf<MapItem>()
        for ((index, line) in lines.withIndex()) {
            val parts = splitFlexible(line)
            if (parts.size < 5) continue
            val numbers = parts.mapIndexedNotNull { idx, value -> parseNumber(value)?.let { idx to it } }
            var lat: Double? = null
            var lon: Double? = null
            var x: Double? = null
            var y: Double? = null
            for ((_, n) in numbers) {
                if (n in 35.0..44.5 && lat == null) lat = n
                else if (n in -10.0..5.0 && lon == null) lon = n
                if (n in 100_000.0..900_000.0) x = n
                if (n in 3_800_000.0..5_000_000.0) y = n
            }
            if ((lat == null || lon == null) && x != null && y != null) {
                val converted = utm31ToWgs84(x, y)
                lat = converted.first
                lon = converted.second
            }
            if (lat == null || lon == null || lat !in 39.0..43.2 || lon !in -0.5..3.6) continue

            val road = parts.firstOrNull { it.matches(Regex("[A-Za-z]+[- ]?\\d+.*")) } ?: "Carretera"
            val speed = numbers.map { it.second.toInt() }.firstOrNull { it in 20..130 }
            items += MapItem(
                id = "sct_${index}_${lat}_${lon}",
                type = ItemType.RADAR_FIXED,
                title = "Radar publicado · $road",
                description = if (speed != null) "Límite publicado: $speed km/h" else "Velocidad no identificada",
                latitude = lat,
                longitude = lon,
                source = "Servei Català de Trànsit",
                speedLimit = speed,
                route = road,
                severity = Severity.CAUTION
            )
        }
        items.distinctBy { "${it.route}_${(it.latitude * 10000).roundToInt()}_${(it.longitude * 10000).roundToInt()}" }
    }

    private suspend fun parseGenericDatex(
        url: String,
        type: ItemType,
        source: String,
        fallbackTitle: String
    ): List<MapItem> = withContext(Dispatchers.IO) {
        val xml = download(url, cacheKey(url), 35_000)
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val result = mutableListOf<MapItem>()
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
        val textTags = setOf("latitude", "longitude", "value", "roadNumber", "roadName", "locationDescriptor", "description", "comment", "predefinedLocationName", "name")

        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':')
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (tag != null && isRecordTag(tag)) {
                        recordDepth = parser.depth
                        recordTag = tag
                        lat = null; lon = null; title = null; description = null; road = null; speed = null
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
                        val la = lat; val lo = lon
                        if (la != null && lo != null && la in 27.0..44.5 && lo in -19.0..5.0) {
                            val safeTitle = (title ?: road ?: fallbackTitle).take(100)
                            result += MapItem(
                                id = "${type.name.lowercase()}_${sequence++}_${la}_${lo}",
                                type = type,
                                title = safeTitle,
                                description = (description ?: buildString {
                                    append(fallbackTitle)
                                    if (road != null) append(" · ").append(road)
                                    if (speed != null) append(" · ").append(speed).append(" km/h")
                                }).take(240),
                                latitude = la,
                                longitude = lo,
                                source = source,
                                speedLimit = speed,
                                route = road,
                                severity = when (type) {
                                    ItemType.TRAFFIC -> Severity.IMPORTANT
                                    ItemType.RADAR_FIXED -> Severity.CAUTION
                                    else -> Severity.INFO
                                }
                            )
                        }
                        recordDepth = -1
                        recordTag = null
                    }
                }
            }
            event = parser.next()
        }
        result.distinctBy { "${(it.latitude * 100000).roundToInt()}_${(it.longitude * 100000).roundToInt()}_${it.type}" }
    }

    private fun isRecordTag(tag: String): Boolean {
        val value = tag.lowercase(Locale.ROOT)
        return value.contains("predefinedlocation") || value.contains("situationrecord") ||
            value.contains("energyinfrastructuresite") || value.contains("parkingrecord")
    }

    private fun firstText(obj: JSONObject?, a: String, b: String): String? {
        if (obj == null) return null
        return obj.optString(a).takeIf { it.isNotBlank() && it != "null" }
            ?: obj.optString(b).takeIf { it.isNotBlank() && it != "null" }
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

    private fun parseNumber(raw: String): Double? {
        val cleaned = raw.trim().replace(" ", "").replace(',', '.')
            .replace(Regex("[^0-9+\\-.]"), "")
        return cleaned.toDoubleOrNull()
    }

    private fun cacheKey(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    private fun download(url: String, cacheName: String, timeout: Int): String {
        val cacheFile = File(context.cacheDir, "$cacheName.data")
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("User-Agent", "MovilidadSeguraEspana/1.0 private-use")
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val charset = runCatching {
                connection.contentType?.substringAfter("charset=", "UTF-8")?.let(Charset::forName)
            }.getOrNull() ?: Charsets.UTF_8
            val stream = if (connection.contentEncoding.equals("gzip", true))
                java.util.zip.GZIPInputStream(connection.inputStream) else connection.inputStream
            val text = stream.bufferedReader(charset).use { it.readText() }
            cacheFile.writeText(text)
            text
        } catch (e: Exception) {
            if (cacheFile.exists()) cacheFile.readText() else throw e
        } finally {
            connection?.disconnect()
        }
    }

    private fun utm31ToWgs84(easting: Double, northing: Double): Pair<Double, Double> {
        val a = 6378137.0
        val eccSquared = 0.00669438
        val k0 = 0.9996
        val x = easting - 500000.0
        val y = northing
        val eccPrimeSquared = eccSquared / (1 - eccSquared)
        val m = y / k0
        val mu = m / (a * (1 - eccSquared / 4 - 3 * eccSquared.pow(2) / 64 - 5 * eccSquared.pow(3) / 256))
        val e1 = (1 - sqrt(1 - eccSquared)) / (1 + sqrt(1 - eccSquared))
        val phi1Rad = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu)
        val n1 = a / sqrt(1 - eccSquared * sin(phi1Rad).pow(2))
        val t1 = tan(phi1Rad).pow(2)
        val c1 = eccPrimeSquared * cos(phi1Rad).pow(2)
        val r1 = a * (1 - eccSquared) / (1 - eccSquared * sin(phi1Rad).pow(2)).pow(1.5)
        val d = x / (n1 * k0)
        val lat = phi1Rad - (n1 * tan(phi1Rad) / r1) *
            (d.pow(2) / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1.pow(2) - 9 * eccPrimeSquared) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1.pow(2) - 252 * eccPrimeSquared - 3 * c1.pow(2)) * d.pow(6) / 720)
        val lon = (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
            (5 - 2 * c1 + 28 * t1 - 3 * c1.pow(2) + 8 * eccPrimeSquared + 24 * t1.pow(2)) * d.pow(5) / 120) / cos(phi1Rad)
        return Math.toDegrees(lat) to (3.0 + Math.toDegrees(lon))
    }

    private fun demoItems(profile: MobilityProfile): List<MapItem> {
        val base = listOf(
            MapItem("d1", ItemType.TRAIN, "Tren 03142", "Próxima estación: Zaragoza · +4 min", 41.6488, -0.8891, "MODO DEMOSTRACIÓN", isDemo = true),
            MapItem("d2", ItemType.RADAR_FIXED, "Radar fijo A-2", "Límite publicado: 100 km/h", 41.573, 1.874, "MODO DEMOSTRACIÓN", severity = Severity.CAUTION, speedLimit = 100, isDemo = true),
            MapItem("d3", ItemType.TRAFFIC, "Retención por accidente", "Carril derecho afectado", 40.45, -3.68, "MODO DEMOSTRACIÓN", severity = Severity.IMPORTANT, isDemo = true),
            MapItem("d4", ItemType.CHARGER, "Recarga rápida", "4 conectores disponibles", 40.4168, -3.7038, "MODO DEMOSTRACIÓN", isDemo = true),
            MapItem("d5", ItemType.BIKE, "Bicicletas públicas", "8 bicicletas · 5 plazas libres", 41.3874, 2.1686, "MODO DEMOSTRACIÓN", isDemo = true),
            MapItem("d6", ItemType.WEATHER, "Aviso de viento", "Rachas fuertes durante la tarde", 42.15, -0.41, "MODO DEMOSTRACIÓN", severity = Severity.CAUTION, isDemo = true)
        )
        return when (profile) {
            MobilityProfile.TRAIN -> base.filter { it.type == ItemType.TRAIN }
            MobilityProfile.CAR, MobilityProfile.TRUCK, MobilityProfile.MOTORCYCLE -> base.filter { it.type in listOf(ItemType.RADAR_FIXED, ItemType.TRAFFIC, ItemType.CHARGER, ItemType.WEATHER) }
            MobilityProfile.BICYCLE -> base.filter { it.type in listOf(ItemType.BIKE, ItemType.WEATHER) }
            MobilityProfile.PUBLIC_TRANSPORT -> base.filter { it.type in listOf(ItemType.TRAIN, ItemType.BIKE) }
        }
    }

    private fun demoSources() = listOf(SourceState("MODO DEMOSTRACIÓN", "Datos simulados claramente separados", true, System.currentTimeMillis()))
}
