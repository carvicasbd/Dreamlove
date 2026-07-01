package com.carles.dondeestamitren;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RenfeClient {
    static final String VEHICLES_CERCANIAS = "https://gtfsrt.renfe.com/vehicle_positions.json";
    static final String VEHICLES_LD = "https://gtfsrt.renfe.com/vehicle_positions_LD.json";
    static final String TRIPS_CERCANIAS = "https://gtfsrt.renfe.com/trip_updates.json";
    static final String TRIPS_LD = "https://gtfsrt.renfe.com/trip_updates_LD.json";
    static final String ALERTS_CERCANIAS = "https://gtfsrt.renfe.com/alerts.json";

    private final SharedPreferences cache;

    RenfeClient(Context context) {
        cache = context.getSharedPreferences("renfe_cache", Context.MODE_PRIVATE);
    }

    List<TrainData> loadVehicles(String endpoint, String service) throws Exception {
        JSONObject root = new JSONObject(downloadWithCache(endpoint));
        JSONArray entities = root.optJSONArray("entity");
        List<TrainData> result = new ArrayList<>();
        if (entities == null) return result;

        long headerTimestamp = root.optJSONObject("header") != null
                ? root.optJSONObject("header").optLong("timestamp", 0L) : 0L;

        for (int i = 0; i < entities.length(); i++) {
            JSONObject entity = entities.optJSONObject(i);
            if (entity == null) continue;
            JSONObject vehicle = entity.optJSONObject("vehicle");
            if (vehicle == null) continue;
            JSONObject position = vehicle.optJSONObject("position");
            if (position == null || !position.has("latitude") || !position.has("longitude")) continue;

            TrainData train = new TrainData();
            JSONObject trip = vehicle.optJSONObject("trip");
            JSONObject descriptor = vehicle.optJSONObject("vehicle");
            train.id = nonEmpty(entity.optString("id", null));
            if (trip != null) {
                train.tripId = firstNonEmpty(trip.optString("tripId", null), trip.optString("trip_id", null));
                train.routeId = firstNonEmpty(trip.optString("routeId", null), trip.optString("route_id", null));
            }
            if (descriptor != null) {
                train.label = firstNonEmpty(descriptor.optString("label", null), descriptor.optString("id", null));
                if (train.id == null) train.id = nonEmpty(descriptor.optString("id", null));
            }
            train.latitude = position.optDouble("latitude");
            train.longitude = position.optDouble("longitude");
            train.timestamp = vehicle.optLong("timestamp", headerTimestamp);
            train.service = inferService(train.label, train.routeId, service);
            if (train.id == null) train.id = service + "_" + train.latitude + "_" + train.longitude;
            result.add(train);
        }
        return result;
    }

    Map<String, DelayData> loadDelays(String endpoint) throws Exception {
        JSONObject root = new JSONObject(downloadWithCache(endpoint));
        JSONArray entities = root.optJSONArray("entity");
        Map<String, DelayData> result = new HashMap<>();
        if (entities == null) return result;

        for (int i = 0; i < entities.length(); i++) {
            JSONObject entity = entities.optJSONObject(i);
            if (entity == null) continue;
            JSONObject update = firstObject(entity, "tripUpdate", "trip_update");
            if (update == null) continue;
            JSONObject trip = update.optJSONObject("trip");
            if (trip == null) continue;
            String tripId = firstNonEmpty(trip.optString("tripId", null), trip.optString("trip_id", null));
            if (tripId == null) continue;

            DelayData data = new DelayData();
            JSONArray stops = firstArray(update, "stopTimeUpdate", "stop_time_update");
            long now = System.currentTimeMillis() / 1000L;
            if (stops != null) {
                for (int j = 0; j < stops.length(); j++) {
                    JSONObject stop = stops.optJSONObject(j);
                    if (stop == null) continue;
                    JSONObject arrival = stop.optJSONObject("arrival");
                    JSONObject departure = stop.optJSONObject("departure");
                    if (data.delaySeconds == null) {
                        if (arrival != null && arrival.has("delay")) data.delaySeconds = arrival.optInt("delay");
                        else if (departure != null && departure.has("delay")) data.delaySeconds = departure.optInt("delay");
                    }
                    long time = 0L;
                    if (arrival != null) time = arrival.optLong("time", 0L);
                    if (time == 0L && departure != null) time = departure.optLong("time", 0L);
                    if (data.nextArrival == null && time >= now - 120L) {
                        data.nextArrival = time;
                        data.nextStopId = firstNonEmpty(stop.optString("stopId", null), stop.optString("stop_id", null));
                    }
                }
            }
            result.put(tripId, data);
        }
        return result;
    }

    List<AlertData> loadAlerts() throws Exception {
        JSONObject root = new JSONObject(downloadWithCache(ALERTS_CERCANIAS));
        JSONArray entities = root.optJSONArray("entity");
        List<AlertData> result = new ArrayList<>();
        if (entities == null) return result;

        for (int i = 0; i < entities.length(); i++) {
            JSONObject entity = entities.optJSONObject(i);
            if (entity == null) continue;
            JSONObject alert = entity.optJSONObject("alert");
            if (alert == null) continue;
            AlertData item = new AlertData();
            item.id = entity.optString("id", "alerta_" + i);
            item.title = translated(firstObject(alert, "headerText", "header_text"));
            item.description = translated(firstObject(alert, "descriptionText", "description_text"));
            item.effect = alert.optString("effect", "");
            if (item.title == null) item.title = "Aviso de servicio";
            if (item.description == null) item.description = "Sin descripción adicional";

            JSONArray informed = firstArray(alert, "informedEntity", "informed_entity");
            if (informed != null) {
                for (int j = 0; j < informed.length(); j++) {
                    JSONObject info = informed.optJSONObject(j);
                    if (info == null) continue;
                    String route = firstNonEmpty(info.optString("routeId", null), info.optString("route_id", null));
                    if (route != null && !item.routeIds.contains(route)) item.routeIds.add(route);
                }
            }
            result.add(item);
        }
        return result;
    }

    private String downloadWithCache(String endpoint) throws Exception {
        String key = "cache_" + Integer.toHexString(endpoint.hashCode());
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "DondeEstaMiTren/1.0 private-use");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            String text = readAll(connection.getInputStream());
            cache.edit().putString(key, text).putLong(key + "_time", System.currentTimeMillis()).apply();
            return text;
        } catch (Exception error) {
            String saved = cache.getString(key, null);
            if (saved != null && !saved.isBlank()) return saved;
            throw error;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
        }
        return builder.toString();
    }

    private static String translated(JSONObject object) {
        if (object == null) return null;
        JSONArray translations = object.optJSONArray("translation");
        if (translations == null) return null;
        for (int i = 0; i < translations.length(); i++) {
            JSONObject translation = translations.optJSONObject(i);
            if (translation == null) continue;
            String text = nonEmpty(translation.optString("text", null));
            if (text != null) return text;
        }
        return null;
    }

    private static JSONObject firstObject(JSONObject object, String first, String second) {
        JSONObject value = object.optJSONObject(first);
        return value != null ? value : object.optJSONObject(second);
    }

    private static JSONArray firstArray(JSONObject object, String first, String second) {
        JSONArray value = object.optJSONArray(first);
        return value != null ? value : object.optJSONArray(second);
    }

    private static String firstNonEmpty(String first, String second) {
        String value = nonEmpty(first);
        return value != null ? value : nonEmpty(second);
    }

    private static String nonEmpty(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return null;
        return value;
    }

    private static String inferService(String label, String route, String fallback) {
        String value = ((label == null ? "" : label) + " " + (route == null ? "" : route)).toUpperCase();
        if (value.contains("RODAL") || value.matches(".*\\bR[1-8].*")) return "Rodalies";
        if (value.contains("AVE") || value.contains("AVLO")) return "Alta Velocidad";
        if (value.contains("MEDIA") || value.contains(" MD")) return "Media Distancia";
        if (value.contains("CERCAN") || value.matches(".*\\bC[1-9].*")) return "Cercanías";
        return fallback;
    }
}
