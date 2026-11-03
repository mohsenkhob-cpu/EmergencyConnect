package ir.emergencyconnect;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.view.Gravity;
import android.widget.*;
import androidx.core.content.FileProvider;
import ir.emergencyconnect.engine.OpenVpn3Bridge;
import ir.emergencyconnect.engine.OpenVpnAndroidController;
import ir.emergencyconnect.engine.RahYabVpnService;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import android.util.Base64;

public class MainActivity extends Activity {
    final String API = "https://www.vpngate.net/api/iphone/";
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final ArrayList<Server> servers = new ArrayList<>();
    LinearLayout list; TextView status; Button refresh;
    TextView lampConnected, lampSearching, lampDisconnected;
    final Handler lampHandler = new Handler(Looper.getMainLooper());
    final Runnable lampPoll = new Runnable(){ public void run(){ updateConnectionLamps(); lampHandler.postDelayed(this,1000); }};
    int navy=Color.rgb(16,42,67), teal=Color.rgb(22,125,101);
    private static final int VPN_PERMISSION_REQUEST = 7412;
    private String pendingVpnProfile;
    private OpenVpnAndroidController externalOpenVpn;
    static class Server { String country, ip, host, config; long speed; int ping; }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(navy);
        getWindow().setNavigationBarColor(navy);
        build();
        externalOpenVpn = new OpenVpnAndroidController(this, new OpenVpnAndroidController.Listener() {
            @Override public void onStatus(String state, String message, String level) {
                runOnUiThread(() -> {
                    String normalized = state == null ? "" : state.toUpperCase(Locale.ROOT);
                    String lamp = "CONNECTED".equals(normalized) ? "connected"
                            : ("NOPROCESS".equals(normalized) || "DISCONNECTED".equals(normalized)
                            || "EXITING".equals(normalized) || "NOTCONNECTED".equals(normalized))
                            ? "disconnected" : "searching";
                    getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                            .putString("connection_lamp_state", lamp).apply();
                    updateConnectionLamps();
                    status.setText("موتور مستقل OpenVPN: " + (state == null ? "وضعیت نامشخص" : state)
                            + (message == null || message.isEmpty() ? "" : "\\n" + message)
                            + "\\nتوجه: فقط وضعیت CONNECTED نشانه برقراری نشست است؛ عبور ترافیک باید جداگانه آزمایش شود.");
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                            .putString("connection_lamp_state", "disconnected").apply();
                    updateConnectionLamps();
                    status.setText(message);
                    new AlertDialog.Builder(MainActivity.this).setTitle("خطای موتور OpenVPN")
                            .setMessage(message).setPositiveButton("بستن", null).show();
                });
            }
            @Override public void onInstallRequired() {
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("نصب موتور اتصال")
                        .setMessage("برای استفاده از موتور مستقل، ابتدا برنامه OpenVPN for Android را نصب کنید. راه‌یاب پس از نصب، پروفایل انتخابی را به‌صورت امن به آن تحویل می‌دهد.")
                        .setPositiveButton("صفحه نصب", (dialog, which) -> {
                            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/de.blinkt.openvpn/"))); }
                            catch (RuntimeException ignored) {}
                        })
                        .setNegativeButton("فعلاً نه", null).show());
            }
        });
        loadServers();
    }
    GradientDrawable shape(int c){ GradientDrawable d=new GradientDrawable(); d.setColor(c); d.setCornerRadius(22); return d; }
    TextView txt(String s,int size,int color,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.RIGHT); t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL); t.setPadding(8,7,8,7); return t; }
    void build(){
        ScrollView sc=new ScrollView(this); LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(18,18,18,24); root.setBackgroundColor(Color.rgb(244,247,250)); sc.addView(root); setContentView(sc);
        LinearLayout hero=new LinearLayout(this); hero.setOrientation(1); hero.setPadding(20,20,20,20); hero.setBackground(shape(navy));
        hero.addView(txt("راه‌یاب VPN",26,Color.WHITE,true)); hero.addView(txt("یافتن و آماده‌سازی سرورهای VPN Gate",15,Color.LTGRAY,false)); root.addView(hero);
        status=txt("برای دریافت فهرست سرورها دکمه زیر را بزنید.",15,navy,true); status.setPadding(5,18,5,12); root.addView(status);
        LinearLayout lamps = new LinearLayout(this);
        lamps.setOrientation(LinearLayout.HORIZONTAL);
        lamps.setGravity(Gravity.CENTER);
        lamps.setPadding(8,12,8,12);
        lamps.setBackground(shape(Color.WHITE));
        lampConnected = lampItem(lamps, "سبز: وصل");
        lampSearching = lampItem(lamps, "زرد: جستجو");
        lampDisconnected = lampItem(lamps, "قرمز: قطع");
        LinearLayout.LayoutParams lampParams = new LinearLayout.LayoutParams(-1,-2);
        lampParams.setMargins(0,0,0,12);
        root.addView(lamps,lampParams);
        updateConnectionLamps();
        refresh=new Button(this); refresh.setText("دریافت و به‌روزرسانی سرورها"); refresh.setAllCaps(false); refresh.setTextColor(Color.WHITE); refresh.setBackground(shape(teal)); root.addView(refresh); refresh.setOnClickListener(v->loadServers());

        Button selfTest=new Button(this);
        selfTest.setText("آزمایش هسته داخلی OpenVPN 3");
        selfTest.setAllCaps(false);
        selfTest.setOnClickListener(v->{
            selfTest.setEnabled(false);
            selfTest.setText("در حال آزمایش هسته…");
            worker.execute(()->{
                final String result=OpenVpn3Bridge.runCoreSelfTest();
                runOnUiThread(()->{
                    selfTest.setEnabled(true);
                    selfTest.setText("آزمایش هسته داخلی OpenVPN 3");
                    new AlertDialog.Builder(this)
                        .setTitle("گزارش آزمایش موتور داخلی")
                        .setMessage(result+"\n\n"+OpenVpn3Bridge.availabilityMessage()+"\n\nاین آزمایش اتصال VPN یا عبور ترافیک را بررسی نمی‌کند.")
                        .setPositiveButton("بستن",null).show();
                });
            });
        });
        root.addView(selfTest);

        Button diagnosticsButton=new Button(this);
        diagnosticsButton.setText("نمایش و کپی گزارش کامل اتصال");
        diagnosticsButton.setAllCaps(false);
        diagnosticsButton.setOnClickListener(v->{
            android.content.SharedPreferences diagnostics =
                    getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
            String report = "گزارش راه‌یاب VPN\\n"
                    + "پل JNI: " + diagnostics.getString("native_bind_result", "ثبت نشده") + "\\n"
                    + "شروع نشست: " + diagnostics.getString("session_start_result", "ثبت نشده") + "\\n"
                    + "آخرین رویداد موتور: " + diagnostics.getString("native_last_event", "هنوز رویدادی ثبت نشده") + "\\n"
                    + "تاریخچه رویدادها (حداکثر ۲۰ مورد):\\n"
                    + diagnostics.getString("native_event_history", "هنوز رویدادی ثبت نشده") + "\\n"
                    + "خطای شروع سرویس: " + diagnostics.getString("service_start_error", "ندارد") + "\\n"
                    + "وضعیت قابلیت اتصال: " + OpenVpn3Bridge.availabilityMessage() + "\\n"
                    + "توجه: این گزارش به‌تنهایی اتصال VPN یا عبور ترافیک را تأیید نمی‌کند.";
            new AlertDialog.Builder(this)
                    .setTitle("گزارش کامل اتصال")
                    .setMessage(report)
                    .setPositiveButton("کپی گزارش", (dialog,which)->{
                        android.content.ClipboardManager clipboard =
                                (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("RahYabVPN diagnostics", report));
                            Toast.makeText(this, "گزارش کپی شد؛ می‌توانید آن را اینجا ارسال کنید.", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("بستن", null)
                    .show();
        });
        root.addView(diagnosticsButton);

        TextView note=txt("روش اتصال پیشنهادی: راه‌یاب پروفایل سرور را به موتور مستقل OpenVPN for Android می‌سپارد. برای این روش، نصب برنامه OpenVPN for Android و تأیید مجوزهای خودش لازم است. موتور داخلی فعلی هنوز آزمایشی است.",13,Color.DKGRAY,false); note.setBackground(shape(Color.WHITE)); note.setPadding(14,14,14,14); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2); np.setMargins(0,12,0,8); root.addView(note);
        TextView warning=txt("شفاف‌سازی: اتصال داخلی در حالت آزمایشی است و هنوز روی گوشی واقعی تأیید نشده است. سرورهای عمومی داوطلبانه ممکن است قطع، کند یا دارای سیاست ثبت اطلاعات متفاوت باشند. برای بانکداری و اطلاعات حساس استفاده نکنید.",12,Color.rgb(150,70,30),true); root.addView(warning);
        list=new LinearLayout(this); list.setOrientation(1); root.addView(list);
        TextView foot=txt("منبع فهرست: VPN Gate دانشگاه تسوکوبا",11,Color.GRAY,false); foot.setGravity(Gravity.CENTER); root.addView(foot);
    }

    TextView lampItem(LinearLayout parent, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        TextView light = txt("●",25,Color.LTGRAY,true);
        light.setGravity(Gravity.CENTER);
        item.addView(light);
        TextView caption = txt(label,11,navy,true);
        caption.setGravity(Gravity.CENTER);
        item.addView(caption);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-2,1f);
        parent.addView(item,p);
        if (label.startsWith("سبز")) lampConnected=light;
        else if (label.startsWith("زرد")) lampSearching=light;
        else lampDisconnected=light;
        return light;
    }
    void updateConnectionLamps() {
        if (lampConnected==null || lampSearching==null || lampDisconnected==null) return;
        String state=getSharedPreferences("rah_yab_diagnostics",MODE_PRIVATE)
                .getString("connection_lamp_state","disconnected");
        lampConnected.setTextColor("connected".equals(state)?Color.rgb(20,160,65):Color.LTGRAY);
        lampSearching.setTextColor("searching".equals(state)?Color.rgb(235,170,0):Color.LTGRAY);
        lampDisconnected.setTextColor("disconnected".equals(state)?Color.rgb(210,45,45):Color.LTGRAY);
    }
    @Override protected void onResume() {
        super.onResume();
        lampHandler.removeCallbacks(lampPoll);
        lampHandler.post(lampPoll);
    }
    @Override protected void onPause() {
        lampHandler.removeCallbacks(lampPoll);
        super.onPause();
    }

    void loadServers(){
        refresh.setEnabled(false);
        status.setText("در حال دریافت فهرست سرورها…");
        list.removeAllViews();
        worker.execute(()->{
            ArrayList<Server> found=new ArrayList<>();
            String error=null;
            String[] sources = new String[] {
                API,
                "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv",
                "https://fastly.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv",
                "https://gcore.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv",
                "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/data/vpngate.csv"
            };
            for(String source : sources) {
                HttpURLConnection connection=null;
                BufferedReader reader=null;
                ArrayList<Server> batch=new ArrayList<>();
                try {
                    connection=(HttpURLConnection)new URL(source).openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(15000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent","RahYabVPN/1.1");
                    connection.setRequestProperty("Accept","text/plain,*/*");
                    int response=connection.getResponseCode();
                    if(response!=HttpURLConnection.HTTP_OK) throw new IOException("HTTP "+response);
                    reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),"UTF-8"));
                    String line;
                    boolean header=false;
                    int totalBytes=0;
                    final int maxBytes=8*1024*1024;
                    while((line=reader.readLine())!=null) {
                        totalBytes+=line.length()+1;
                        if(totalBytes>maxBytes) throw new IOException("حجم فهرست بیش از حد مجاز است.");
                        if(line.length()>256*1024) continue;
                        if(line.startsWith("#HostName")) { header=true; continue; }
                        if(!header||line.startsWith("#")||line.trim().isEmpty()) continue;
                        List<String> fields=parseCsv(line);
                        if(fields.size()<15) continue;
                        try {
                            String encoded=fields.get(14).trim();
                            byte[] raw=Base64.decode(encoded,Base64.DEFAULT);
                            if(raw.length==0||raw.length>256*1024) continue;
                            String config=new String(raw,"UTF-8");
                            if(!config.contains("client")||!config.contains("remote ")) continue;
                            Server server=new Server();
                            server.host=fields.get(0);
                            server.ip=fields.get(1);
                            server.ping=parseInt(fields.get(3));
                            server.speed=parseLong(fields.get(4));
                            server.country=fields.get(5);
                            server.config=config;
                            batch.add(server);
                            if(batch.size()>=500) break;
                        } catch(IllegalArgumentException ignored) {
                            // Skip malformed individual VPN Gate records.
                        }
                    }
                    if(!batch.isEmpty()) {
                        found.addAll(batch);
                        error=null;
                        break;
                    }
                    throw new IOException("فهرست CSV معتبر یا دارای سرور نبود.");
                } catch(Exception e) {
                    error=source+" : "+(e.getMessage()==null?"خطای ناشناخته":e.getMessage());
                } finally {
                    if(reader!=null) try { reader.close(); } catch(IOException ignored) {}
                    if(connection!=null) connection.disconnect();
                }
            }
            final String finalError=error;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()) return;
                refresh.setEnabled(true);
                servers.clear();
                servers.addAll(found);
                if(found.isEmpty()) {
                    status.setText("سروری دریافت نشد. اینترنت را بررسی و دوباره تلاش کنید.\n"+
                            (finalError==null?"":finalError));
                } else {
                    Collections.sort(servers,(x,y)->Long.compare(y.speed,x.speed));
                    status.setText("تعداد "+servers.size()+
                            " سرور دریافت شد. فهرست بر اساس سرعت اعلام‌شده مرتب شده؛ برای بررسی پروفایل روی سرور بزنید.");
                    render();
                    if(finalError!=null) status.append("\nدریافت فهرست با هشدار پایان یافت: "+finalError);
                }
            });
        });
    }
    static int parseInt(String s){try{return Integer.parseInt(s.trim());}catch(Exception e){return 9999;}}
    static long parseLong(String s){try{return Long.parseLong(s.trim());}catch(Exception e){return 0;}}
    List<String> parseCsv(String line){ ArrayList<String> out=new ArrayList<>(); StringBuilder x=new StringBuilder(); boolean q=false; for(int i=0;i<line.length();i++){char ch=line.charAt(i); if(ch=='"'){ if(q&&i+1<line.length()&&line.charAt(i+1)=='"'){x.append('"');i++;}else q=!q;} else if(ch==','&&!q){out.add(x.toString());x.setLength(0);}else x.append(ch);} out.add(x.toString()); return out; }
    void render(){ list.removeAllViews(); int n=Math.min(servers.size(),80); for(int i=0;i<n;i++){ final Server s=servers.get(i); LinearLayout card=new LinearLayout(this); card.setOrientation(1); card.setPadding(12,10,12,10); card.setBackground(shape(Color.WHITE)); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.setMargins(0,6,0,6); card.setLayoutParams(cp);
        card.addView(txt((s.country==null?"نامشخص":s.country)+"  |  "+s.ip,16,navy,true)); card.addView(txt("پینگ گزارش‌شده: "+(s.ping>=9999?"نامشخص":s.ping+" ms")+"   |   سرعت اعلامی: "+(s.speed/1000000)+" Mbps",12,Color.DKGRAY,false)); card.addView(txt("برای بررسی و انتخاب روش اتصال لمس کنید",12,teal,true)); card.setOnClickListener(v->importProfile(s)); list.addView(card);
    }}
    void importProfile(Server s){
        String config = s == null ? "" : s.config;
        boolean hasClient = config != null && config.contains("client");
        boolean hasRemote = config != null && config.contains("remote ");
        String report = "سرور انتخاب‌شده: " + (s == null ? "نامشخص" : s.ip)
                + "\\nدستور client: " + (hasClient ? "موجود" : "یافت نشد")
                + "\\nدستور remote: " + (hasRemote ? "موجود" : "یافت نشد")
                + "\\n\\nاین بررسی فقط ساختار ابتدایی فایل را کنترل می‌کند. موتور مستقل OpenVPN تنظیمات کامل را هنگام شروع اتصال بررسی خواهد کرد.";
        new AlertDialog.Builder(this)
                .setTitle("آماده‌سازی پروفایل")
                .setMessage(report)
                .setPositiveButton("اتصال با موتور مستقل OpenVPN", (dialog,which)->connectExternalOpenVpn(s))
                .setNeutralButton("انتقال دستی فایل پروفایل", (dialog,which)->exportProfile(s))
                .setNegativeButton("بستن",null)
                .show();
    }

    private void connectExternalOpenVpn(Server server) {
        if (server == null || server.config == null || server.config.trim().isEmpty()) {
            status.setText("فایل تنظیمات سرور خالی است.");
            return;
        }
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("connection_lamp_state", "searching").apply();
        updateConnectionLamps();
        status.setText("در حال تحویل پروفایل به موتور مستقل OpenVPN…");
        if (externalOpenVpn == null) {
            status.setText("کنترل‌کننده موتور مستقل هنوز آماده نیست؛ برنامه را دوباره باز کنید.");
            return;
        }
        externalOpenVpn.connect(server.config, "RahYab-" + server.ip);
    }

    private void requestInternalVpn(Server server) {
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("connection_lamp_state", "searching").apply();
        updateConnectionLamps();
        if (server == null || server.config == null || server.config.trim().isEmpty()) {
            status.setText("فایل تنظیمات سرور خالی است.");
            return;
        }
        pendingVpnProfile = server.config;
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            try {
                startActivityForResult(prepare, VPN_PERMISSION_REQUEST);
            } catch (RuntimeException error) {
                pendingVpnProfile = null;
                new AlertDialog.Builder(this)
                    .setTitle("مجوز VPN")
                    .setMessage("نمایش درخواست مجوز VPN ممکن نشد: " + error.getMessage())
                    .setPositiveButton("بستن", null).show();
            }
        } else {
            startInternalVpnService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (externalOpenVpn != null && externalOpenVpn.onActivityResult(requestCode, resultCode)) return;
        if (requestCode != VPN_PERMISSION_REQUEST) return;
        if (resultCode == RESULT_OK && pendingVpnProfile != null) {
            startInternalVpnService();
        } else {
            pendingVpnProfile = null;
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("connection_lamp_state", "disconnected").apply();
            updateConnectionLamps();
            status.setText("مجوز اتصال VPN داده نشد؛ هیچ تونلی ساخته نشد.");
        }
    }

    private void startInternalVpnService() {
        final String profile = pendingVpnProfile;
        pendingVpnProfile = null;
        if (profile == null || profile.trim().isEmpty()) return;
        Intent service = new Intent(this, RahYabVpnService.class);
        service.setAction(RahYabVpnService.ACTION_START);
        service.putExtra(RahYabVpnService.EXTRA_PROFILE, profile);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            status.setText("درخواست اتصال داخلی ارسال شد؛ در حال بررسی نتیجه شروع سرویس…");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                android.content.SharedPreferences diagnostics =
                        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
                String serviceError = diagnostics.getString("service_start_error", null);
                String bindResult = diagnostics.getString("native_bind_result", "ثبت نشده");
                String startResult = diagnostics.getString("session_start_result", "هنوز پاسخی از شروع نشست ثبت نشده است.");
                String lastEvent = diagnostics.getString("native_last_event", "هنوز رویدادی از موتور دریافت نشده است.");
                String eventHistory = diagnostics.getString("native_event_history", "هنوز رویدادی از موتور دریافت نشده است.");
                if (serviceError != null && !serviceError.isEmpty()) {
                    status.setText("اندروید اجازه شروع سرویس VPN را نداد:\\n" + serviceError);
                } else if (!"OK".equals(bindResult)) {
                    status.setText("اتصال پل موتور OpenVPN به اندروید برقرار نشد.\\nگزارش فنی: " + bindResult);
                } else {
                    status.setText("پل JNI: OK\\nنتیجه شروع نشست: " + startResult
                            + "\\nآخرین رویداد موتور: " + lastEvent
                            + "\\nتاریخچه رویدادها:\\n" + eventHistory
                            + "\\nتوجه: این گزارش به‌تنهایی تأیید اتصال VPN نیست.");
                }
            }, 2500);
        } catch (RuntimeException error) {
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("connection_lamp_state", "disconnected").apply();
            updateConnectionLamps();
            status.setText("شروع سرویس VPN ناموفق بود: " + error.getMessage());
        }
    }

    void exportProfile(Server s){
        try{
            File dir=new File(getCacheDir(),"profiles");
            if(!dir.exists()&&!dir.mkdirs()) throw new IOException("ساخت پوشه موقت ناموفق بود");
            File f=new File(dir,"vpngate-"+s.ip.replace('.','_')+".ovpn");
            try(FileOutputStream o=new FileOutputStream(f)){ o.write(s.config.getBytes("UTF-8")); }
            Uri u=FileProvider.getUriForFile(this,getPackageName()+".provider",f);
            Intent i=new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u,"application/x-openvpn-profile");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,"باز کردن پروفایل با برنامه OpenVPN"));
        }catch(Exception e){
            new AlertDialog.Builder(this).setTitle("انتقال پروفایل انجام نشد")
                .setMessage("برای اتصال فعلی از OpenVPN for Android استفاده کنید.\n"+e.getMessage())
                .setPositiveButton("متوجه شدم",null).show();
        }
    }
    @Override protected void onDestroy(){ if(externalOpenVpn!=null) externalOpenVpn.close(); super.onDestroy(); worker.shutdownNow();}
}
