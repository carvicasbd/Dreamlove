package com.carles.movilidadseguraespana

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class AppScreen { HOME, MAP, ALERTS, SETTINGS }

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var locationManager: LocationManager
    private var textToSpeech: TextToSpeech? = null
    private var lastRadarSpokenId: String? = null
    private var lastRadarSpokenAt = 0L
    private lateinit var appViewModel: MainViewModel

    private val listener = LocationListener { location ->
        if (::appViewModel.isInitialized) {
            appViewModel.updateLocation(location)
            val nearby = appViewModel.nearestRadar()
            if (nearby != null) {
                val (radar, distance) = nearby
                val now = System.currentTimeMillis()
                if (radar.id != lastRadarSpokenId || now - lastRadarSpokenAt > 120_000L) {
                    val limit = radar.speedLimit?.let { ". Límite publicado $it" } ?: ""
                    speak("${radar.title} dentro de $distance metros$limit")
                    lastRadarSpokenId = radar.id
                    lastRadarSpokenAt = now
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        textToSpeech = TextToSpeech(this, this)

        setContent {
            appViewModel = viewModel()
            val state by appViewModel.state.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                ) startLocationUpdates()
            }

            MovilidadTheme {
                MovilidadApp(
                    state = state,
                    viewModel = appViewModel,
                    onStartJourney = {
                        appViewModel.setJourneyActive(true)
                        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        ) startLocationUpdates()
                        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    onStopJourney = {
                        appViewModel.setJourneyActive(false)
                        stopLocationUpdates()
                    },
                    onSpeak = ::speak
                )
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching { locationManager.requestLocationUpdates(provider, 2_000L, 5f, listener) }
        }
    }

    private fun stopLocationUpdates() {
        runCatching { locationManager.removeUpdates(listener) }
    }

    private fun speak(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mobility_message")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) textToSpeech?.language = Locale("es", "ES")
    }

    override fun onDestroy() {
        stopLocationUpdates()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun MovilidadTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = Color(0xFF0B6B69),
        onPrimary = Color.White,
        secondary = Color(0xFF496A82),
        tertiary = Color(0xFF725B8C),
        background = Color(0xFFF4F8F8),
        surface = Color.White,
        error = Color(0xFFB3261E)
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MovilidadApp(
    state: MobilityUiState,
    viewModel: MainViewModel,
    onStartJourney: () -> Unit,
    onStopJourney: () -> Unit,
    onSpeak: (String) -> Unit
) {
    var screen by remember { mutableStateOf(AppScreen.HOME) }
    val title = when (screen) {
        AppScreen.HOME -> "Movilidad Segura España"
        AppScreen.MAP -> state.selectedProfile?.title ?: "Mapa general"
        AppScreen.ALERTS -> "Alertas cercanas"
        AppScreen.SETTINGS -> "Ajustes"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.Bold)
                        if (state.demoMode) Text("MODO DEMOSTRACIÓN", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                },
                actions = {
                    if (state.loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    IconButton(onClick = viewModel::refresh, enabled = state.selectedProfile != null) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar datos")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = screen == AppScreen.HOME, onClick = { screen = AppScreen.HOME }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Inicio") })
                NavigationBarItem(selected = screen == AppScreen.MAP, onClick = { screen = AppScreen.MAP }, icon = { Icon(Icons.Default.Map, null) }, label = { Text("Mapa") })
                NavigationBarItem(selected = screen == AppScreen.ALERTS, onClick = { screen = AppScreen.ALERTS }, icon = { Icon(Icons.Default.Notifications, null) }, label = { Text("Alertas") })
                NavigationBarItem(selected = screen == AppScreen.SETTINGS, onClick = { screen = AppScreen.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Ajustes") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                AppScreen.HOME -> HomeScreen(state, onProfile = {
                    viewModel.selectProfile(it)
                    screen = AppScreen.MAP
                }, onGeneralMap = { screen = AppScreen.MAP })
                AppScreen.MAP -> MapScreen(state, onStartJourney, onStopJourney, onSpeak)
                AppScreen.ALERTS -> AlertsScreen(state, onSpeak)
                AppScreen.SETTINGS -> SettingsScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: MobilityUiState,
    onProfile: (MobilityProfile) -> Unit,
    onGeneralMap: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Hola 👋", fontSize = if (state.simpleMode) 30.sp else 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("¿Cómo te desplazas hoy?", fontSize = if (state.simpleMode) 25.sp else 20.sp)
        Text("Elige una opción y verás solo la información que te interesa.", color = Color(0xFF5C6666))
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = if (state.simpleMode) GridCells.Fixed(1) else GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(MobilityProfile.entries) { profile ->
                ProfileCard(profile, state.simpleMode) { onProfile(profile) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onGeneralMap, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.Public, null)
            Spacer(Modifier.width(8.dp))
            Text("Ver mapa general")
        }
        Spacer(Modifier.height(8.dp))
        Text(state.message, modifier = Modifier.fillMaxWidth(), color = Color(0xFF48605F), fontSize = 13.sp)
    }
}

@Composable
private fun ProfileCard(profile: MobilityProfile, simpleMode: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (simpleMode) 118.dp else 142.dp)
            .semantics { contentDescription = "${profile.title}. ${profile.subtitle}" }
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(profile.emoji, fontSize = if (simpleMode) 36.sp else 32.sp)
            Column {
                Text(profile.title, fontSize = if (simpleMode) 22.sp else 18.sp, fontWeight = FontWeight.Bold)
                Text(profile.subtitle, fontSize = if (simpleMode) 16.sp else 13.sp, color = Color(0xFF5D6767), maxLines = 2)
            }
        }
    }
}

@Composable
private fun MapScreen(
    state: MobilityUiState,
    onStartJourney: () -> Unit,
    onStopJourney: () -> Unit,
    onSpeak: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = Color(0xFFE6F1F0)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.message, fontWeight = FontWeight.SemiBold)
                    val speed = state.currentSpeedKmh?.let { " · ${it.toInt()} km/h" } ?: ""
                    Text("Aviso radar: ${state.radarDistanceMeters} m$speed", fontSize = 13.sp)
                }
                if (state.journeyActive) {
                    Button(onClick = onStopJourney, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp)); Text("Finalizar")
                    }
                } else {
                    Button(onClick = onStartJourney) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Iniciar viaje")
                    }
                }
            }
        }

        MobilityMap(
            items = state.items,
            userLat = state.currentLatitude,
            userLon = state.currentLongitude,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        val nearby = remember(state.items, state.currentLatitude, state.currentLongitude, state.radarDistanceMeters) {
            nearestItem(state)
        }
        if (nearby != null) {
            Surface(tonalElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠️", fontSize = 26.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nearby.first.title, fontWeight = FontWeight.Bold)
                        Text("A ${nearby.second} m · ${nearby.first.description}", fontSize = 13.sp)
                    }
                    IconButton(onClick = { onSpeak("${nearby.first.title} a ${nearby.second} metros") }) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "Repetir aviso")
                    }
                }
            }
        }
    }
}

@Composable
private fun MobilityMap(
    items: List<MapItem>,
    userLat: Double?,
    userLon: Double?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier.semantics { contentDescription = "Mapa de movilidad segura" },
        factory = {
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(6.0)
                controller.setCenter(GeoPoint(40.2, -3.7))
            }
        },
        update = { map ->
            map.overlays.clear()
            items.take(2000).forEach { item ->
                val marker = Marker(map).apply {
                    position = GeoPoint(item.latitude, item.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "${iconFor(item.type)} ${item.title}"
                    snippet = item.description
                    subDescription = "Fuente: ${item.source}"
                    setOnMarkerClickListener { clicked, _ -> clicked.showInfoWindow(); true }
                }
                map.overlays.add(marker)
            }
            if (userLat != null && userLon != null) {
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(userLat, userLon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "📍 Tu posición"
                    snippet = "Ubicación procesada solo en el móvil"
                })
            }
            map.invalidate()
        }
    )
}

@Composable
private fun AlertsScreen(state: MobilityUiState, onSpeak: (String) -> Unit) {
    val alertItems = state.items.sortedWith(compareByDescending<MapItem> { it.severity.ordinal }.thenBy { it.title })
    if (alertItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No hay alertas disponibles. Selecciona un perfil y actualiza.", modifier = Modifier.padding(24.dp))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Información relevante", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Cada aviso indica la fuente y la antigüedad del dato.")
        }
        items(alertItems.take(250), key = { it.id }) { item ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                    Text(iconFor(item.type), fontSize = 26.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold, fontSize = if (state.simpleMode) 20.sp else 17.sp)
                        Text(item.description, fontSize = if (state.simpleMode) 16.sp else 14.sp)
                        Text("Fuente: ${item.source} · ${relativeTime(item.timestamp)}", fontSize = 12.sp, color = Color.Gray)
                    }
                    IconButton(onClick = { onSpeak("${item.title}. ${item.description}") }) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "Leer aviso")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: MobilityUiState, viewModel: MainViewModel) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Facilidad de uso", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        item {
            SettingSwitch("Modo sencillo", "Letras y botones más grandes, menos opciones por pantalla", state.simpleMode, viewModel::setSimpleMode)
        }
        item {
            SettingSwitch("Modo demostración", "Usa datos simulados y no los mezcla con fuentes reales", state.demoMode, viewModel::setDemoMode)
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Distancia de aviso al radar", fontWeight = FontWeight.Bold)
                    Text("${state.radarDistanceMeters} metros", color = MaterialTheme.colorScheme.primary)
                    Slider(
                        value = state.radarDistanceMeters.toFloat(),
                        onValueChange = { viewModel.setRadarDistance((it / 250).toInt().coerceAtLeast(1) * 250) },
                        valueRange = 250f..2000f,
                        steps = 6
                    )
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Aviso de velocidad", fontWeight = FontWeight.Bold)
                    Text("Avisar al superar en ${state.speedTolerance} km/h un límite oficial disponible")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 3, 5, 10).forEach { tolerance ->
                            FilterChip(
                                selected = state.speedTolerance == tolerance,
                                onClick = { viewModel.setSpeedTolerance(tolerance) },
                                label = { Text(if (tolerance == 0) "Al alcanzar" else "+$tolerance") }
                            )
                        }
                    }
                }
            }
        }
        item { Text("Fuentes", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        items(state.sources) { source ->
            Card(shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (source.active) Icons.Default.CheckCircle else Icons.Default.Info, null,
                        tint = if (source.active) Color(0xFF1B7D52) else Color(0xFF8A6500))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(source.name, fontWeight = FontWeight.Bold)
                        Text(source.status, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF1F7)), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Privacidad", fontWeight = FontWeight.Bold)
                    Text("No requiere cuenta, no contiene publicidad y procesa tu ubicación localmente. DGT 3.0, EMT Madrid y AEMET solo se activan cuando existen credenciales o autorización.")
                    Spacer(Modifier.height(8.dp))
                    Text("Aplicación privada, independiente y no oficial. Respeta siempre las señales y las indicaciones de los agentes.", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, fontSize = 13.sp)
            }
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }
}

private fun nearestItem(state: MobilityUiState): Pair<MapItem, Int>? {
    val lat = state.currentLatitude ?: return null
    val lon = state.currentLongitude ?: return null
    return state.items.asSequence()
        .filter { it.type in listOf(ItemType.RADAR_FIXED, ItemType.RADAR_SECTION, ItemType.MOBILE_ZONE, ItemType.TRAFFIC) }
        .map { item ->
            val distance = FloatArray(1)
            Location.distanceBetween(lat, lon, item.latitude, item.longitude, distance)
            item to distance[0].toInt()
        }
        .filter { it.second <= state.radarDistanceMeters }
        .minByOrNull { it.second }
}

private fun iconFor(type: ItemType): String = when (type) {
    ItemType.TRAIN -> "🚆"
    ItemType.BUS -> "🚌"
    ItemType.TRAM -> "🚊"
    ItemType.RADAR_FIXED -> "📷"
    ItemType.RADAR_SECTION -> "↔️"
    ItemType.MOBILE_ZONE -> "⚠️"
    ItemType.TRAFFIC -> "🚧"
    ItemType.CHARGER -> "⚡"
    ItemType.PARKING -> "🅿️"
    ItemType.BIKE -> "🚲"
    ItemType.WEATHER -> "🌦️"
    ItemType.STOP -> "🚏"
    ItemType.INFO -> "ℹ️"
}

private fun relativeTime(timestamp: Long): String {
    val age = System.currentTimeMillis() - timestamp
    return when {
        age < 60_000 -> "ahora"
        age < 3_600_000 -> "hace ${age / 60_000} min"
        age < 86_400_000 -> "hace ${age / 3_600_000} h"
        else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
    }
}
