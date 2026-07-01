package com.carles.sabadelltransport;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TRIPS = "https://gtfsrt.renfe.com/trip_updates.json";
    private static final String ALERTS = "https://gtfsrt.renfe.com/alerts.json";
    private static final long REFRESH = 60_000L;

    private final int BG = Color.rgb(8,15,28), CARD = Color.rgb(17,27,46), CARD2 = Color.rgb(25,38,62);
    private final int WHITE = Color.rgb(241,245,249), MUTED = Color.rgb(148,163,184), CYAN = Color.rgb(34,211,238);
    private final int GREEN = Color.rgb(52,211,153), AMBER = Color.rgb(251,191,36), ORANGE = Color.rgb(251,146,60), RED = Color.rgb(248,113,113);

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout renfeBox;
    private TextView renfeState, lastUpdate;
    private ProgressBar spinner;
    private boolean inWeb;
    private WebView web;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!inWeb) loadRenfe(false);
            ui.postDelayed(this, REFRESH);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        dashboard();
        loadRenfe(true);
        ui.postDelayed(ticker, REFRESH);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(ticker);
        pool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (inWeb) {
            if (web != null && web.canGoBack()) web.goBack();
            else { inWeb = false; dashboard(); loadRenfe(false); }
        } else super.onBackPressed();
    }

    private void dashboard() {
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(BG); setContentView(root);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); root.addView(scroll, new FrameLayout.LayoutParams(-1,-1));
        LinearLayout page = column(); page.setPadding(dp(18),dp(20),dp(18),dp(36)); scroll.addView(page, new ScrollView.LayoutParams(-1,-2));

        page.addView(txt("MOVILIDAD · SABADELL",12,CYAN,true));
        TextView title = txt("Sabadell\nTransport Live",34,WHITE,true); title.setLineSpacing(0,.93f); page.addView(title);
        TextView sub = txt("Retrasos, incidencias y tiempos oficiales de Renfe, TUS, Sagalés y Monbus.",15,MUTED,false);
        sub.setPadding(0,dp(8),0,dp(16)); page.addView(sub);

        LinearLayout bar = row(); bar.setPadding(dp(12),dp(10),dp(12),dp(10)); bar.setBackground(round(CARD,16));
        bar.addView(txt("●",14,GREEN,true));
        lastUpdate = txt(" Preparando datos…",13,MUTED,false); bar.addView(lastUpdate,new LinearLayout.LayoutParams(0,-2,1));
        Button refresh = small("ACTUALIZAR",CYAN); refresh.setOnClickListener(v -> loadRenfe(true)); bar.addView(refresh);
        page.addView(bar, margins(-1,-2,0,0,0,18));

        page.addView(section("RENFE / RODALIES"));
        LinearLayout rc = card();
        LinearLayout head = row(); head.addView(badge("R",CYAN));
        LinearLayout names = column(); names.addView(txt("Estado ferroviario",19,WHITE,true)); names.addView(txt("GTFS-Realtime oficial · refresco cada 60 s",12,MUTED,false));
        head.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        spinner = new ProgressBar(this); head.addView(spinner,new LinearLayout.LayoutParams(dp(30),dp(30))); rc.addView(head);
        renfeState = txt("Conectando con Renfe…",14,MUTED,false); renfeState.setPadding(0,dp(14),0,dp(8)); rc.addView(renfeState);
        renfeBox = column(); rc.addView(renfeBox); page.addView(rc,margins(-1,-2,0,0,0,20));

        page.addView(section("AUTOBUSES"));
        page.addView(operator("T","TUS Sabadell","Líneas urbanas y llegadas por parada",GREEN,"https://tus.es/index.php/es/informacion-de-las-lineas"),margins(-1,-2,0,0,0,12));
        page.addView(operator("S","Sagalés","Interurbanos, e13 y avisos de servicio",AMBER,"https://www.sagales.com/es/linia/555"),margins(-1,-2,0,0,0,12));
        page.addView(operator("M","Monbus Catalunya","Igualada, Esparreguera, Manresa y Barcelona",ORANGE,"https://catalunya.monbus.es/tempsreal/"),margins(-1,-2,0,0,0,18));

        LinearLayout legend = card(); legend.addView(txt("CÓDIGO DE ESTADO",12,MUTED,true));
        legend.addView(legend(GREEN,"En hora o sin incidencias conocidas")); legend.addView(legend(AMBER,"Retraso leve: 2–5 minutos"));
        legend.addView(legend(ORANGE,"Retraso alto: 6–15 minutos")); legend.addView(legend(RED,"Más de 15 minutos o cancelación")); page.addView(legend);
        TextView foot = txt("La app no calcula ni inventa retrasos: muestra información oficial disponible de cada operador.",11,MUTED,false);
        foot.setGravity(Gravity.CENTER); foot.setPadding(dp(12),dp(22),dp(12),0); page.addView(foot);
    }

    private View operator(String letter,String name,String detail,int accent,String url) {
        LinearLayout c = card(), top = row(); top.addView(badge(letter,accent));
        LinearLayout copy = column(); copy.addView(txt(name,19,WHITE,true)); TextView d=txt(detail,13,MUTED,false); d.setPadding(0,dp(3),0,dp(7)); copy.addView(d); copy.addView(pill("INFORMACIÓN OFICIAL INTEGRADA",accent));
        top.addView(copy,new LinearLayout.LayoutParams(0,-2,1)); c.addView(top);
        Button b = action("ABRIR INFORMACIÓN OFICIAL",accent); b.setOnClickListener(v -> openWeb(name,url)); c.addView(b,margins(-1,dp(48),0,16,0,0)); return c;
    }

    private void loadRenfe(boolean clear) {
        if (renfeBox == null) return;
        if (!online()) { spinner.setVisibility(View.GONE); renfeState.setText("Sin conexión a Internet"); renfeState.setTextColor(RED); return; }
        spinner.setVisibility(View.VISIBLE); renfeState.setText("Consultando retrasos e incidencias oficiales…"); renfeState.setTextColor(MUTED); if (clear) renfeBox.removeAllViews();
        pool.execute(() -> {
            try {
                List<Delay> delays = parseDelays(get(TRIPS)); List<Notice> notices = parseNotices(get(ALERTS));
                ui.post(() -> render(delays,notices));
            } catch (Exception e) { ui.post(() -> error(e)); }
        });
    }

    private void render(List<Delay> delays,List<Notice> notices) {
        if (renfeBox == null) return; spinner.setVisibility(View.GONE); renfeBox.removeAllViews();
        lastUpdate.setText(" Datos actualizados a las "+new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date()));
        int severe=0; for(Delay d:delays) if(d.cancelled||d.minutes>15) severe++;
        if(severe>0){renfeState.setText(severe+" servicios con incidencia importante");renfeState.setTextColor(RED);}
        else if(!delays.isEmpty()){renfeState.setText(delays.size()+" servicios con retraso detectado");renfeState.setTextColor(AMBER);}
        else{renfeState.setText("Sin retrasos destacados en el feed actual");renfeState.setTextColor(GREEN);}
        renfeBox.addView(pill("PRIORIDAD: R4 · R8 · SABADELL",CYAN));
        if(delays.isEmpty()) renfeBox.addView(empty("No aparecen retrasos superiores a 1 minuto en los datos recibidos."),margins(-1,-2,0,10,0,0));
        else { TextView h=section("RETRASOS DESTACADOS");h.setPadding(0,dp(13),0,0);renfeBox.addView(h); for(int i=0;i<Math.min(10,delays.size());i++)renfeBox.addView(delayView(delays.get(i))); }
        if(!notices.isEmpty()){TextView h=section("AVISOS OFICIALES");h.setPadding(0,dp(14),0,0);renfeBox.addView(h);for(int i=0;i<Math.min(5,notices.size());i++)renfeBox.addView(noticeView(notices.get(i)));}
        Button official=action("ABRIR RODALIES OFICIAL",CYAN);official.setOnClickListener(v->openWeb("Rodalies de Catalunya","https://rodalies.gencat.cat/es/inici/"));renfeBox.addView(official,margins(-1,dp(48),0,16,0,0));
    }

    private void error(Exception e){if(renfeBox==null)return;spinner.setVisibility(View.GONE);renfeState.setText("No se ha podido leer el servicio de Renfe");renfeState.setTextColor(RED);renfeBox.removeAllViews();renfeBox.addView(empty(e.getMessage()==null?"Error de conexión":e.getMessage()));Button b=action("REINTENTAR",CYAN);b.setOnClickListener(v->loadRenfe(true));renfeBox.addView(b,margins(-1,dp(48),0,12,0,0));}

    private List<Delay> parseDelays(String raw)throws Exception{
        JSONArray es=new JSONObject(raw).optJSONArray("entity");List<Delay> out=new ArrayList<>();if(es==null)return out;
        for(int i=0;i<es.length();i++){JSONObject e=es.optJSONObject(i),u=e==null?null:e.optJSONObject("tripUpdate");if(u==null&&e!=null)u=e.optJSONObject("trip_update");if(u==null)continue;JSONObject t=u.optJSONObject("trip");if(t==null)continue;
            String route=first(t.optString("routeId"),t.optString("route_id"),"Rodalies"),trip=first(t.optString("tripId"),t.optString("trip_id"),e.optString("id"));
            String rel=first(t.optString("scheduleRelationship"),t.optString("schedule_relationship"),"");boolean cancelled=rel.toUpperCase(Locale.ROOT).contains("CANCEL");int max=cancelled?999999:0;String stop="";
            JSONArray ss=u.optJSONArray("stopTimeUpdate");if(ss==null)ss=u.optJSONArray("stop_time_update");if(ss!=null)for(int j=0;j<ss.length();j++){JSONObject s=ss.optJSONObject(j);if(s==null)continue;String sr=first(s.optString("scheduleRelationship"),s.optString("schedule_relationship"),"");if("SKIPPED".equalsIgnoreCase(sr))cancelled=true;int d=Math.max(delay(s.optJSONObject("arrival")),delay(s.optJSONObject("departure")));if(d>max&&d<86400){max=d;stop=first(s.optString("stopId"),s.optString("stop_id"),"");}}
            int min=cancelled?999:Math.max(0,Math.round(max/60f));if(cancelled||min>1)out.add(new Delay(route,trip,stop,min,cancelled,priority(route,trip,stop)));
        }
        Collections.sort(out,(a,b)->{if(a.priority!=b.priority)return a.priority?-1:1;if(a.cancelled!=b.cancelled)return a.cancelled?-1:1;return Integer.compare(b.minutes,a.minutes);});return out;
    }

    private List<Notice> parseNotices(String raw)throws Exception{
        JSONArray es=new JSONObject(raw).optJSONArray("entity");List<Notice> p=new ArrayList<>(),g=new ArrayList<>();Set<String> seen=new HashSet<>();if(es==null)return p;
        for(int i=0;i<es.length();i++){JSONObject e=es.optJSONObject(i),a=e==null?null:e.optJSONObject("alert");if(a==null)continue;String title=translated(a.optJSONObject("headerText"));if(title.isEmpty())title=translated(a.optJSONObject("header_text"));String desc=translated(a.optJSONObject("descriptionText"));if(desc.isEmpty())desc=translated(a.optJSONObject("description_text"));String key=(title+" "+desc).trim();if(key.isEmpty()||seen.contains(key))continue;seen.add(key);boolean pri=priority(key);String route="";
            JSONArray inf=a.optJSONArray("informedEntity");if(inf==null)inf=a.optJSONArray("informed_entity");if(inf!=null)for(int j=0;j<inf.length();j++){JSONObject x=inf.optJSONObject(j);if(x==null)continue;String r=first(x.optString("routeId"),x.optString("route_id"),"");if(!r.isEmpty())route=route.isEmpty()?r:route+" · "+r;if(priority(r))pri=true;}
            Notice n=new Notice(title.isEmpty()?"Aviso de servicio":title,desc,route,pri);if(pri)p.add(n);else g.add(n);
        }p.addAll(g);return p;
    }

    private int delay(JSONObject o){return o==null?0:o.optInt("delay",0);}
    private String translated(JSONObject o){if(o==null)return"";JSONArray a=o.optJSONArray("translation");if(a==null)return o.optString("text","");String f="";for(int i=0;i<a.length();i++){JSONObject t=a.optJSONObject(i);if(t==null)continue;String v=t.optString("text","").trim(),l=t.optString("language","");if(f.isEmpty())f=v;if("es".equalsIgnoreCase(l)||"ca".equalsIgnoreCase(l))return v;}return f;}
    private boolean priority(String...vs){for(String s:vs){String v=s==null?"":s.toUpperCase(Locale.ROOT);if(v.contains("R4")||v.contains("R8")||v.contains("SABADELL"))return true;}return false;}

    private String get(String address)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","SabadellTransportLive/1.0 Android");int code=c.getResponseCode();if(code<200||code>=300)throw new Exception("Respuesta HTTP "+code);BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null)b.append(line);r.close();c.disconnect();return b.toString();}

    private void openWeb(String title,String url){inWeb=true;LinearLayout page=column();page.setBackgroundColor(BG);setContentView(page);LinearLayout tb=row();tb.setPadding(dp(10),dp(8),dp(10),dp(8));tb.setBackgroundColor(CARD);Button back=small("‹ VOLVER",CYAN);back.setOnClickListener(v->onBackPressed());tb.addView(back);TextView t=txt(title,16,WHITE,true);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(dp(12),0,dp(8),0);tb.addView(t,new LinearLayout.LayoutParams(0,dp(46),1));Button ext=small("↗",CYAN);ext.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));}catch(Exception e){Toast.makeText(this,"No se puede abrir el navegador",Toast.LENGTH_SHORT).show();}});tb.addView(ext);page.addView(tb,new LinearLayout.LayoutParams(-1,dp(62)));ProgressBar p=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);p.setMax(100);page.addView(p,new LinearLayout.LayoutParams(-1,dp(3)));web=new WebView(this);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setLoadWithOverviewMode(true);s.setUseWideViewPort(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);web.setWebViewClient(new WebViewClient());web.setWebChromeClient(new WebChromeClient(){@Override public void onProgressChanged(WebView v,int n){p.setProgress(n);p.setVisibility(n>=100?View.GONE:View.VISIBLE);}});web.loadUrl(url);page.addView(web,new LinearLayout.LayoutParams(-1,0,1));}

    private View delayView(Delay d){LinearLayout r=row();r.setPadding(dp(12),dp(11),dp(12),dp(11));r.setBackground(round(CARD2,14));int c=d.cancelled||d.minutes>15?RED:d.minutes>5?ORANGE:AMBER;r.addView(pill(d.route.isEmpty()?"RENFE":d.route,d.priority?CYAN:c));LinearLayout info=column();info.setPadding(dp(10),0,dp(8),0);info.addView(txt("Tren "+d.trip,14,WHITE,true));info.addView(txt(d.stop.isEmpty()?"Actualización en ruta":"Punto "+d.stop,11,MUTED,false));r.addView(info,new LinearLayout.LayoutParams(0,-2,1));TextView state=txt(d.cancelled?"CANCELADO":"+"+d.minutes+" min",14,c,true);state.setGravity(Gravity.END);r.addView(state);return bottom(r,7);}
    private View noticeView(Notice n){LinearLayout b=column();b.setPadding(dp(12),dp(11),dp(12),dp(11));b.setBackground(round(CARD2,14));LinearLayout top=row();top.addView(txt("!",18,n.priority?RED:AMBER,true));TextView t=txt(n.title,14,WHITE,true);t.setPadding(dp(8),0,0,0);top.addView(t,new LinearLayout.LayoutParams(0,-2,1));b.addView(top);if(!n.route.isEmpty()){TextView r=txt(n.route,11,CYAN,true);r.setPadding(dp(26),dp(4),0,0);b.addView(r);}if(!n.desc.isEmpty()&&!n.desc.equals(n.title)){TextView d=txt(n.desc,12,MUTED,false);d.setMaxLines(4);d.setPadding(dp(26),dp(5),0,0);b.addView(d);}return bottom(b,7);}
    private View legend(int c,String s){LinearLayout r=row();r.setPadding(0,dp(10),0,0);r.addView(txt("●",14,c,true));r.addView(txt("  "+s,13,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));return r;}
    private TextView empty(String s){TextView v=txt(s,13,MUTED,false);v.setPadding(dp(14),dp(14),dp(14),dp(14));v.setBackground(round(CARD2,14));return v;}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(16),dp(16),dp(16),dp(16));v.setBackground(round(CARD,20));return v;}
    private TextView section(String s){TextView v=txt(s,12,MUTED,true);v.setPadding(dp(2),0,0,dp(8));return v;}
    private TextView badge(String s,int c){TextView v=txt(s,19,BG,true);v.setGravity(Gravity.CENTER);v.setBackground(round(c,15));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(48),dp(48));p.setMargins(0,0,dp(12),0);v.setLayoutParams(p);return v;}
    private TextView pill(String s,int c){TextView v=txt(s,10,c,true);v.setPadding(dp(9),dp(5),dp(9),dp(5));GradientDrawable g=round(Color.TRANSPARENT,40);g.setStroke(dp(1),Color.argb(150,Color.red(c),Color.green(c),Color.blue(c)));v.setBackground(g);return v;}
    private Button action(String s,int c){Button b=new Button(this);b.setText(s);b.setTextColor(BG);b.setTextSize(12);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setAllCaps(false);b.setBackground(round(c,14));b.setStateListAnimator(null);return b;}
    private Button small(String s,int c){Button b=new Button(this);b.setText(s);b.setTextColor(c);b.setTextSize(11);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setAllCaps(false);b.setPadding(dp(10),0,dp(10),0);GradientDrawable g=round(Color.TRANSPARENT,12);g.setStroke(dp(1),Color.argb(150,Color.red(c),Color.green(c),Color.blue(c)));b.setBackground(g);b.setStateListAnimator(null);b.setMinHeight(0);b.setMinWidth(0);return b;}
    private TextView txt(String s,int sp,int c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextColor(c);v.setTextSize(sp);v.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));v.setLineSpacing(dp(2),1f);return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private GradientDrawable round(int c,int radius){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(radius));return g;}
    private View bottom(View v,int m){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(m),0,0);v.setLayoutParams(p);return v;}
    private LinearLayout.LayoutParams margins(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private boolean online(){ConnectivityManager c=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);NetworkInfo n=c==null?null:c.getActiveNetworkInfo();return n!=null&&n.isConnected();}
    private String first(String...v){for(String s:v)if(s!=null&&!s.trim().isEmpty())return s.trim();return"";}

    static class Delay{final String route,trip,stop;final int minutes;final boolean cancelled,priority;Delay(String r,String t,String s,int m,boolean c,boolean p){route=r;trip=t;stop=s;minutes=m;cancelled=c;priority=p;}}
    static class Notice{final String title,desc,route;final boolean priority;Notice(String t,String d,String r,boolean p){title=t;desc=d;route=r;priority=p;}}
}
