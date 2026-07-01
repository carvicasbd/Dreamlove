package com.carles.sabadelltransport;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 70;
    private static final String MOUTE = "https://mou-te.gencat.cat/itinerary";

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

    private boolean inPortal;
    private WebView web;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        showHome();
    }

    @Override protected void onDestroy() {
        destroyWeb();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (inPortal) {
            if (web != null && web.canGoBack()) web.goBack();
            else {
                inPortal = false;
                destroyWeb();
                showHome();
            }
        } else super.onBackPressed();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_REQUEST) {
            if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Puedes escribir la parada manualmente.", Toast.LENGTH_LONG).show();
            }
            openPlanner(true);
        }
    }

    private void showHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = col();
        page.setPadding(dp(18), dp(20), dp(18), dp(36));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        page.addView(text("MOVILIDAD · SABADELL", 12, CYAN, true));
        TextView title = text("Sabadell\nTransport Live", 34, WHITE, true);
        title.setLineSpacing(0, .93f);
        page.addView(title);
        TextView intro = text("Horarios, retrasos, paradas e incidencias oficiales dentro de una sola app.", 15, MUTED, false);
        intro.setPadding(0, dp(8), 0, dp(18));
        page.addView(intro);

        page.addView(section("PLANIFICAR UN VIAJE"));
        LinearLayout planner = card();
        LinearLayout ph = row();
        ph.addView(badge("↔", CYAN));
        LinearLayout pcopy = col();
        pcopy.addView(text("Elegir salida y destino", 20, WHITE, true));
        pcopy.addView(text("Renfe, TUS, Sagalés, Monbus y conexiones", 13, MUTED, false));
        ph.addView(pcopy, new LinearLayout.LayoutParams(0, -2, 1));
        planner.addView(ph);
        TextView pi = text("Selecciona origen, destino, fecha y hora en el planificador oficial de transporte público de Catalunya.", 13, MUTED, false);
        pi.setPadding(0, dp(14), 0, 0);
        planner.addView(pi);
        Button route = filled("ELEGIR SALIDA → DESTINO", CYAN);
        route.setOnClickListener(v -> openPlanner(false));
        planner.addView(route, margin(-1, dp(52), 0, 16, 0, 0));
        Button stop = outlined("ESTOY EN UNA PARADA · USAR GPS", GREEN);
        stop.setOnClickListener(v -> requestStopLocation());
        planner.addView(stop, margin(-1, dp(50), 0, 10, 0, 0));
        page.addView(planner, margin(-1, -2, 0, 0, 0, 20));

        page.addView(section("OPERADORES"));
        page.addView(operator(
                "R", "Renfe / Rodalies", "Elige estaciones, consulta horarios, retrasos y afectaciones de R4/R8.", CYAN,
                new String[]{"HORARIOS", "ESTADO", "INCIDENCIAS"},
                new String[]{
                        "https://rodalies.gencat.cat/es/horaris/",
                        "https://rodalies.gencat.cat/es/inici/",
                        "https://rodalies.gencat.cat/es/alteracions_del_servei/"
                }), margin(-1, -2, 0, 0, 0, 12));

        page.addView(operator(
                "T", "TUS Sabadell", "Líneas urbanas, horarios, parada actual y afectaciones oficiales.", GREEN,
                new String[]{"HORARIOS", "PARADAS", "AVISOS"},
                new String[]{
                        "https://tus.es/index.php/es/informacion-de-las-lineas",
                        MOUTE,
                        "https://tus.es/index.php/es/"
                }), margin(-1, -2, 0, 0, 0, 12));

        page.addView(operator(
                "S", "Sagalés", "Origen/destino, horarios interurbanos, e13 y avisos por línea.", AMBER,
                new String[]{"RUTAS", "TIEMPO REAL", "AVISOS"},
                new String[]{
                        "https://www.sagales.com/es/lineas-regulares",
                        "https://www.sagales.com/es/linia/555",
                        "https://www.sagales.com/es/lineas-regulares"
                }), margin(-1, -2, 0, 0, 0, 12));

        page.addView(operator(
                "M", "Monbus Catalunya", "Horarios, expediciones, destino y paso estimado por parada.", ORANGE,
                new String[]{"HORARIOS", "TIEMPO REAL", "AVISOS"},
                new String[]{
                        "https://catalunya.monbus.es/",
                        "https://catalunya.monbus.es/tempsreal/",
                        "https://catalunya.monbus.es/"
                }), margin(-1, -2, 0, 0, 0, 18));

        LinearLayout note = card();
        note.addView(text("DATOS MOSTRADOS", 12, MUTED, true));
        note.addView(key(GREEN, "Horario o estimación disponible"));
        note.addView(key(AMBER, "Aviso o retraso que conviene revisar"));
        note.addView(key(ORANGE, "Desvío o cambio importante"));
        note.addView(key(RED, "Cancelación o incidencia grave"));
        TextView n = text("Los resultados se muestran dentro de la app desde las fuentes oficiales. Cuando no existe una API pública de retrasos, se abre el tiempo real del propio operador y no se inventan minutos.", 11, MUTED, false);
        n.setPadding(0, dp(14), 0, 0);
        note.addView(n);
        page.addView(note);
    }

    private View operator(String letter, String name, String description, int accent, String[] labels, String[] urls) {
        LinearLayout box = card();
        LinearLayout top = row();
        top.addView(badge(letter, accent));
        LinearLayout copy = col();
        copy.addView(text(name, 19, WHITE, true));
        TextView desc = text(description, 13, MUTED, false);
        desc.setPadding(0, dp(3), 0, dp(8));
        copy.addView(desc);
        copy.addView(pill("INFORMACIÓN OFICIAL INTEGRADA", accent));
        top.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(top);

        LinearLayout actions = row();
        for (int i = 0; i < labels.length; i++) {
            final int selected = i;
            Button button = smallFilled(labels[i], accent);
            button.setOnClickListener(v -> openPortal(name, labels, urls, accent, selected, false));
            actions.addView(button, equal());
        }
        box.addView(actions, margin(-1, dp(48), 0, 16, 0, 0));
        return box;
    }

    private void requestStopLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            openPlanner(true);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
        }
    }

    private void openPlanner(boolean location) {
        openPortal(location ? "Mi parada y próximos servicios" : "Salida → destino",
                new String[]{"PLANIFICAR", "MI PARADA", "INCIDENCIAS"},
                new String[]{MOUTE, MOUTE, "https://rodalies.gencat.cat/es/alteracions_del_servei/"},
                CYAN, location ? 1 : 0, location);
    }

    private void openPortal(String title, String[] labels, String[] urls, int accent, int selected, boolean locationHint) {
        inPortal = true;
        destroyWeb();

        LinearLayout page = col();
        page.setBackgroundColor(BG);
        setContentView(page);

        LinearLayout toolbar = row();
        toolbar.setPadding(dp(10), dp(8), dp(10), dp(8));
        toolbar.setBackgroundColor(CARD);
        Button back = outlineSmall("‹ VOLVER", accent);
        back.setOnClickListener(v -> onBackPressed());
        toolbar.addView(back);
        TextView heading = text(title, 16, WHITE, true);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12), 0, dp(8), 0);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button external = outlineSmall("↗", accent);
        external.setOnClickListener(v -> {
            String address = web == null ? urls[selected] : web.getUrl();
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address))); }
            catch (Exception e) { Toast.makeText(this, "No se puede abrir el navegador.", Toast.LENGTH_SHORT).show(); }
        });
        toolbar.addView(external);
        page.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(62)));

        LinearLayout tabs = row();
        tabs.setPadding(dp(7), dp(7), dp(7), dp(7));
        tabs.setBackgroundColor(CARD2);
        Button[] tabButtons = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            tabButtons[i] = smallFilled(labels[i], i == selected ? accent : MUTED);
            setTabStyle(tabButtons[i], i == selected ? accent : MUTED, i == selected);
            tabButtons[i].setOnClickListener(v -> {
                for (int j = 0; j < tabButtons.length; j++) setTabStyle(tabButtons[j], j == index ? accent : MUTED, j == index);
                web.loadUrl(urls[index]);
            });
            tabs.addView(tabButtons[i], equal());
        }
        page.addView(tabs, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView hint = text(locationHint
                ? "Pulsa el icono de ubicación del planificador para detectar tu parada o estación. También puedes escribirla manualmente."
                : "Selecciona los datos que necesites. El contenido inferior pertenece al portal oficial.",
                12, MUTED, false);
        hint.setPadding(dp(14), dp(9), dp(14), dp(9));
        page.addView(hint);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        page.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(settings.getUserAgentString() + " SabadellTransportLive/2.0");

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternal(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternal(Uri.parse(url));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                callback.invoke(origin, granted, false);
            }
        });
        web.loadUrl(urls[selected]);
        page.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private boolean handleExternal(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
        return true;
    }

    private void destroyWeb() {
        if (web != null) {
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.destroy();
            web = null;
        }
    }

    private void setTabStyle(Button button, int color, boolean active) {
        button.setTextColor(active ? BG : color);
        if (active) button.setBackground(round(color, 12));
        else {
            GradientDrawable shape = round(Color.TRANSPARENT, 12);
            shape.setStroke(dp(1), Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)));
            button.setBackground(shape);
        }
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

    private TextView badge(String value, int color) {
        TextView v = text(value, 19, BG, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(color, 15));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(48), dp(48));
        p.setMargins(0, 0, dp(12), 0);
        v.setLayoutParams(p);
        return v;
    }

    private TextView pill(String value, int color) {
        TextView v = text(value, 10, color, true);
        v.setPadding(dp(9), dp(5), dp(9), dp(5));
        GradientDrawable shape = round(Color.TRANSPARENT, 40);
        shape.setStroke(dp(1), Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)));
        v.setBackground(shape);
        return v;
    }

    private View key(int color, String value) {
        LinearLayout line = row();
        line.setPadding(0, dp(10), 0, 0);
        line.addView(text("●", 14, color, true));
        line.addView(text("  " + value, 13, MUTED, false));
        return line;
    }

    private Button filled(String value, int color) {
        Button b = baseButton(value, 13);
        b.setTextColor(BG);
        b.setBackground(round(color, 14));
        return b;
    }

    private Button outlined(String value, int color) {
        Button b = baseButton(value, 12);
        b.setTextColor(color);
        GradientDrawable shape = round(Color.TRANSPARENT, 14);
        shape.setStroke(dp(1), color);
        b.setBackground(shape);
        return b;
    }

    private Button smallFilled(String value, int color) {
        Button b = baseButton(value, 10);
        b.setTextColor(BG);
        b.setPadding(dp(3), 0, dp(3), 0);
        b.setBackground(round(color, 12));
        b.setMinWidth(0);
        b.setMinHeight(0);
        return b;
    }

    private Button outlineSmall(String value, int color) {
        Button b = baseButton(value, 11);
        b.setTextColor(color);
        b.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable shape = round(Color.TRANSPARENT, 12);
        shape.setStroke(dp(1), color);
        b.setBackground(shape);
        b.setMinWidth(0);
        b.setMinHeight(0);
        return b;
    }

    private Button baseButton(String value, int size) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(size);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
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

    private LinearLayout.LayoutParams margin(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
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
}
