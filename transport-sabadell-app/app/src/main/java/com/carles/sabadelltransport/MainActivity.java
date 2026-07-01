package com.carles.sabadelltransport;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String API = "https://api.transitous.org";
    private static final String RENFE_ALERTS = "https://gtfsrt.renfe.com/alerts.json";
    private static final int LOCATION_REQUEST = 73;

    private final int BG = Color.rgb(8, 15, 28);
    private final int CARD = Color.rgb(17, 27, 46);
    private final int CARD2 = Color.rgb(25, 38, 62);
    private final int WHITE = Color.rgb(241, 245, 249);
    private final int MUTED = Color.rgb(148, 163, 184);
    private final int CYAN = Color.rgb(34, 211, 238);
    private final int GREEN = Color.rgb(52, 211, 153);
    private final int AMBER = Color.rgb(251, 191, 36);
    private final int ORANGE = Color.rgb(251, 146, 60);
    private final int RED = Color.rgb(248, 113, 113);

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Calendar travelTime = Calendar.getInstance();

    private EditText originInput;
    private EditText destinationInput;
    private EditText stopInput;
    private TextView dateButton;
    private TextView timeButton;
    private Spinner modeSpinner;
    private LinearLayout results;
    private ProgressBar loading;
    private TextView resultsTitle;
    private LocationManager locationManager;
    private LocationListener pendingLocationListener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        showHome();
    }

    @Override protected void onDestroy() {
        if (pendingLocationListener != null && locationManager != null) {
            try { locationManager.removeUpdates(pendingLocationListener); } catch (SecurityException ignored) { }
        }
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) locateNearbyStop();
            else toast("Puedes buscar la parada escribiendo su nombre.");
        }
    }

    private void showHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = col();
        page.setPadding(dp(18), dp(20), dp(18), dp(42));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        page.addView(text("MOVILIDAD · SABADELL", 12, CYAN, true));
        TextView title = text("Sabadell\nTransport Live", 34, WHITE, true);
        title.setLineSpacing(0, .93f);
        page.addView(title);
        TextView intro = text("Todo dentro de la app: rutas, horarios, retrasos, paradas e incidencias disponibles.", 15, MUTED, false);
        intro.setPadding(0, dp(8), 0, dp(18));
        page.addView(intro);

        page.addView(operatorStrip());
        page.addView(section("PLANIFICAR VIAJE"));
        page.addView(routePlanner(), margin(-1, -2, 0, 0, 0, 18));

        page.addView(section("PRÓXIMAS SALIDAS DE UNA PARADA"));
        page.addView(stopBoard(), margin(-1, -2, 0, 0, 0, 18));

        page.addView(section("INCIDENCIAS"));
        LinearLayout incidents = card();
        incidents.addView(text("Problemas oficiales de servicio", 19, WHITE, true));
        TextView idesc = text("Las alertas de cada viaje o parada aparecen en sus resultados. Renfe también dispone de un panel general nativo.", 13, MUTED, false);
        idesc.setPadding(0, dp(5), 0, 0);
        incidents.addView(idesc);
        Button renfe = filled("VER INCIDENCIAS GENERALES DE RENFE", RED);
        renfe.setOnClickListener(v -> loadRenfeAlerts());
        incidents.addView(renfe, margin(-1, dp(52), 0, 14, 0, 0));
        page.addView(incidents, margin(-1, -2, 0, 0, 0, 18));

        resultsTitle = section("RESULTADOS");
        resultsTitle.setVisibility(View.GONE);
        page.addView(resultsTitle);
        loading = new ProgressBar(this);
        loading.setVisibility(View.GONE);
        page.addView(loading, margin(-1, dp(46), 0, 2, 0, 10));
        results = col();
        page.addView(results);

        LinearLayout info = card();
        info.addView(text("FUENTES Y PRECISIÓN", 12, MUTED, true));
        TextView source = text("Interfaz completamente nativa. Rutas y salidas: Transitous/MOTIS con feeds abiertos y OpenStreetMap. Incidencias generales de Renfe: GTFS-Realtime de Renfe. Cuando un operador no publica tiempo real, se muestra “Horario · sin tiempo real” y nunca se inventa un retraso.", 11, MUTED, false);
        source.setPadding(0, dp(10), 0, 0);
        info.addView(source);
        page.addView(info, margin(-1, -2, 0, 22, 0, 0));
    }

    private View operatorStrip() {
        LinearLayout outer = col();
        outer.addView(section("OPERADORES INTEGRADOS"));
        LinearLayout row1 = row();
        row1.addView(operatorChip("R", "Renfe / Rodalies", CYAN), equal());
        row1.addView(operatorChip("T", "TUS Sabadell", GREEN), equal());
        LinearLayout row2 = row();
        row2.addView(operatorChip("S", "Sagalés", AMBER), equal());
        row2.addView(operatorChip("M", "Monbus", ORANGE), equal());
        outer.addView(row1, new LinearLayout.LayoutParams(-1, dp(58)));
        outer.addView(row2, margin(-1, dp(58), 0, 7, 0, 18));
        return outer;
    }

    private View operatorChip(String letter, String name, int color) {
        LinearLayout box = row();
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setBackground(round(CARD, 15));
        TextView b = text(letter, 15, BG, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(color, 12));
        box.addView(b, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView n = text(name, 12, WHITE, true);
        n.setPadding(dp(8), 0, dp(2), 0);
        box.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        return box;
    }

    private View routePlanner() {
        LinearLayout box = card();
        box.addView(text("Salida y destino", 20, WHITE, true));
        TextView hint = text("Puedes escribir una estación, parada, dirección o lugar.", 13, MUTED, false);
        hint.setPadding(0, dp(4), 0, dp(12));
        box.addView(hint);

        originInput = input("Salida: p. ej. Sabadell Centre");
        destinationInput = input("Destino: p. ej. Barcelona Sants");
        box.addView(originInput);
        box.addView(destinationInput, margin(-1, dp(54), 0, 9, 0, 0));

        Button swap = outlined("⇅ INTERCAMBIAR SALIDA Y DESTINO", CYAN);
        swap.setOnClickListener(v -> {
            String a = originInput.getText().toString();
            originInput.setText(destinationInput.getText().toString());
            destinationInput.setText(a);
        });
        box.addView(swap, margin(-1, dp(46), 0, 9, 0, 0));

        LinearLayout when = row();
        dateButton = pickerText();
        timeButton = pickerText();
        updateDateTimeLabels();
        dateButton.setOnClickListener(v -> chooseDate());
        timeButton.setOnClickListener(v -> chooseTime());
        when.addView(dateButton, equal());
        when.addView(timeButton, equal());
        box.addView(when, margin(-1, dp(50), 0, 10, 0, 0));

        modeSpinner = new Spinner(this);
        String[] modes = {"Todos los transportes", "Solo Renfe / tren", "Solo autobús"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, modes) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(WHITE);
                v.setTextSize(14);
                v.setPadding(dp(12), 0, dp(12), 0);
                return v;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(position, convertView, parent);
                v.setTextColor(Color.BLACK);
                return v;
            }
        };
        modeSpinner.setAdapter(adapter);
        modeSpinner.setBackground(round(CARD2, 12));
        box.addView(modeSpinner, margin(-1, dp(50), 0, 10, 0, 0));

        Button search = filled("BUSCAR HORARIOS Y RETRASOS", CYAN);
        search.setOnClickListener(v -> startJourneySearch());
        box.addView(search, margin(-1, dp(54), 0, 12, 0, 0));
        return box;
    }

    private View stopBoard() {
        LinearLayout box = card();
        box.addView(text("¿En qué parada estás?", 20, WHITE, true));
        TextView hint = text("Escribe una parada o detecta las salidas cercanas mediante GPS.", 13, MUTED, false);
        hint.setPadding(0, dp(4), 0, dp(12));
        box.addView(hint);
        stopInput = input("Parada o estación");
        box.addView(stopInput);
        Button search = filled("BUSCAR PARADA", GREEN);
        search.setOnClickListener(v -> searchStopByName());
        box.addView(search, margin(-1, dp(52), 0, 10, 0, 0));
        Button gps = outlined("USAR MI UBICACIÓN", GREEN);
        gps.setOnClickListener(v -> requestLocation());
        box.addView(gps, margin(-1, dp(50), 0, 9, 0, 0));
        return box;
    }

    private void chooseDate() {
        new DatePickerDialog(this, (view, year, month, day) -> {
            travelTime.set(Calendar.YEAR, year);
            travelTime.set(Calendar.MONTH, month);
            travelTime.set(Calendar.DAY_OF_MONTH, day);
            updateDateTimeLabels();
        }, travelTime.get(Calendar.YEAR), travelTime.get(Calendar.MONTH), travelTime.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void chooseTime() {
        new TimePickerDialog(this, (view, hour, minute) -> {
            travelTime.set(Calendar.HOUR_OF_DAY, hour);
            travelTime.set(Calendar.MINUTE, minute);
            travelTime.set(Calendar.SECOND, 0);
            updateDateTimeLabels();
        }, travelTime.get(Calendar.HOUR_OF_DAY), travelTime.get(Calendar.MINUTE), true).show();
    }

    private void updateDateTimeLabels() {
        if (dateButton != null) dateButton.setText("FECHA\n" + new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(travelTime.getTime()));
        if (timeButton != null) timeButton.setText("HORA\n" + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(travelTime.getTime()));
    }

    private void startJourneySearch() {
        hideKeyboard();
        String from = originInput.getText().toString().trim();
        String to = destinationInput.getText().toString().trim();
        if (from.length() < 2 || to.length() < 2) {
            toast("Escribe una salida y un destino.");
            return;
        }
        beginResults("Buscando lugares…");
        pool.execute(() -> {
            try {
                List<GeoPlace> fromMatches = geocode(from, false);
                List<GeoPlace> toMatches = geocode(to, false);
                ui.post(() -> choosePlace("Elige la salida", fromMatches, selectedFrom ->
                        choosePlace("Elige el destino", toMatches, selectedTo -> loadJourneys(selectedFrom, selectedTo))));
            } catch (Exception e) {
                ui.post(() -> showError("No se han podido localizar la salida y el destino.", e));
            }
        });
    }

    private void searchStopByName() {
        hideKeyboard();
        String query = stopInput.getText().toString().trim();
        if (query.length() < 2) {
            toast("Escribe el nombre de una parada o estación.");
            return;
        }
        beginResults("Buscando paradas…");
        pool.execute(() -> {
            try {
                List<GeoPlace> matches = geocode(query, true);
                ui.post(() -> choosePlace("Elige la parada", matches, this::loadStopTimes));
            } catch (Exception e) {
                ui.post(() -> showError("No se ha podido buscar la parada.", e));
            }
        });
    }

    private void choosePlace(String title, List<GeoPlace> places, PlaceCallback callback) {
        if (places == null || places.isEmpty()) {
            showError("No se han encontrado resultados.", null);
            return;
        }
        String[] labels = new String[places.size()];
        for (int i = 0; i < places.size(); i++) labels[i] = places.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> callback.onSelected(places.get(which)))
                .setNegativeButton("Cancelar", (dialog, which) -> finishLoading())
                .show();
    }

    private List<GeoPlace> geocode(String query, boolean stopsOnly) throws Exception {
        StringBuilder url = new StringBuilder(API).append("/api/v1/geocode?text=")
                .append(enc(query)).append("&language=es&numResults=8&place=41.5486,2.1074&placeBias=3");
        if (stopsOnly) url.append("&type=STOP");
        JSONArray array = new JSONArray(get(url.toString()));
        List<GeoPlace> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", "Lugar");
            String type = o.optString("type", "");
            String id = o.optString("id", "");
            double lat = o.optDouble("lat", Double.NaN);
            double lon = o.optDouble("lon", Double.NaN);
            String area = areaLabel(o.optJSONArray("areas"));
            String label = name + (area.isEmpty() ? "" : " · " + area) + ("STOP".equals(type) ? " · Parada" : "");
            if (!Double.isNaN(lat) && !Double.isNaN(lon)) out.add(new GeoPlace(id, name, label, lat, lon, type));
        }
        return out;
    }

    private String areaLabel(JSONArray areas) {
        if (areas == null) return "";
        for (int i = 0; i < areas.length(); i++) {
            JSONObject a = areas.optJSONObject(i);
            if (a != null && a.optBoolean("default", false)) return a.optString("name", "");
        }
        JSONObject first = areas.length() > 0 ? areas.optJSONObject(0) : null;
        return first == null ? "" : first.optString("name", "");
    }

    private void loadJourneys(GeoPlace from, GeoPlace to) {
        int selectedMode = modeSpinner.getSelectedItemPosition();
        beginResults("Consultando horarios y tiempo real…");
        pool.execute(() -> {
            try {
                String fromRef = from.ref();
                String toRef = to.ref();
                String time = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(travelTime.getTime());
                StringBuilder url = new StringBuilder(API).append("/api/v6/plan?fromPlace=").append(enc(fromRef))
                        .append("&toPlace=").append(enc(toRef))
                        .append("&time=").append(enc(time))
                        .append("&numItineraries=6&maxItineraries=8&detailedLegs=false&language=es&withFares=false");
                if (selectedMode == 1) url.append("&transitModes=RAIL,SUBURBAN,REGIONAL_RAIL");
                if (selectedMode == 2) url.append("&transitModes=BUS,COACH");
                JSONObject data = new JSONObject(get(url.toString()));
                ui.post(() -> renderJourneys(from, to, data));
            } catch (Exception e) {
                ui.post(() -> showError("No se han podido obtener rutas para este trayecto.", e));
            }
        });
    }

    private void renderJourneys(GeoPlace from, GeoPlace to, JSONObject data) {
        finishLoading();
        resultsTitle.setText("RUTAS · " + from.name + " → " + to.name);
        JSONArray itineraries = data.optJSONArray("itineraries");
        if (itineraries == null || itineraries.length() == 0) {
            results.addView(messageCard("No se han encontrado servicios para la fecha y hora seleccionadas.", AMBER));
            return;
        }
        for (int i = 0; i < itineraries.length(); i++) {
            JSONObject itinerary = itineraries.optJSONObject(i);
            if (itinerary != null) results.addView(journeyCard(itinerary, i + 1), margin(-1, -2, 0, 0, 0, 12));
        }
    }

    private View journeyCard(JSONObject itinerary, int number) {
        LinearLayout box = card();
        String start = formatTime(itinerary.optString("startTime"));
        String end = formatTime(itinerary.optString("endTime"));
        int duration = itinerary.optInt("duration", 0) / 60;
        int transfers = itinerary.optInt("transfers", 0);
        LinearLayout head = row();
        TextView badge = text(String.valueOf(number), 16, BG, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(CYAN, 14));
        head.addView(badge, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout summary = col();
        summary.setPadding(dp(12), 0, 0, 0);
        summary.addView(text(start + " → " + end, 20, WHITE, true));
        summary.addView(text(duration + " min · " + (transfers == 0 ? "Directo" : transfers + " transbordo" + (transfers == 1 ? "" : "s")), 12, MUTED, false));
        head.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(head);

        JSONArray legs = itinerary.optJSONArray("legs");
        if (legs != null) {
            for (int i = 0; i < legs.length(); i++) {
                JSONObject leg = legs.optJSONObject(i);
                if (leg != null) box.addView(legCard(leg), margin(-1, -2, 0, 10, 0, 0));
            }
        }
        return box;
    }

    private View legCard(JSONObject leg) {
        LinearLayout box = col();
        box.setPadding(dp(12), dp(11), dp(12), dp(11));
        box.setBackground(round(CARD2, 14));
        String mode = leg.optString("mode", "");
        boolean transit = !("WALK".equals(mode) || "BIKE".equals(mode) || "CAR".equals(mode));
        String display = first(leg.optString("displayName"), leg.optString("routeShortName"), modeLabel(mode));
        String agency = leg.optString("agencyName", "");
        String headsign = leg.optString("headsign", "");
        boolean realTime = leg.optBoolean("realTime", false);
        boolean cancelled = leg.optBoolean("cancelled", false);
        int delay = delayMinutes(leg.optString("scheduledStartTime"), leg.optString("startTime"));
        int stateColor = cancelled ? RED : delay > 15 ? RED : delay > 5 ? ORANGE : delay > 1 ? AMBER : GREEN;

        LinearLayout top = row();
        top.addView(pill(display, transit ? stateColor : MUTED));
        TextView status;
        if (!transit) status = text(modeLabel(mode), 11, MUTED, true);
        else if (cancelled) status = text("CANCELADO", 11, RED, true);
        else if (realTime && delay > 0) status = text("+" + delay + " min", 12, stateColor, true);
        else if (realTime) status = text("EN HORA", 11, GREEN, true);
        else status = text("SIN TIEMPO REAL", 10, MUTED, true);
        status.setGravity(Gravity.END);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(top);

        JSONObject from = leg.optJSONObject("from");
        JSONObject to = leg.optJSONObject("to");
        String fromName = from == null ? "Origen" : from.optString("name", "Origen");
        String toName = to == null ? "Destino" : to.optString("name", "Destino");
        String actualStart = formatTime(leg.optString("startTime"));
        String scheduledStart = formatTime(leg.optString("scheduledStartTime"));
        String actualEnd = formatTime(leg.optString("endTime"));
        TextView route = text(actualStart + "  " + fromName + "\n" + actualEnd + "  " + toName, 14, WHITE, true);
        route.setPadding(0, dp(9), 0, 0);
        box.addView(route);
        if (!agency.isEmpty() || !headsign.isEmpty()) {
            String detail = (agency.isEmpty() ? "" : agency) + (agency.isEmpty() || headsign.isEmpty() ? "" : " · ") + (headsign.isEmpty() ? "" : "Destino " + headsign);
            box.addView(text(detail, 12, MUTED, false));
        }
        if (realTime && delay != 0 && !scheduledStart.equals(actualStart)) {
            box.addView(text("Horario previsto: " + scheduledStart, 11, MUTED, false));
        }
        appendAlerts(box, leg.optJSONArray("alerts"));
        if (from != null) appendAlerts(box, from.optJSONArray("alerts"));
        if (to != null) appendAlerts(box, to.optJSONArray("alerts"));
        return box;
    }

    private void requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locateNearbyStop();
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
        }
    }

    private void locateNearbyStop() {
        beginResults("Localizando paradas cercanas…");
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            showError("El servicio de ubicación no está disponible.", null);
            return;
        }
        try {
            Location best = null;
            for (String provider : locationManager.getProviders(true)) {
                Location l = locationManager.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 10 * 60 * 1000L) {
                loadNearbyDepartures(best.getLatitude(), best.getLongitude());
                return;
            }
            String provider = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
            pendingLocationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
                    pendingLocationListener = null;
                    loadNearbyDepartures(location.getLatitude(), location.getLongitude());
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onProviderDisabled(String provider) { }
            };
            locationManager.requestSingleUpdate(provider, pendingLocationListener, Looper.getMainLooper());
            ui.postDelayed(() -> {
                if (pendingLocationListener != null) {
                    try { locationManager.removeUpdates(pendingLocationListener); } catch (SecurityException ignored) { }
                    pendingLocationListener = null;
                    showError("No se ha podido obtener la ubicación. Busca la parada por nombre.", null);
                }
            }, 15000L);
        } catch (SecurityException e) {
            showError("No hay permiso para usar la ubicación.", e);
        }
    }

    private void loadNearbyDepartures(double lat, double lon) {
        beginResults("Consultando las próximas salidas cercanas…");
        pool.execute(() -> {
            try {
                String center = String.format(Locale.US, "%.6f,%.6f", lat, lon);
                String url = API + "/api/v6/stoptimes?center=" + enc(center) + "&radius=650&exactRadius=true&n=24&language=es&withAlerts=true";
                JSONObject data = new JSONObject(get(url));
                ui.post(() -> renderStopTimes("PARADAS CERCANAS", data));
            } catch (Exception e) {
                ui.post(() -> showError("No se han podido obtener las salidas cercanas.", e));
            }
        });
    }

    private void loadStopTimes(GeoPlace stop) {
        beginResults("Consultando horarios de " + stop.name + "…");
        pool.execute(() -> {
            try {
                String ref = !stop.id.isEmpty() ? stop.id : String.format(Locale.US, "%.6f,%.6f", stop.lat, stop.lon);
                String url;
                if (!stop.id.isEmpty()) url = API + "/api/v6/stoptimes?stopId=" + enc(ref) + "&n=24&language=es&withAlerts=true&fetchStops=true";
                else url = API + "/api/v6/stoptimes?center=" + enc(ref) + "&radius=350&n=24&language=es&withAlerts=true";
                JSONObject data = new JSONObject(get(url));
                ui.post(() -> renderStopTimes("SALIDAS · " + stop.name, data));
            } catch (Exception e) {
                ui.post(() -> showError("No se han podido obtener los horarios de esta parada.", e));
            }
        });
    }

    private void renderStopTimes(String title, JSONObject data) {
        finishLoading();
        resultsTitle.setText(title);
        JSONArray times = data.optJSONArray("stopTimes");
        if (times == null || times.length() == 0) {
            results.addView(messageCard("No aparecen próximas salidas en el intervalo consultado.", AMBER));
            return;
        }
        Set<String> alertKeys = new HashSet<>();
        for (int i = 0; i < times.length(); i++) {
            JSONObject item = times.optJSONObject(i);
            if (item != null) results.addView(stopTimeCard(item, alertKeys), margin(-1, -2, 0, 0, 0, 9));
        }
    }

    private View stopTimeCard(JSONObject item, Set<String> seenAlerts) {
        LinearLayout box = card();
        JSONObject place = item.optJSONObject("place");
        String route = first(item.optString("displayName"), item.optString("routeShortName"), modeLabel(item.optString("mode")));
        String headsign = item.optString("headsign", "Destino no indicado");
        String agency = item.optString("agencyName", "");
        boolean realTime = item.optBoolean("realTime", false);
        boolean cancelled = item.optBoolean("cancelled", false) || item.optBoolean("tripCancelled", false);
        String actual = place == null ? "--:--" : formatTime(first(place.optString("departure"), place.optString("arrival")));
        String scheduled = place == null ? "--:--" : formatTime(first(place.optString("scheduledDeparture"), place.optString("scheduledArrival")));
        int delay = place == null ? 0 : delayMinutes(first(place.optString("scheduledDeparture"), place.optString("scheduledArrival")), first(place.optString("departure"), place.optString("arrival")));
        int color = cancelled ? RED : delay > 15 ? RED : delay > 5 ? ORANGE : delay > 1 ? AMBER : GREEN;

        LinearLayout head = row();
        TextView time = text(actual, 23, cancelled ? RED : WHITE, true);
        head.addView(time, new LinearLayout.LayoutParams(dp(78), -2));
        LinearLayout info = col();
        info.addView(text(route + "  →  " + headsign, 16, WHITE, true));
        String stopName = place == null ? "" : place.optString("name", "");
        info.addView(text((agency.isEmpty() ? "" : agency + " · ") + stopName, 12, MUTED, false));
        head.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        String state = cancelled ? "CANCELADO" : realTime ? (delay > 0 ? "+" + delay + " min" : "EN HORA") : "HORARIO";
        head.addView(pill(state, realTime || cancelled ? color : MUTED));
        box.addView(head);
        if (!realTime) box.addView(text("Sin información de retraso en tiempo real", 11, MUTED, false));
        else if (!scheduled.equals(actual) && !"--:--".equals(scheduled)) box.addView(text("Hora programada: " + scheduled, 11, MUTED, false));
        appendUniqueAlerts(box, item.optJSONArray("alerts"), seenAlerts);
        if (place != null) appendUniqueAlerts(box, place.optJSONArray("alerts"), seenAlerts);
        return box;
    }

    private void loadRenfeAlerts() {
        beginResults("Consultando incidencias oficiales de Renfe…");
        pool.execute(() -> {
            try {
                JSONObject data = new JSONObject(get(RENFE_ALERTS));
                ui.post(() -> renderRenfeAlerts(data));
            } catch (Exception e) {
                ui.post(() -> showError("No se ha podido consultar el panel de incidencias de Renfe.", e));
            }
        });
    }

    private void renderRenfeAlerts(JSONObject data) {
        finishLoading();
        resultsTitle.setText("INCIDENCIAS OFICIALES · RENFE");
        JSONArray entities = data.optJSONArray("entity");
        int count = 0;
        Set<String> seen = new HashSet<>();
        if (entities != null) {
            for (int i = 0; i < entities.length(); i++) {
                JSONObject entity = entities.optJSONObject(i);
                JSONObject alert = entity == null ? null : entity.optJSONObject("alert");
                if (alert == null) continue;
                String header = translated(firstObject(alert, "headerText", "header_text"));
                String desc = translated(firstObject(alert, "descriptionText", "description_text"));
                String key = header + "|" + desc;
                if (key.trim().equals("|") || !seen.add(key)) continue;
                results.addView(alertCard(header.isEmpty() ? "Aviso de servicio" : header, desc, RED), margin(-1, -2, 0, 0, 0, 10));
                count++;
            }
        }
        if (count == 0) results.addView(messageCard("No aparecen incidencias generales en el feed recibido.", GREEN));
    }

    private JSONObject firstObject(JSONObject parent, String a, String b) {
        JSONObject o = parent.optJSONObject(a);
        return o != null ? o : parent.optJSONObject(b);
    }

    private String translated(JSONObject object) {
        if (object == null) return "";
        JSONArray translations = object.optJSONArray("translation");
        if (translations == null) return object.optString("text", "");
        String fallback = "";
        for (int i = 0; i < translations.length(); i++) {
            JSONObject t = translations.optJSONObject(i);
            if (t == null) continue;
            String value = t.optString("text", "").trim();
            if (fallback.isEmpty()) fallback = value;
            String lang = t.optString("language", "");
            if ("es".equalsIgnoreCase(lang) || "ca".equalsIgnoreCase(lang)) return value;
        }
        return fallback;
    }

    private void appendAlerts(LinearLayout parent, JSONArray alerts) {
        appendUniqueAlerts(parent, alerts, new HashSet<>());
    }

    private void appendUniqueAlerts(LinearLayout parent, JSONArray alerts, Set<String> seen) {
        if (alerts == null) return;
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject a = alerts.optJSONObject(i);
            if (a == null) continue;
            String header = a.optString("headerText", "Aviso");
            String desc = a.optString("descriptionText", "");
            String key = header + "|" + desc;
            if (!seen.add(key)) continue;
            LinearLayout warning = col();
            warning.setPadding(dp(10), dp(8), dp(10), dp(8));
            warning.setBackground(round(Color.argb(55, 248, 113, 113), 10));
            warning.addView(text("⚠ " + header, 12, RED, true));
            if (!desc.isEmpty() && !desc.equals(header)) warning.addView(text(desc, 11, WHITE, false));
            parent.addView(warning, margin(-1, -2, 0, 8, 0, 0));
        }
    }

    private View alertCard(String header, String desc, int color) {
        LinearLayout box = card();
        box.addView(text("⚠ " + header, 15, color, true));
        if (desc != null && !desc.isEmpty() && !desc.equals(header)) {
            TextView d = text(desc, 13, WHITE, false);
            d.setPadding(0, dp(7), 0, 0);
            box.addView(d);
        }
        return box;
    }

    private void beginResults(String message) {
        results.removeAllViews();
        resultsTitle.setText(message.toUpperCase(Locale.getDefault()));
        resultsTitle.setVisibility(View.VISIBLE);
        loading.setVisibility(View.VISIBLE);
    }

    private void finishLoading() {
        loading.setVisibility(View.GONE);
        resultsTitle.setVisibility(View.VISIBLE);
    }

    private void showError(String message, Exception error) {
        finishLoading();
        results.removeAllViews();
        String detail = error == null || error.getMessage() == null ? "" : "\n" + error.getMessage();
        results.addView(messageCard(message + detail, RED));
    }

    private View messageCard(String message, int color) {
        LinearLayout box = card();
        box.addView(text(message, 14, color, true));
        return box;
    }

    private String get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(18000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "SabadellTransportLive/3.0 (https://github.com/carvicasbd/Dreamlove)");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("Respuesta HTTP " + code);
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) out.append(line);
        reader.close();
        connection.disconnect();
        return out.toString();
    }

    private String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private int delayMinutes(String scheduled, String actual) {
        long s = parseIso(scheduled);
        long a = parseIso(actual);
        if (s <= 0 || a <= 0) return 0;
        return (int) Math.round((a - s) / 60000.0);
    }

    private long parseIso(String value) {
        if (value == null || value.length() < 19) return -1;
        try {
            String v = value.trim();
            int dot = v.indexOf('.');
            if (dot > 0) {
                int zone = Math.max(v.indexOf('+', dot), v.indexOf('-', dot));
                if (zone > dot) v = v.substring(0, dot) + v.substring(zone);
                else if (v.endsWith("Z")) v = v.substring(0, dot) + "Z";
            }
            if (v.endsWith("Z")) v = v.substring(0, v.length() - 1) + "+0000";
            if (v.length() >= 6 && v.charAt(v.length() - 3) == ':') v = v.substring(0, v.length() - 3) + v.substring(v.length() - 2);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            Date d = f.parse(v);
            return d == null ? -1 : d.getTime();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String formatTime(String iso) {
        long millis = parseIso(iso);
        if (millis > 0) {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());
            f.setTimeZone(TimeZone.getDefault());
            return f.format(new Date(millis));
        }
        if (iso != null && iso.length() >= 16) return iso.substring(11, 16);
        return "--:--";
    }

    private String modeLabel(String mode) {
        if (mode == null) return "Trayecto";
        switch (mode) {
            case "WALK": return "A pie";
            case "BUS": return "Autobús";
            case "COACH": return "Autocar";
            case "SUBURBAN": return "Rodalies";
            case "REGIONAL_RAIL": return "Regional";
            case "LONG_DISTANCE": return "Larga distancia";
            case "HIGHSPEED_RAIL": return "Alta velocidad";
            case "SUBWAY": return "Metro";
            case "TRAM": return "Tranvía";
            default: return mode.replace('_', ' ');
        }
    }

    private String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private LinearLayout card() {
        LinearLayout v = col();
        v.setPadding(dp(16), dp(16), dp(16), dp(16));
        v.setBackground(round(CARD, 20));
        return v;
    }

    private TextView section(String value) {
        TextView v = text(value, 12, MUTED, true);
        v.setPadding(dp(2), 0, 0, dp(8));
        return v;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(WHITE);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackground(round(CARD2, 12));
        e.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(54)));
        return e;
    }

    private TextView pickerText() {
        TextView v = text("", 12, WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(CARD2, 12));
        return v;
    }

    private TextView pill(String value, int color) {
        TextView v = text(value, 10, color, true);
        v.setPadding(dp(8), dp(5), dp(8), dp(5));
        GradientDrawable shape = round(Color.TRANSPARENT, 40);
        shape.setStroke(dp(1), Color.argb(160, Color.red(color), Color.green(color), Color.blue(color)));
        v.setBackground(shape);
        return v;
    }

    private Button filled(String value, int color) {
        Button b = baseButton(value, 12);
        b.setTextColor(BG);
        b.setBackground(round(color, 14));
        return b;
    }

    private Button outlined(String value, int color) {
        Button b = baseButton(value, 11);
        b.setTextColor(color);
        GradientDrawable shape = round(Color.TRANSPARENT, 14);
        shape.setStroke(dp(1), color);
        b.setBackground(shape);
        return b;
    }

    private Button baseButton(String value, int size) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(size);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinWidth(0);
        return b;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        v.setLineSpacing(dp(2), 1f);
        return v;
    }

    private LinearLayout col() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private LinearLayout.LayoutParams margin(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    private LinearLayout.LayoutParams equal() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface PlaceCallback { void onSelected(GeoPlace place); }

    private static class GeoPlace {
        final String id;
        final String name;
        final String label;
        final double lat;
        final double lon;
        final String type;

        GeoPlace(String id, String name, String label, double lat, double lon, String type) {
            this.id = id;
            this.name = name;
            this.label = label;
            this.lat = lat;
            this.lon = lon;
            this.type = type;
        }

        String ref() {
            if ("STOP".equals(type) && id != null && !id.isEmpty()) return id;
            return String.format(Locale.US, "%.6f,%.6f", lat, lon);
        }
    }
}
