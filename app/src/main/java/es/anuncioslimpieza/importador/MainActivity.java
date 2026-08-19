package es.anuncioslimpieza.importador;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String ADMIN = "https://anuncioslimpieza.es/admin?seccion=importador";
    private static final String[] PROVINCE_LABELS = {
            "Todas las provincias","Álava","Albacete","Alicante","Almería","Asturias","Ávila","Badajoz","Baleares","Barcelona","Burgos","Cáceres","Cádiz","Cantabria","Castellón","Ceuta","Ciudad Real","Córdoba","Cuenca","Girona","Granada","Guadalajara","Guipúzcoa","Huelva","Huesca","Jaén","A Coruña","La Rioja","Las Palmas","León","Lleida","Lugo","Madrid","Málaga","Melilla","Murcia","Navarra","Ourense","Palencia","Pontevedra","Salamanca","Segovia","Sevilla","Soria","Tarragona","Santa Cruz de Tenerife","Teruel","Toledo","Valencia","Valladolid","Vizcaya","Zamora","Zaragoza"
    };
    private static final String[] PROVINCE_SLUGS = {
            "","alava","albacete","alicante","almeria","asturias","avila","badajoz","baleares","barcelona","burgos","caceres","cadiz","cantabria","castellon","ceuta","ciudad-real","cordoba","cuenca","girona","granada","guadalajara","guipuzcoa","huelva","huesca","jaen","a-coruna","la-rioja","las-palmas","leon","lleida","lugo","madrid","malaga","melilla","murcia","navarra","ourense","palencia","pontevedra","salamanca","segovia","sevilla","soria","tarragona","tenerife","teruel","toledo","valencia","valladolid","vizcaya","zamora","zaragoza"
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> seen = new HashSet<>();
    private final List<String> sources = new ArrayList<>();

    private SharedPreferences prefs;
    private WebView web;
    private EditText token, nSources, nPages, nMax, nDelay;
    private Spinner province;
    private TextView phase, status;
    private Button start, stop;
    private BridgeClient client;
    private String script = "";
    private String selectedProvinceSlug = "";
    private String selectedProvinceLabel = "Todas las provincias";
    private volatile boolean running = false, captcha = false;
    private int si = 0, pi = 1, maxSources = 20, pagesPerSource = 3, maxAds = 200, delay = 4500, seq = 0, scheduled = -1;
    private int pages = 0, ads = 0, offers = 0, demands = 0, stored = 0, published = 0, skipped = 0, errors = 0, captchas = 0;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("al_empleo_v23", MODE_PRIVATE);
        script = asset("extract.js");
        try {
            buildUi();
            restore();
            configureWebSafely();
            render(web == null ? "WebView no disponible. Actualiza Android System WebView/Chrome." : "Preparado para importar ofertas y demandas.");
        } catch (Throwable t) {
            showStartupError(t);
        }
    }

    private void showStartupError(Throwable t) {
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(18), dp(24), dp(18), dp(18));
            TextView a = tv("Importador Empleos", 24, Color.rgb(20,30,26), true);
            TextView b = tv("La app ha podido abrir, pero un componente de Android ha fallado.\n\n" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()), 14, Color.rgb(120,30,30), false);
            root.addView(a); root.addView(b); setContentView(root);
        } catch (Throwable ignored) { finish(); }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView tv(String s, float z, int c, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(z); v.setTextColor(c); if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); return v; }
    private EditText input(String hint, String value, boolean text) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); e.setTextSize(14); e.setPadding(dp(10),dp(7),dp(10),dp(7)); e.setBackgroundColor(Color.rgb(247,249,248)); e.setInputType(text ? android.text.InputType.TYPE_CLASS_TEXT : android.text.InputType.TYPE_CLASS_NUMBER); return e; }
    private Button btn(String s, int c) { Button b = new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setAllCaps(false); b.setTextSize(13); b.setBackgroundColor(c); return b; }
    private LinearLayout row(View... vs) { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); for (View v : vs) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); lp.setMargins(dp(3),dp(3),dp(3),dp(3)); r.addView(v,lp); } return r; }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(242,246,244));
        ScrollView scroll = new ScrollView(this); LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(dp(14),dp(10),dp(14),dp(8)); p.setBackgroundColor(Color.WHITE);
        p.addView(tv("ANUNCIOS LIMPIEZA · ANDROID",11,Color.rgb(25,117,82),true));
        p.addView(tv("Importador de ofertas + demandas",22,Color.rgb(17,24,21),true));
        TextView intro = tv("Importa anuncios con o sin foto. No se limita a anuncios marcados como Novedad. Elige una provincia o todas.",13,Color.rgb(80,95,88),false); intro.setPadding(0,dp(3),0,dp(6)); p.addView(intro);
        token = input("Token del importador","",true); p.addView(token,new LinearLayout.LayoutParams(-1,-2));
        TextView provinceLabel = tv("Provincia de búsqueda",12,Color.rgb(45,65,56),true); provinceLabel.setPadding(dp(2),dp(8),0,dp(3)); p.addView(provinceLabel);
        province = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,PROVINCE_LABELS); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); province.setAdapter(adapter); province.setPadding(dp(8),0,dp(8),0); province.setBackgroundColor(Color.rgb(247,249,248)); p.addView(province,new LinearLayout.LayoutParams(-1,dp(48)));
        nSources = input("Fuentes","20",false); nPages = input("Páginas/fuente","3",false); nMax = input("Máx. anuncios","200",false); nDelay = input("Pausa ms","4500",false); p.addView(row(nSources,nPages)); p.addView(row(nMax,nDelay));
        start = btn("▶ Iniciar",Color.rgb(19,129,88)); stop = btn("■ Detener",Color.rgb(91,101,96)); Button admin = btn("Panel web",Color.rgb(32,38,35)); p.addView(row(start,stop,admin));
        phase = tv("",12,Color.rgb(24,114,81),true); status = tv("",12,Color.rgb(38,47,43),false); p.addView(phase); p.addView(status);
        scroll.addView(p); root.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        try { web = new WebView(this); root.addView(web,new LinearLayout.LayoutParams(-1,0,1f)); }
        catch (Throwable t) { web = null; TextView w = tv("Android WebView no está disponible: " + t.getClass().getSimpleName(),12,Color.rgb(150,35,35),false); root.addView(w,new LinearLayout.LayoutParams(-1,-2)); }
        setContentView(root);
        stop.setEnabled(false);
        start.setOnClickListener(v -> startImport());
        stop.setOnClickListener(v -> stopImport("Detenido por el usuario."));
        admin.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ADMIN))); } catch (Exception e) { toast("No se pudo abrir el panel web."); } });
    }

    @SuppressLint({"SetJavaScriptEnabled","AddJavascriptInterface"})
    private void configureWebSafely() {
        if (web == null) { start.setEnabled(false); return; }
        try {
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setLoadsImagesAutomatically(true); s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false);
            if (android.os.Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            try { CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(web,true); } catch (Throwable ignored) {}
            web.addJavascriptInterface(new Js(),"AndroidBridge");
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !BridgeClient.isMilanuncios(r.getUrl().toString()); }
                @Override public void onPageFinished(WebView v, String u) {
                    if (!running || captcha) return;
                    int x = seq; if (scheduled == x) return; scheduled = x;
                    phase.setText("Página cargada · esperando " + delay + " ms");
                    main.postDelayed(() -> { if (running && !captcha && x == seq && web != null) web.evaluateJavascript(script,null); },delay);
                }
            });
        } catch (Throwable t) {
            web = null; start.setEnabled(false); phase.setText("WebView no disponible"); status.setText(t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    private void restore() {
        token.setText(prefs.getString("token","")); nSources.setText(String.valueOf(prefs.getInt("sources",20))); nPages.setText(String.valueOf(prefs.getInt("pages",3))); nMax.setText(String.valueOf(prefs.getInt("max",200))); nDelay.setText(String.valueOf(prefs.getInt("delay",4500)));
        String saved = prefs.getString("province_slug",""); int pos = 0; for (int i=0;i<PROVINCE_SLUGS.length;i++) if (PROVINCE_SLUGS[i].equals(saved)) { pos=i; break; }
        province.setSelection(pos); selectedProvinceSlug = PROVINCE_SLUGS[pos]; selectedProvinceLabel = PROVINCE_LABELS[pos];
    }

    private int num(EditText e,int d,int min,int max) { try { return Math.max(min,Math.min(max,Integer.parseInt(e.getText().toString().trim()))); } catch (Exception x) { return d; } }

    private void startImport() {
        if (running) return; if (web == null) { toast("Android WebView no está disponible."); return; }
        String t = token.getText().toString().trim(); if (t.length() < 32) { toast("Pega el token del Importador Premium."); return; }
        int pos = Math.max(0,province.getSelectedItemPosition()); pos = Math.min(pos,PROVINCE_SLUGS.length-1); selectedProvinceSlug = PROVINCE_SLUGS[pos]; selectedProvinceLabel = PROVINCE_LABELS[pos];
        maxSources=num(nSources,20,1,150); pagesPerSource=num(nPages,3,1,15); maxAds=num(nMax,200,1,1500); delay=num(nDelay,4500,3000,30000);
        prefs.edit().putString("token",t).putString("province_slug",selectedProvinceSlug).putInt("sources",maxSources).putInt("pages",pagesPerSource).putInt("max",maxAds).putInt("delay",delay).apply();
        client = new BridgeClient(t); reset(); running = true; getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); start.setEnabled(false); stop.setEnabled(true); render("Conectando con anuncioslimpieza.es · " + selectedProvinceLabel + "…");
        io.execute(() -> {
            try {
                JSONObject r = client.post(new JSONObject().put("action","sources").put("kind","mixed")); JSONArray a = r.optJSONArray("sources");
                if (a != null) for (int i=0;i<a.length() && sources.size()<maxSources;i++) { String u=a.optString(i,""); if (!BridgeClient.isMilanuncios(u)) continue; if (selectedProvinceSlug.isEmpty() || matchesProvince(u,selectedProvinceSlug)) sources.add(u); }
                if (!selectedProvinceSlug.isEmpty() && sources.isEmpty()) sources.add("https://www.milanuncios.com/servicio-domestico-en-" + selectedProvinceSlug + "/");
                if (selectedProvinceSlug.isEmpty() && sources.isEmpty()) sources.add("https://www.milanuncios.com/servicio-domestico/");
                main.post(this::load);
            } catch (Exception e) { main.post(() -> fail(e.getMessage())); }
        });
    }

    private boolean matchesProvince(String raw,String slug) { try { String p = Uri.parse(raw).getPath(); if (p==null) return false; p=p.toLowerCase(Locale.ROOT); String s=slug.toLowerCase(Locale.ROOT); return p.contains("-en-"+s+"/") || p.contains("/"+s+"/") || p.contains(s); } catch (Exception e) { return false; } }
    private void reset() { sources.clear(); seen.clear(); si=0; pi=1; pages=ads=offers=demands=stored=published=skipped=errors=captchas=0; captcha=false; seq=0; scheduled=-1; }
    private void load() { if (!running) return; if (ads>=maxAds || si>=sources.size()) { finish("Captación terminada."); return; } if (pi>pagesPerSource) { si++; pi=1; if (si>=sources.size()) { finish("Captación terminada."); return; } } seq++; scheduled=-1; phase.setText("Listado " + (si+1) + "/" + sources.size() + " · página " + pi); web.loadUrl(withPage(sources.get(si),pi)); }
    private String withPage(String raw,int p) { try { Uri u=Uri.parse(raw); Uri.Builder b=u.buildUpon().clearQuery(); for(String n:u.getQueryParameterNames()){ if(n.equalsIgnoreCase("pagina"))continue; for(String v:u.getQueryParameters(n))b.appendQueryParameter(n,v); } if(p>1)b.appendQueryParameter("pagina",String.valueOf(p)); return b.build().toString(); } catch(Exception e){ return p<=1?raw:raw+(raw.contains("?")?"&":"?")+"pagina="+p; } }

    private void process(String json) {
        io.execute(() -> {
            int fresh=0;
            try {
                JSONArray a = new JSONObject(json).optJSONArray("records"); if(a==null)a=new JSONArray(); pages++;
                for(int i=0;i<a.length() && running && ads<maxAds;i++) {
                    JSONObject d=a.optJSONObject(i); if(d==null)continue;
                    String k=d.optString("card_key",d.optString("title")+"|"+d.optString("description")).trim().toLowerCase(Locale.ROOT); if(k.isEmpty()||seen.contains(k))continue;
                    seen.add(k); fresh++; ingest(d);
                }
            } catch(Exception e) { errors++; }
            int f=fresh; main.post(() -> { if(!running)return; render(f+" anuncios detectados en esta página."); if(f==0&&pi>1){si++;pi=1;}else pi++; int pause=(pages>0&&pages%5==0)?12000:Math.max(1500,delay/2); if(pause==12000)phase.setText("Descanso de cortesía · 12 s"); main.postDelayed(this::load,pause); });
        });
    }

    private void ingest(JSONObject d) {
        try {
            d.put("kind","mixed"); JSONObject r=client.post(d); ads++;
            String kind=r.optString("kind",d.optString("kind_hint","")); if("offer".equals(kind))offers++; else if("demand".equals(kind))demands++;
            JSONObject st=r.optJSONObject("stored"); boolean ok=st!=null&&st.optBoolean("stored",false); if(ok)stored++; else skipped++;
            if(r.optInt("published_id",0)>0)published++;
            renderAsync(d.optString("title","Anuncio") + (r.optInt("published_id",0)>0?" · publicado":""));
        } catch(Exception e) { errors++; renderAsync("Incidencia: "+e.getMessage()); }
    }

    private void captcha() { if(!running||captcha)return; captcha=true; captchas++; phase.setText("PAUSADO · CAPTCHA"); render("Completa manualmente el CAPTCHA. La app continuará sola."); pollCaptcha(); }
    private void pollCaptcha() { if(!running||!captcha||web==null)return; main.postDelayed(() -> { String js="(function(){var t=(document.body&&document.body.innerText||'').toLowerCase();return t.length>220&&!(/captcha|completa el captcha|haz click para continuar|algo se detuvo|verifica que eres humano|comprueba que eres humano|unusual traffic|request blocked/.test(t));})()"; web.evaluateJavascript(js,v -> { if(!running||!captcha)return; if("true".equals(v)){captcha=false;phase.setText("CAPTCHA resuelto · reanudando…");main.postDelayed(() -> web.evaluateJavascript(script,null),Math.max(4500,delay));}else pollCaptcha(); }); },2500); }
    private void finish(String m) { running=false; captcha=false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); start.setEnabled(web!=null); stop.setEnabled(false); phase.setText("COMPLETADO"); render(m); }
    private void stopImport(String m) { if(!running)return; running=false; captcha=false; if(web!=null)web.stopLoading(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); start.setEnabled(web!=null); stop.setEnabled(false); phase.setText("DETENIDO"); render(m); }
    private void fail(String m) { errors++; running=false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); start.setEnabled(web!=null); stop.setEnabled(false); phase.setText("ERROR"); render(m==null?"Error desconocido":m); }
    private void renderAsync(String m) { main.post(() -> render(m)); }
    private void render(String m) { if(status==null)return; status.setText("Provincia " + selectedProvinceLabel + " · Fuentes " + Math.min(si+1,Math.max(1,sources.size())) + "/" + sources.size() + " · páginas " + pages + "\nAnuncios " + ads + " · ofertas " + offers + " · demandas " + demands + " · guardados " + stored + " · publicados " + published + " · omitidos " + skipped + " · errores " + errors + (captchas>0?" · captchas "+captchas:"") + "\n" + m); }
    private String asset(String n) { try(InputStream in=getAssets().open(n); ByteArrayOutputStream out=new ByteArrayOutputStream()){ byte[] b=new byte[8192]; int k; while((k=in.read(b))!=-1)out.write(b,0,k); return new String(out.toByteArray(),StandardCharsets.UTF_8); } catch(Exception e){ return "javascript:AndroidBridge.onJsError('extract.js');"; } }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() { running=false; io.shutdownNow(); if(web!=null){ try{web.removeJavascriptInterface("AndroidBridge");web.destroy();}catch(Throwable ignored){} } super.onDestroy(); }

    private final class Js {
        @JavascriptInterface public void onExtract(String j) { main.post(() -> { if(running){phase.setText("Procesando anuncios…");process(j);} }); }
        @JavascriptInterface public void onCaptcha() { main.post(MainActivity.this::captcha); }
        @JavascriptInterface public void onJsError(String m) { main.post(() -> { errors++; pages++; render("Error de lectura: "+m); pi++; main.postDelayed(MainActivity.this::load,Math.max(3000,delay)); }); }
    }
}
