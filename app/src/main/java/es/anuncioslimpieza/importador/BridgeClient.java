package es.anuncioslimpieza.importador;

import android.net.Uri;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class BridgeClient {
    private static final String BRIDGE = "https://anuncioslimpieza.es/android-empleo-bridge.php";
    private final String token;

    BridgeClient(String token) { this.token = token == null ? "" : token.trim(); }

    JSONObject post(JSONObject payload) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BRIDGE).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-AL-Bridge-Token", token);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = readAll(in);
            JSONObject json;
            try { json = new JSONObject(text); }
            catch (JSONException e) { throw new Exception("Respuesta no válida del portal (HTTP " + code + ")"); }
            if (code < 200 || code >= 300 || !json.optBoolean("ok", false))
                throw new Exception(json.optString("error", "HTTP " + code));
            return json;
        } finally { if (c != null) c.disconnect(); }
    }

    static boolean isMilanuncios(String raw) {
        try {
            String host = Uri.parse(raw == null ? "" : raw).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("milanuncios.com") || host.endsWith(".milanuncios.com");
        } catch (Exception e) { return false; }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
