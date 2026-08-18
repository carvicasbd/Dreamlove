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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private static final String ADMIN = "https://anuncioslimpieza.es/admin?seccion=importador&modo=demandas";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> seen = new HashSet<>();
    private final List<String> sources = new ArrayList<>();
    private WebView web; private EditText token, nSources, nPages, nMax, nDelay;
    private TextView phase, status; private Button start, stop; private SharedPreferences prefs;
    private BridgeClient client; private String script = "", userAgent = "";
    private volatile boolean running = false, captcha = false;
    private int si=0, pi=1, maxSources=10, pagesPerSource=2, maxAds=100, delay=4500, seq=0, scheduled=-1;
    private int pages=0, demands=0, stored=0, published=0, photos=0, staged=0, pendingPhone=0, pendingPhoto=0, skipped=0, errors=0, captchas=0, photoErrors=0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); prefs=getSharedPreferences("al_demandas",MODE_PRIVATE); script=asset("extract.js"); buildUi(); configureWeb(); restore(); render("Preparado para importar demandas desde Android.");
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView tv(String s,float z,int c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);return v;}
    private EditText input(String hint,String value,boolean text){EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setSingleLine(true);e.setTextSize(14);e.setPadding(dp(10),dp(7),dp(10),dp(7));e.setBackgroundColor(Color.rgb(247,249,248));e.setInputType(text?android.text.InputType.TYPE_CLASS_TEXT:android.text.InputType.TYPE_CLASS_NUMBER);return e;}
    private Button btn(String s,int c){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setTextSize(13);b.setBackgroundColor(c);return b;}
    private LinearLayout row(View... vs){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);for(View v:vs){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);lp.setMargins(dp(3),dp(3),dp(3),dp(3));r.addView(v,lp);}return r;}
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(242,246,244));
        ScrollView scroll=new ScrollView(this);LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(14),dp(10),dp(14),dp(8));p.setBackgroundColor(Color.WHITE);
        p.addView(tv("ANUNCIOS LIMPIEZA · ANDROID",11,Color.rgb(25,117,82),true));p.addView(tv("Importador premium de demandas",22,Color.rgb(17,24,21),true));TextView intro=tv("App Android nativa · sin Chrome, sin Lemur y sin extensiones. El navegador integrado queda visible para resolver manualmente cualquier CAPTCHA.",13,Color.rgb(80,95,88),false);intro.setPadding(0,dp(3),0,dp(6));p.addView(intro);
        token=input("Token del importador","",true);p.addView(token,new LinearLayout.LayoutParams(-1,-2));nSources=input("Fuentes","10",false);nPages=input("Páginas/fuente","2",false);nMax=input("Máx. perfiles","100",false);nDelay=input("Pausa ms","4500",false);p.addView(row(nSources,nPages));p.addView(row(nMax,nDelay));
        start=btn("▶ Iniciar",Color.rgb(19,129,88));stop=btn("■ Detener",Color.rgb(91,101,96));Button admin=btn("Panel web",Color.rgb(32,38,35));p.addView(row(start,stop,admin));phase=tv("",12,Color.rgb(24,114,81),true);status=tv("",12,Color.rgb(38,47,43),false);p.addView(phase);p.addView(status);scroll.addView(p);root.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1f));setContentView(root);stop.setEnabled(false);start.setOnClickListener(v->startImport());stop.setOnClickListener(v->stopImport("Detenido por el usuario."));admin.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(ADMIN)));}catch(Exception e){toast("No se pudo abrir el panel web.");}});
    }
    @SuppressLint({"SetJavaScriptEnabled","AddJavascriptInterface"}) private void configureWeb(){
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setLoadsImagesAutomatically(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);userAgent=s.getUserAgentString();CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);web.addJavascriptInterface(new Js(),"AndroidBridge");
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !BridgeClient.isMilanuncios(r.getUrl().toString());}@Override public void onPageFinished(WebView v,String u){if(!running||captcha)return;int x=seq;if(scheduled==x)return;scheduled=x;phase.setText("Página cargada · esperando "+delay+" ms");main.postDelayed(()->{if(running&&!captcha&&x==seq)web.evaluateJavascript(script,null);},delay);}});
    }
    private void restore(){token.setText(prefs.getString("token",""));nSources.setText(String.valueOf(prefs.getInt("sources",10)));nPages.setText(String.valueOf(prefs.getInt("pages",2)));nMax.setText(String.valueOf(prefs.getInt("max",100)));nDelay.setText(String.valueOf(prefs.getInt("delay",4500)));}
    private int num(EditText e,int d,int min,int max){try{return Math.max(min,Math.min(max,Integer.parseInt(e.getText().toString().trim())));}catch(Exception x){return d;}}
    private void startImport(){
        if(running)return;String t=token.getText().toString().trim();if(t.length()<32){toast("Pega el token del Importador Premium de Demandas.");return;}maxSources=num(nSources,10,1,150);pagesPerSource=num(nPages,2,1,10);maxAds=num(nMax,100,1,1000);delay=num(nDelay,4500,3000,30000);prefs.edit().putString("token",t).putInt("sources",maxSources).putInt("pages",pagesPerSource).putInt("max",maxAds).putInt("delay",delay).apply();client=new BridgeClient(t,userAgent);reset();running=true;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);start.setEnabled(false);stop.setEnabled(true);render("Conectando con anuncioslimpieza.es…");
        io.execute(()->{try{JSONObject r=client.post(new JSONObject().put("action","sources").put("kind","demand"));JSONArray a=r.optJSONArray("sources");if(a==null||a.length()==0)throw new Exception("No hay fuentes configuradas.");for(int i=0;i<a.length()&&sources.size()<maxSources;i++){String u=a.optString(i,"");if(BridgeClient.isMilanuncios(u))sources.add(u);}if(sources.isEmpty())throw new Exception("No hay fuentes válidas.");main.post(this::load);}catch(Exception e){main.post(()->fail(e.getMessage()));}});
    }
    private void reset(){sources.clear();seen.clear();si=0;pi=1;pages=demands=stored=published=photos=staged=pendingPhone=pendingPhoto=skipped=errors=captchas=photoErrors=0;captcha=false;seq=0;scheduled=-1;}
    private void load(){if(!running)return;if(demands>=maxAds||si>=sources.size()){finish("Captación terminada.");return;}if(pi>pagesPerSource){si++;pi=1;if(si>=sources.size()){finish("Captación terminada.");return;}}seq++;scheduled=-1;phase.setText("Listado "+(si+1)+"/"+sources.size()+" · página "+pi);web.loadUrl(withPage(sources.get(si),pi));}
    private String withPage(String raw,int p){try{Uri u=Uri.parse(raw);Uri.Builder b=u.buildUpon().clearQuery();for(String n:u.getQueryParameterNames()){if(n.equalsIgnoreCase("pagina"))continue;for(String v:u.getQueryParameters(n))b.appendQueryParameter(n,v);}if(p>1)b.appendQueryParameter("pagina",String.valueOf(p));return b.build().toString();}catch(Exception e){return p<=1?raw:raw+(raw.contains("?")?"&":"?")+"pagina="+p;}}
    private void process(String json){io.execute(()->{int fresh=0;try{JSONArray a=new JSONObject(json).optJSONArray("records");if(a==null)a=new JSONArray();pages++;for(int i=0;i<a.length()&&running&&demands<maxAds;i++){JSONObject d=a.optJSONObject(i);if(d==null)continue;String k=d.optString("card_key",d.optString("title")+"|"+d.optString("description")).trim().toLowerCase(Locale.ROOT);if(k.isEmpty()||seen.contains(k))continue;seen.add(k);fresh++;ingest(d);}}catch(Exception e){errors++;}int f=fresh;main.post(()->{if(!running)return;render(f+" demandas nuevas en esta página.");if(f==0&&pi>1){si++;pi=1;}else pi++;int pause=(pages>0&&pages%5==0)?12000:Math.max(1500,delay/2);if(pause==12000)phase.setText("Descanso de cortesía · 12 s");main.postDelayed(this::load,pause);});});}
    private void ingest(JSONObject d){try{d.put("kind","demand");JSONObject r=client.post(d);demands++;JSONObject st=r.optJSONObject("stored");boolean ok=st!=null&&st.optBoolean("stored",false);if(ok)stored++;else skipped++;int pub=r.optInt("published_id",0);if(pub>0)published++;if(r.optBoolean("pending_phone",false)||(st!=null&&"awaiting_phone".equals(st.optString("status"))))pendingPhone++;if(r.optBoolean("pending_photo",false)||(st!=null&&"awaiting_photo".equals(st.optString("status"))))pendingPhoto++;int id=r.optInt("import_id",0);if(id<=0&&st!=null)id=st.optInt("id",st.optInt("duplicate_of",0));JSONArray imgs=d.optJSONArray("images");if(id>0&&imgs!=null)for(int i=0;i<imgs.length()&&i<5&&running;i++){JSONObject rr=client.uploadPhoto(id,imgs.optString(i,""),d.optString("url",""));if(rr==null){photoErrors++;continue;}if(rr.optBoolean("stored",false)){photos++;if(rr.optBoolean("staged",false))staged++;}if(pub<=0&&rr.optInt("published_id",0)>0){pub=rr.optInt("published_id");published++;}}renderAsync(d.optString("title","Demanda")+(pub>0?" · publicada":""));}catch(Exception e){errors++;renderAsync("Incidencia: "+e.getMessage());}}
    private void captcha(){if(!running||captcha)return;captcha=true;captchas++;phase.setText("PAUSADO · CAPTCHA");render("Completa manualmente el CAPTCHA. La app continuará sola.");pollCaptcha();}
    private void pollCaptcha(){if(!running||!captcha)return;main.postDelayed(()->{String js="(function(){var t=(document.body&&document.body.innerText||'').toLowerCase();return t.length>220&&!(/captcha|completa el captcha|haz click para continuar|algo se detuvo|verifica que eres humano|comprueba que eres humano|unusual traffic|request blocked/.test(t));})()";web.evaluateJavascript(js,v->{if(!running||!captcha)return;if("true".equals(v)){captcha=false;phase.setText("CAPTCHA resuelto · reanudando…");main.postDelayed(()->web.evaluateJavascript(script,null),Math.max(4500,delay));}else pollCaptcha();});},2500);}
    private void finish(String m){running=false;captcha=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);start.setEnabled(true);stop.setEnabled(false);phase.setText("COMPLETADO");render(m);}
    private void stopImport(String m){if(!running)return;running=false;captcha=false;web.stopLoading();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);start.setEnabled(true);stop.setEnabled(false);phase.setText("DETENIDO");render(m);}
    private void fail(String m){errors++;running=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);start.setEnabled(true);stop.setEnabled(false);phase.setText("ERROR");render(m);}
    private void renderAsync(String m){main.post(()->render(m));}
    private void render(String m){status.setText("Fuentes "+Math.min(si+1,Math.max(1,sources.size()))+"/"+sources.size()+" · páginas "+pages+"\nDemandas "+demands+" · guardadas "+stored+" · publicadas "+published+" · fotos "+photos+(staged>0?" · foto.pend "+staged:"")+(pendingPhone>0?" · pend.tel "+pendingPhone:"")+(pendingPhoto>0?" · pend.foto "+pendingPhoto:"")+" · omitidas "+skipped+" · errores "+errors+(captchas>0?" · captchas "+captchas:"")+(photoErrors>0?" · err.foto "+photoErrors:"")+"\n"+m);}
    private String asset(String n){try(InputStream in=getAssets().open(n);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int k;while((k=in.read(b))!=-1)out.write(b,0,k);return new String(out.toByteArray(),StandardCharsets.UTF_8);}catch(Exception e){return "javascript:AndroidBridge.onJsError('extract.js');";}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){running=false;io.shutdownNow();if(web!=null){web.removeJavascriptInterface("AndroidBridge");web.destroy();}super.onDestroy();}
    private final class Js {@JavascriptInterface public void onExtract(String j){main.post(()->{if(running){phase.setText("Procesando demandas…");process(j);}});}@JavascriptInterface public void onCaptcha(){main.post(MainActivity.this::captcha);}@JavascriptInterface public void onJsError(String m){main.post(()->{errors++;pages++;render("Error de lectura: "+m);pi++;main.postDelayed(MainActivity.this::load,Math.max(3000,delay));});}}
}
