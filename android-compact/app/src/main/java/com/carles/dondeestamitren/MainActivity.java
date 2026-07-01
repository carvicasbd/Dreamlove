package com.carles.dondeestamitren;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final long CERCANIAS_REFRESH_MS = 20_000L;
    private static final long TRIPS_REFRESH_MS = 30_000L;
    private static final long LD_REFRESH_MS = 15 * 60_000L;
    private static final long ALERTS_REFRESH_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Map<String, TrainData> trains = new ConcurrentHashMap<>();
    private final Map<String, DelayData> delays = new ConcurrentHashMap<>();
    private final List<AlertData> alerts = new ArrayList<>();

    private MapView mapView;
    private EditText searchInput;
    private TextView statusText;
    private RenfeClient renfeClient;
    private long lastSuccessfulUpdate;
    private boolean tasksRunning;

    private final DateTimeFormatter dateFormatter = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss", new Locale("es", "ES"))
            .withZone(ZoneId.of("Europe/Madrid"));

    private final Runnable cercaniasTask = new Runnable() {
        @Override public void run() {
            refreshVehicles(RenfeClient.VEHICLES_CERCANIAS, "Cercanías/Rodalies");
            if (tasksRunning) handler.postDelayed(this, CERCANIAS_REFRESH_MS);
        }
    };

    private final Runnable longDistanceTask = new Runnable() {
        @Override public void run() {
            refreshVehicles(RenfeClient.VEHICLES_LD, "Alta/Media/Larga Distancia");
            if (tasksRunning) handler.postDelayed(this, LD_REFRESH_MS);
        }
    };

    private final Runnable tripsTask = new Runnable() {
        @Override public void run() {
            refreshDelays();
            if (tasksRunning) handler.postDelayed(this, TRIPS_REFRESH_MS);
        }
    };

    private final Runnable alertsTask = new Runnable() {
        @Override public void run() {
            refreshAlerts(false);
            if (tasksRunning) handler.postDelayed(this, ALERTS_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        renfeClient = new RenfeClient(this);
        buildInterface();
        showInitialMap();
    }

    private void buildInterface() {
        int padding = dp(10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 248, 250));

        TextView title = new TextView(this);
        title.setText("Dónde está mi tren");
        title.setTextSize(24f);
        title.setTextColor(Color.rgb(19, 42, 76));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(padding, padding, padding, 2);
        title.setContentDescription("Dónde está mi tren, aplicación no oficial");
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Aplicación privada no oficial · Datos abiertos de Renfe Data");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(padding, 0, padding, padding);
        root.addView(subtitle);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(padding, 0, padding, padding / 2);

        searchInput = new EditText(this);
        searchInput.setHint("Buscar tren, línea o servicio");
        searchInput.setSingleLine(true);
        searchInput.setTextSize(17f);
        searchInput.setContentDescription("Buscar tren, línea o servicio");
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button clearButton = new Button(this);
        clearButton.setText("Todos");
        clearButton.setContentDescription("Mostrar todos los trenes");
        clearButton.setOnClickListener(v -> {
            searchInput.setText("");
            renderMarkers("");
            mapView.getController().setZoom(6.2);
            mapView.getController().animateTo(new GeoPoint(40.2, -3.7));
        });
        searchRow.addView(clearButton, new LinearLayout.LayoutParams(dp(90), dp(52)));
        root.addView(searchRow);

        statusText = new TextView(this);
        statusText.setText("Conectando con Renfe Data…");
        statusText.setTextSize(14f);
        statusText.setTextColor(Color.rgb(70, 70, 70));
        statusText.setPadding(padding, 2, padding, padding / 2);
        statusText.setContentDescription("Estado de actualización");
        root.addView(statusText);

        mapView = new MapView(this);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setTilesScaledToDpi(true);
        mapView.setContentDescription("Mapa de trenes de España");
        root.addView(mapView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(padding, padding / 2, padding, padding / 2);

        Button refreshButton = new Button(this);
        refreshButton.setText("Actualizar");
        refreshButton.setContentDescription("Actualizar datos ahora");
        refreshButton.setOnClickListener(v -> refreshAll());
        buttonRow.addView(refreshButton, new LinearLayout.LayoutParams(0, dp(54), 1f));

        Button incidentsButton = new Button(this);
        incidentsButton.setText("Incidencias");
        incidentsButton.setContentDescription("Consultar incidencias de servicio");
        incidentsButton.setOnClickListener(v -> refreshAlerts(true));
        buttonRow.addView(incidentsButton, new LinearLayout.LayoutParams(0, dp(54), 1f));

        Button infoButton = new Button(this);
        infoButton.setText("Información");
        infoButton.setContentDescription("Información y atribuciones");
        infoButton.setOnClickListener(v -> showInfo());
        buttonRow.addView(infoButton, new LinearLayout.LayoutParams(0, dp(54), 1f));
        root.addView(buttonRow);

        TextView attribution = new TextView(this);
        attribution.setText("© OpenStreetMap · Datos: Renfe Data (CC BY 4.0)");
        attribution.setGravity(Gravity.CENTER);
        attribution.setTextSize(11f);
        attribution.setTextColor(Color.GRAY);
        attribution.setPadding(4, 0, 4, 6);
        root.addView(attribution);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderMarkers(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        MapEventsReceiver receiver = new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint p) { return false; }
            @Override public boolean longPressHelper(GeoPoint p) {
                Toast.makeText(MainActivity.this,
                        String.format(Locale.US, "Lat %.5f · Lon %.5f", p.getLatitude(), p.getLongitude()),
                        Toast.LENGTH_SHORT).show();
                return true;
            }
        };
        mapView.getOverlays().add(new MapEventsOverlay(receiver));
        setContentView(root);
    }

    private void showInitialMap() {
        mapView.getController().setZoom(6.2);
        mapView.getController().setCenter(new GeoPoint(40.2, -3.7));
    }

    private void refreshAll() {
        statusText.setText("Actualizando posiciones, retrasos e incidencias…");
        refreshVehicles(RenfeClient.VEHICLES_CERCANIAS, "Cercanías/Rodalies");
        refreshVehicles(RenfeClient.VEHICLES_LD, "Alta/Media/Larga Distancia");
        refreshDelays();
        refreshAlerts(false);
    }

    private void refreshVehicles(String endpoint, String service) {
        executor.execute(() -> {
            try {
                List<TrainData> loaded = renfeClient.loadVehicles(endpoint, service);
                trains.entrySet().removeIf(entry -> service.equals(entry.getValue().service));
                for (TrainData train : loaded) trains.put(train.id, train);
                applyDelays();
                lastSuccessfulUpdate = System.currentTimeMillis();
                runOnUiThread(() -> {
                    renderMarkers(searchInput.getText().toString());
                    updateStatus(null);
                });
            } catch (Exception error) {
                runOnUiThread(() -> updateStatus("No se pudo actualizar " + service));
            }
        });
    }

    private void refreshDelays() {
        executor.execute(() -> {
            try {
                Map<String, DelayData> cercanias = renfeClient.loadDelays(RenfeClient.TRIPS_CERCANIAS);
                Map<String, DelayData> longDistance = renfeClient.loadDelays(RenfeClient.TRIPS_LD);
                delays.clear();
                delays.putAll(cercanias);
                delays.putAll(longDistance);
                applyDelays();
                runOnUiThread(() -> {
                    renderMarkers(searchInput.getText().toString());
                    updateStatus(null);
                });
            } catch (Exception error) {
                runOnUiThread(() -> updateStatus("Posiciones disponibles; retrasos temporalmente no disponibles"));
            }
        });
    }

    private void refreshAlerts(boolean showWhenReady) {
        executor.execute(() -> {
            try {
                List<AlertData> loaded = renfeClient.loadAlerts();
                synchronized (alerts) {
                    alerts.clear();
                    alerts.addAll(loaded);
                }
                runOnUiThread(() -> {
                    if (showWhenReady) showAlertsDialog();
                    updateStatus(null);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (showWhenReady) Toast.makeText(this,
                            "No se han podido descargar las incidencias", Toast.LENGTH_LONG).show();
                    updateStatus("Incidencias temporalmente no disponibles");
                });
            }
        });
    }

    private void applyDelays() {
        for (TrainData train : trains.values()) {
            if (train.tripId == null) continue;
            DelayData delay = delays.get(train.tripId);
            if (delay == null) continue;
            train.delaySeconds = delay.delaySeconds;
            train.nextStopId = delay.nextStopId;
            train.nextArrival = delay.nextArrival;
        }
    }

    private void renderMarkers(String rawQuery) {
        if (mapView == null) return;
        String query = normalize(rawQuery);
        mapView.getOverlays().removeIf(overlay -> overlay instanceof Marker);

        List<TrainData> ordered = new ArrayList<>(trains.values());
        ordered.sort(Comparator.comparing(TrainData::displayName, String.CASE_INSENSITIVE_ORDER));
        int visible = 0;
        TrainData firstMatch = null;

        for (TrainData train : ordered) {
            if (!matches(train, query)) continue;
            if (firstMatch == null) firstMatch = train;
            Marker marker = new Marker(mapView);
            marker.setPosition(new GeoPoint(train.latitude, train.longitude));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle(train.displayName());
            marker.setSnippet(shortDescription(train));
            marker.setSubDescription("Última posición oficial: " + formatEpoch(train.timestamp));
            marker.setOnMarkerClickListener((clicked, view) -> {
                showTrainDialog(train);
                return true;
            });
            mapView.getOverlays().add(marker);
            visible++;
        }
        mapView.invalidate();

        if (!query.isEmpty() && firstMatch != null) {
            mapView.getController().setZoom(10.5);
            mapView.getController().animateTo(new GeoPoint(firstMatch.latitude, firstMatch.longitude));
        }
        statusText.setText(statusLine(visible));
    }

    private boolean matches(TrainData train, String query) {
        if (query.isEmpty()) return true;
        String searchable = normalize(train.displayName() + " "
                + value(train.tripId) + " " + value(train.routeId) + " " + value(train.service));
        return searchable.contains(query);
    }

    private void showTrainDialog(TrainData train) {
        StringBuilder text = new StringBuilder();
        text.append("Servicio: ").append(value(train.service)).append('\n');
        text.append("Tren/línea: ").append(train.displayName()).append('\n');
        text.append("Identificador de viaje: ").append(value(train.tripId)).append('\n');
        text.append("Ruta: ").append(value(train.routeId)).append('\n');
        text.append("Retraso: ").append(delayText(train.delaySeconds)).append('\n');
        text.append("Próxima parada: ").append(value(train.nextStopId)).append('\n');
        text.append("Llegada estimada: ").append(formatEpoch(train.nextArrival)).append('\n');
        text.append("Última posición GPS: ").append(formatEpoch(train.timestamp)).append('\n');
        text.append(String.format(Locale.US, "Coordenadas: %.5f, %.5f", train.latitude, train.longitude));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(train.displayName())
                .setMessage(text.toString())
                .setPositiveButton("Cerrar", null)
                .setNeutralButton("Centrar", null)
                .create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            mapView.getController().setZoom(13.0);
            mapView.getController().animateTo(new GeoPoint(train.latitude, train.longitude));
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showAlertsDialog() {
        List<AlertData> copy;
        synchronized (alerts) { copy = new ArrayList<>(alerts); }
        if (copy.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Incidencias")
                    .setMessage("Renfe Data no informa de incidencias activas en este momento.")
                    .setPositiveButton("Cerrar", null)
                    .show();
            return;
        }

        StringBuilder builder = new StringBuilder();
        int count = Math.min(copy.size(), 40);
        for (int i = 0; i < count; i++) {
            AlertData item = copy.get(i);
            builder.append(i + 1).append(". ").append(item.title).append('\n');
            if (!item.routeIds.isEmpty()) builder.append("Líneas: ").append(item.routeIds).append('\n');
            if (item.effect != null && !item.effect.isBlank()) builder.append("Efecto: ").append(item.effect).append('\n');
            builder.append(item.description).append("\n\n");
        }

        TextView content = new TextView(this);
        content.setText(builder.toString());
        content.setTextSize(16f);
        content.setTextColor(Color.BLACK);
        content.setPadding(dp(18), dp(10), dp(18), dp(10));
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);

        new AlertDialog.Builder(this)
                .setTitle("Incidencias activas: " + copy.size())
                .setView(scrollView)
                .setPositiveButton("Cerrar", null)
                .show();
    }

    private void showInfo() {
        new AlertDialog.Builder(this)
                .setTitle("Dónde está mi tren")
                .setMessage("Aplicación privada e independiente, no oficial.\n\n"
                        + "Datos de transporte proporcionados por Renfe Data y reutilizados conforme a su licencia Creative Commons Attribution 4.0.\n\n"
                        + "Mapas: OpenStreetMap y sus colaboradores.\n\n"
                        + "Las posiciones de Cercanías suelen actualizarse cada 20 segundos. Las posiciones de Alta Velocidad, Larga y Media Distancia pueden actualizarse aproximadamente cada 15 minutos. La hora de la última posición oficial aparece en cada ficha.\n\n"
                        + "Comprueba siempre la información oficial antes de tomar decisiones críticas de viaje.")
                .setPositiveButton("Entendido", null)
                .show();
    }

    private void updateStatus(String warning) {
        if (warning != null) {
            statusText.setText(warning + " · " + statusLine(countVisible(searchInput.getText().toString())));
        } else {
            statusText.setText(statusLine(countVisible(searchInput.getText().toString())));
        }
    }

    private String statusLine(int visible) {
        String update = lastSuccessfulUpdate == 0L
                ? "sin actualización todavía"
                : "actualizado " + dateFormatter.format(Instant.ofEpochMilli(lastSuccessfulUpdate));
        return visible + " trenes visibles · " + trains.size() + " recibidos · " + update;
    }

    private int countVisible(String query) {
        String normalized = normalize(query);
        int count = 0;
        for (TrainData train : trains.values()) if (matches(train, normalized)) count++;
        return count;
    }

    private String shortDescription(TrainData train) {
        return value(train.service) + " · " + delayText(train.delaySeconds)
                + (train.nextStopId == null ? "" : " · Próxima: " + train.nextStopId);
    }

    private String delayText(Integer seconds) {
        if (seconds == null) return "sin información de retraso";
        int minutes = Math.round(seconds / 60f);
        if (minutes > 0) return "+" + minutes + " min";
        if (minutes < 0) return minutes + " min";
        return "sin retraso informado";
    }

    private String formatEpoch(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0L) return "no disponible";
        return dateFormatter.format(Instant.ofEpochSecond(epochSeconds));
    }

    private String value(String text) {
        return text == null || text.isBlank() ? "no disponible" : text;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(new Locale("es", "ES"))
                .trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onResume() {
        super.onResume();
        mapView.onResume();
        if (!tasksRunning) {
            tasksRunning = true;
            handler.post(cercaniasTask);
            handler.post(longDistanceTask);
            handler.post(tripsTask);
            handler.post(alertsTask);
        }
    }

    @Override protected void onPause() {
        tasksRunning = false;
        handler.removeCallbacks(cercaniasTask);
        handler.removeCallbacks(longDistanceTask);
        handler.removeCallbacks(tripsTask);
        handler.removeCallbacks(alertsTask);
        mapView.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        mapView.onDetach();
        super.onDestroy();
    }
}
