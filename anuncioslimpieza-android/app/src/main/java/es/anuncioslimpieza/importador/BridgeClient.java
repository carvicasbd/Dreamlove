package es.anuncioslimpieza.importador;

import android.net.Uri;
import android.util.Base64;
import android.webkit.CookieManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class BridgeClient {
    private static final String BRIDGE = "https://anuncioslimpieza.es/?al_android_bridge=1";
    private final String token;
    private final String userAgent;

    BridgeClient(String token, String userAgent) {
        this.token = token;
        this.userAgent = userAgent == null ? "" : userAgent;
    }

    JSONObject post(JSONObject payload) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BRIDGE).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            if (!userAgent.isEmpty()) c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("X-AL-Bridge-Token", token);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = readAll(in);
            JSONObject json;
            try { json = new JSONObject(text); }
            catch (JSONException e) {
                String sample = text == null ? "" : text.replaceAll("\\s+", " ").trim();
                if (sample.length() > 90) sample = sample.substring(0, 90) + "…";
                throw new Exception("Respuesta no JSON del portal (HTTP " + code + ")" + (sample.isEmpty() ? "" : ": " + sample));
            }
            if (code < 200 || code >= 300 || !json.optBoolean("ok", false))
                throw new Exception(json.optString("error", "HTTP " + code));
            return json;
        } finally { if (c != null) c.disconnect(); }
    }

    JSONObject uploadPhoto(int importId, String imageUrl, String referer) throws Exception {
        Photo p = downloadPhoto(imageUrl, referer);
        if (p == null) return null;
        JSONObject req = new JSONObject()
                .put("action", "photo").put("kind", "demand")
                .put("import_id", importId).put("source_url", imageUrl)
                .put("mime_type", p.mime)
                .put("data_base64", Base64.encodeToString(p.bytes, Base64.NO_WRAP));
        return post(req);
    }

    private Photo downloadPhoto(String raw, String referer) {
        if (!isMilanuncios(raw)) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(raw).openConnection();
            c.setConnectTimeout(12000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
            if (!userAgent.isEmpty()) c.setRequestProperty("User-Agent", userAgent);
            if (isMilanuncios(referer)) c.setRequestProperty("Referer", referer);
            String cookie = CookieManager.getInstance().getCookie(raw);
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            int code = c.getResponseCode(); if (code < 200 || code >= 300) return null;
            String mime = c.getContentType(); if (mime == null) mime = "";
            mime = mime.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!(mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/webp") || mime.equals("image/avif"))) return null;
            if (c.getContentLength() > 6 * 1024 * 1024) return null;
            try (InputStream in = new BufferedInputStream(c.getInputStream()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int total = 0, n;
                while ((n = in.read(buf)) != -1) { total += n; if (total > 6 * 1024 * 1024) return null; out.write(buf, 0, n); }
                byte[] bytes = out.toByteArray(); if (bytes.length < 1500) return null;
                return new Photo(bytes, mime);
            }
        } catch (Exception e) { return null; }
        finally { if (c != null) c.disconnect(); }
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
            String line; while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static final class Photo {
        final byte[] bytes; final String mime;
        Photo(byte[] bytes, String mime) { this.bytes = bytes; this.mime = mime; }
    }
}
