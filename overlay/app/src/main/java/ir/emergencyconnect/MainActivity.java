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
import ir.emergencyconnect.engine.RahYabVpnService;
import java.io.*;
import java.net.*;
import android.net.TrafficStats;
import java.util.*;
import java.util.concurrent.*;
import android.util.Base64;

public class MainActivity extends Activity {
    final String API = "https://www.vpngate.net/api/iphone/";
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final ArrayList<Server> servers = new ArrayList<>();
    LinearLayout list; TextView status; Button refresh;
    Server selectedServer; TextView liveSpeedView;
    final Handler speedHandler = new Handler(Looper.getMainLooper());
    long lastRxBytes=-1, lastTxBytes=-1;
    final Runnable speedPoll = new Runnable(){ public void run(){ updateLiveSpeed(); speedHandler.postDelayed(this,1000); }};
    TextView lampConnected, lampSearching, lampDisconnected;
    final Handler lampHandler = new Handler(Looper.getMainLooper());
    final Runnable lampPoll = new Runnable(){ public void run(){ updateConnectionLamps(); lampHandler.postDelayed(this,1000); }};
    int navy=Color.rgb(16,42,67), teal=Color.rgb(22,125,101);
    private static final int VPN_PERMISSION_REQUEST = 7412;
    private String pendingVpnProfile;
    static class Server { String country, ip, host, config, source; long speed; int ping, measuredPing=-1; boolean tcpReachable; }

    @Override public void onCreate(Bundle b){ super.onCreate(b); getWindow().setStatusBarColor(navy); getWindow().setNavigationBarColor(navy); build(); requestNotificationPermissionIfNeeded(); loadServers(); }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7413);
        }
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

        TextView note=txt("روش اتصال: دکمه اصلی «اتصال آزمایشی داخل راه‌یاب» درخواست اتصال از موتور داخلی را می‌فرستد. گزینه «انتقال پروفایل به OpenVPN» فقط فایل را به برنامه OpenVPN می‌دهد و وارد کردن فایل به‌تنهایی اتصال را برقرار نمی‌کند.",13,Color.DKGRAY,false); note.setBackground(shape(Color.WHITE)); note.setPadding(14,14,14,14); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2); np.setMargins(0,12,0,8); root.addView(note);
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
        speedHandler.removeCallbacks(speedPoll);
        speedHandler.post(speedPoll);
    }
    @Override protected void onPause() {
        lampHandler.removeCallbacks(lampPoll);
        speedHandler.removeCallbacks(speedPoll);
        super.onPause();
    }

    void loadServers(){
        refresh.setEnabled(false);
        status.setText("در حال دریافت و ارزیابی سرورها از چند مسیر منبع…");
        list.removeAllViews();
        selectedServer=null;
        worker.execute(()->{
            ArrayList<Server> candidates=new ArrayList<>();
            HashSet<String> seen=new HashSet<>();
            String[] sources = new String[] {
                API,
                "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv",
                "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/data/vpngate.csv"
            };
            StringBuilder sourceReport=new StringBuilder();
            for(String source : sources) {
                HttpURLConnection connection=null;
                BufferedReader reader=null;
                int added=0;
                try {
                    connection=(HttpURLConnection)new URL(source).openConnection();
                    connection.setConnectTimeout(9000);
                    connection.setReadTimeout(12000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent","RahYabVPN/2.0");
                    connection.setRequestProperty("Accept","text/plain,*/*");
                    int response=connection.getResponseCode();
                    if(response!=HttpURLConnection.HTTP_OK) throw new IOException("HTTP "+response);
                    reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),"UTF-8"));
                    String line;
                    boolean header=false;
                    int totalBytes=0;
                    while((line=reader.readLine())!=null) {
                        totalBytes+=line.length()+1;
                        if(totalBytes>8*1024*1024) throw new IOException("حجم فهرست بیش از حد مجاز است.");
                        if(line.length()>256*1024) continue;
                        if(line.startsWith("#HostName")) { header=true; continue; }
                        if(!header||line.startsWith("#")||line.trim().isEmpty()) continue;
                        List<String> fields=parseCsv(line);
                        if(fields.size()<15) continue;
                        try {
                            byte[] raw=Base64.decode(fields.get(14).trim(),Base64.DEFAULT);
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
                            server.source=source.contains("vpngate.net")?"VPN Gate رسمی":"آینه داده VPN Gate";
                            String key=server.ip+":"+remotePort(config);
                            if(seen.add(key)) { candidates.add(server); added++; }
                        } catch(IllegalArgumentException ignored) {}
                    }
                    sourceReport.append("\n").append(serverSourceName(source)).append(": ").append(added).append(" سرور تازه");
                } catch(Exception e) {
                    sourceReport.append("\n").append(serverSourceName(source)).append(": در دسترس نبود");
                } finally {
                    if(reader!=null) try { reader.close(); } catch(IOException ignored) {}
                    if(connection!=null) connection.disconnect();
                }
            }
            // First narrow the list using provider-reported bandwidth, then verify TCP reachability.
            Collections.sort(candidates,(a,b)->Long.compare(b.speed,a.speed));
            int probeCount=Math.min(40,candidates.size());
            for(int i=0;i<probeCount;i++) probeTcp(candidates.get(i));
            Collections.sort(candidates,(a,b)->{
                if(a.tcpReachable!=b.tcpReachable) return a.tcpReachable?-1:1;
                if(a.measuredPing!=b.measuredPing) {
                    if(a.measuredPing<0) return 1;
                    if(b.measuredPing<0) return -1;
                    return Integer.compare(a.measuredPing,b.measuredPing);
                }
                return Long.compare(b.speed,a.speed);
            });
            ArrayList<Server> best=new ArrayList<>();
            for(Server candidate:candidates) {
                if(best.size()>=20) break;
                best.add(candidate);
            }
            final String sourcesSummary=sourceReport.toString();
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()) return;
                refresh.setEnabled(true);
                servers.clear();
                servers.addAll(best);
                if(best.isEmpty()) {
                    status.setText("هیچ سرور معتبری از منابع دریافت نشد. اتصال اینترنت را بررسی و دوباره تلاش کنید."+sourcesSummary);
                } else {
                    long reachable=best.stream().filter(x->x.tcpReachable).count();
                    status.setText("۲۰ سرور برتر (یا کمتر) از میان "+candidates.size()
                            +" رکورد بررسی شدند؛ "+reachable+" مورد پاسخ‌گوی آزمایش TCP بودند."
                            +"\nسرعت اعلامی، معیار اولیه است؛ پینگ TCP معیار دسترسی فعلی است."
                            +sourcesSummary);
                    render();
                }
            });
        });
    }
    private String serverSourceName(String source) {
        return source.contains("vpngate.net")?"VPN Gate رسمی":"آینه VPN Gate";
    }
    private int remotePort(String config) {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^\\s*remote\\s+\\S+\\s+(\\d+)").matcher(config);
        return m.find()?parseInt(m.group(1)):-1;
    }
    private void probeTcp(Server server) {
        int port=remotePort(server.config);
        if(port<1||port>65535) return;
        long started=System.nanoTime();
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(server.ip,port),1200);
            server.measuredPing=(int)Math.max(1,(System.nanoTime()-started)/1000000L);
            server.tcpReachable=true;
        } catch(Exception ignored) {
            server.tcpReachable=false;
        }
    }
    static int parseInt(String s){try{return Integer.parseInt(s.trim());}catch(Exception e){return 9999;}}
    static long parseLong(String s){try{return Long.parseLong(s.trim());}catch(Exception e){return 0;}}
    List<String> parseCsv(String line){ ArrayList<String> out=new ArrayList<>(); StringBuilder x=new StringBuilder(); boolean q=false; for(int i=0;i<line.length();i++){char ch=line.charAt(i); if(ch=='"'){ if(q&&i+1<line.length()&&line.charAt(i+1)=='"'){x.append('"');i++;}else q=!q;} else if(ch==','&&!q){out.add(x.toString());x.setLength(0);}else x.append(ch);} out.add(x.toString()); return out; }
    void render(){
        list.removeAllViews();
        int n=Math.min(servers.size(),20);
        for(int i=0;i<n;i++){
            final Server s=servers.get(i);
            boolean selected=s==selectedServer;
            LinearLayout card=new LinearLayout(this);
            card.setOrientation(1);
            card.setPadding(12,10,12,10);
            GradientDrawable bg=shape(selected?Color.rgb(225,244,239):Color.WHITE);
            bg.setStroke(selected?4:1,selected?teal:Color.rgb(220,226,232));
            card.setBackground(bg);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);
            cp.setMargins(0,6,0,6);
            card.setLayoutParams(cp);
            card.addView(txt((selected?"✓ انتخاب‌شده  |  ":"")+(s.country==null?"نامشخص":s.country)+"  |  "+s.ip,16,selected?teal:navy,true));
            card.addView(txt("پینگ آزمایشی: "+(s.measuredPing<0?"پاسخ نداد":s.measuredPing+" ms")
                    +"   |   وضعیت مسیر: "+(s.tcpReachable?"در دسترس":"تأیید نشد"),12,Color.DKGRAY,false));
            card.addView(txt("سرعت اعلامی سرور: "+String.format(Locale.US,"%.1f",(s.speed*8.0/1000000.0))+" Mbps",12,Color.DKGRAY,false));
            TextView rate=txt("دانلود/آپلود زنده: پس از اتصال نمایش داده می‌شود",12,teal,true);
            card.addView(rate);
            if(selected) liveSpeedView=rate;
            card.addView(txt(selected?"این سرور انتخاب شده است":"برای انتخاب این سرور لمس کنید",12,selected?teal:Color.GRAY,true));
            card.setOnClickListener(v->{
                selectedServer=s;
                render();
                status.setText("سرور "+s.ip+" انتخاب شد؛ در حال بررسی پروفایل…");
                importProfile(s);
            });
            list.addView(card);
        }
    }
    private void updateLiveSpeed() {
        if(liveSpeedView==null||selectedServer==null) return;
        String state=getSharedPreferences("rah_yab_diagnostics",MODE_PRIVATE)
                .getString("connection_lamp_state","disconnected");
        if(!"connected".equals(state)) {
            liveSpeedView.setText("دانلود/آپلود زنده: پس از برقراری اتصال نمایش داده می‌شود");
            lastRxBytes=-1; lastTxBytes=-1;
            return;
        }
        long rx=TrafficStats.getTotalRxBytes(), tx=TrafficStats.getTotalTxBytes();
        if(rx==TrafficStats.UNSUPPORTED||tx==TrafficStats.UNSUPPORTED) {
            liveSpeedView.setText("اندازه‌گیری سرعت در این گوشی پشتیبانی نمی‌شود");
            return;
        }
        if(lastRxBytes>=0&&lastTxBytes>=0) {
            long down=Math.max(0,rx-lastRxBytes), up=Math.max(0,tx-lastTxBytes);
            liveSpeedView.setText("سرعت کل گوشی: دانلود "+formatRate(down*8)+" | آپلود "+formatRate(up*8));
        } else {
            liveSpeedView.setText("در حال اندازه‌گیری سرعت دانلود و آپلود…");
        }
        lastRxBytes=rx; lastTxBytes=tx;
    }
    private String formatRate(long bitsPerSecond) {
        if(bitsPerSecond>=1000000) return String.format(Locale.US,"%.2f Mbps",bitsPerSecond/1000000.0);
        return String.format(Locale.US,"%.0f Kbps",bitsPerSecond/1000.0);
    }
    void importProfile(Server s){
        status.setText("در حال بررسی فایل سرور با موتور داخلی OpenVPN 3…");
        worker.execute(()->{
            final String report=OpenVpn3Bridge.validateProfile(s.config);
            runOnUiThread(()->{
                status.setText("بررسی پروفایل سرور "+s.ip+" انجام شد.");
                new AlertDialog.Builder(this)
                    .setTitle("گزارش بررسی پروفایل")
                    .setMessage(report+"\n\nتوجه: پذیرفته‌شدن فایل توسط تجزیه‌گر به معنی پشتیبانی همه گزینه‌ها یا اتصال VPN نیست.")
                    .setPositiveButton("اتصال آزمایشی داخل راه‌یاب", (dialog,which)->requestInternalVpn(s))
                    .setNeutralButton("انتقال پروفایل به OpenVPN", (dialog,which)->exportProfile(s))
                    .setNegativeButton("بستن",null)
                    .show();
            });
        });
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
    @Override protected void onDestroy(){lampHandler.removeCallbacks(lampPoll);speedHandler.removeCallbacks(speedPoll);super.onDestroy();worker.shutdownNow();}
}
