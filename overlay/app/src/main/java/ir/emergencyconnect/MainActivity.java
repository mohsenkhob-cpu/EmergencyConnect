package ir.emergencyconnect;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.widget.*;
import androidx.core.content.FileProvider;
import ir.emergencyconnect.engine.OpenVpn3Bridge;
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
    int navy=Color.rgb(16,42,67), teal=Color.rgb(22,125,101);
    static class Server { String country, ip, host, config; long speed; int ping; }

    @Override public void onCreate(Bundle b){ super.onCreate(b); getWindow().setStatusBarColor(navy); getWindow().setNavigationBarColor(navy); build(); loadServers(); }
    GradientDrawable shape(int c){ GradientDrawable d=new GradientDrawable(); d.setColor(c); d.setCornerRadius(22); return d; }
    TextView txt(String s,int size,int color,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.RIGHT); t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL); t.setPadding(8,7,8,7); return t; }
    void build(){
        ScrollView sc=new ScrollView(this); LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(18,18,18,24); root.setBackgroundColor(Color.rgb(244,247,250)); sc.addView(root); setContentView(sc);
        LinearLayout hero=new LinearLayout(this); hero.setOrientation(1); hero.setPadding(20,20,20,20); hero.setBackground(shape(navy));
        hero.addView(txt("راه‌یاب VPN",26,Color.WHITE,true)); hero.addView(txt("یافتن و آماده‌سازی سرورهای VPN Gate",15,Color.LTGRAY,false)); root.addView(hero);
        status=txt("برای دریافت فهرست سرورها دکمه زیر را بزنید.",15,navy,true); status.setPadding(5,18,5,12); root.addView(status);
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

        TextView note=txt("روش فعلی اتصال: این برنامه سرورهای VPN Gate را دریافت می‌کند و فایل تنظیمات را برای OpenVPN for Android می‌فرستد. دکمه آزمایش بالا فقط هسته داخلی را بررسی می‌کند و به معنی امکان اتصال مستقل نیست.",13,Color.DKGRAY,false); note.setBackground(shape(Color.WHITE)); note.setPadding(14,14,14,14); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2); np.setMargins(0,12,0,8); root.addView(note);
        TextView warning=txt("شفاف‌سازی: اتصال مستقل از داخل این APK هنوز فعال نشده است. سرورهای عمومی داوطلبانه ممکن است قطع، کند یا دارای سیاست ثبت اطلاعات متفاوت باشند. برای بانکداری و اطلاعات حساس استفاده نکنید.",12,Color.rgb(150,70,30),true); root.addView(warning);
        list=new LinearLayout(this); list.setOrientation(1); root.addView(list);
        TextView foot=txt("منبع فهرست: VPN Gate دانشگاه تسوکوبا",11,Color.GRAY,false); foot.setGravity(Gravity.CENTER); root.addView(foot);
    }
    void loadServers(){
        refresh.setEnabled(false);
        status.setText("در حال دریافت فهرست سرورها…");
        list.removeAllViews();
        worker.execute(()->{
            ArrayList<Server> found=new ArrayList<>();
            String error=null;
            HttpURLConnection connection=null;
            BufferedReader reader=null;
            try {
                connection=(HttpURLConnection)new URL(API).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent","RahYabVPN/1.0");
                connection.setRequestProperty("Accept","text/plain");
                int response=connection.getResponseCode();
                if(response!=HttpURLConnection.HTTP_OK) {
                    throw new IOException("پاسخ سرور VPN Gate: HTTP "+response);
                }
                reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),"UTF-8"));
                String line;
                boolean header=false;
                int totalBytes=0;
                final int maxBytes=8*1024*1024;
                while((line=reader.readLine())!=null) {
                    totalBytes+=line.length()+1;
                    if(totalBytes>maxBytes) throw new IOException("حجم فهرست سرورها بیش از حد مجاز است.");
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
                        found.add(server);
                        if(found.size()>=500) break;
                    } catch(IllegalArgumentException ignored) {
                        // Skip malformed individual VPN Gate records.
                    }
                }
            } catch(Exception e) {
                error=e.getMessage()==null?"خطای ناشناخته در دریافت سرورها":e.getMessage();
            } finally {
                if(reader!=null) try { reader.close(); } catch(IOException ignored) {}
                if(connection!=null) connection.disconnect();
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
        card.addView(txt((s.country==null?"نامشخص":s.country)+"  |  "+s.ip,16,navy,true)); card.addView(txt("پینگ گزارش‌شده: "+(s.ping>=9999?"نامشخص":s.ping+" ms")+"   |   سرعت اعلامی: "+(s.speed/1000000)+" Mbps",12,Color.DKGRAY,false)); card.addView(txt("برای انتقال تنظیمات به OpenVPN لمس کنید",12,teal,true)); card.setOnClickListener(v->importProfile(s)); list.addView(card);
    }}
    void importProfile(Server s){
        status.setText("در حال بررسی فایل سرور با موتور داخلی OpenVPN 3…");
        worker.execute(()->{
            final String report=OpenVpn3Bridge.validateProfile(s.config);
            runOnUiThread(()->{
                status.setText("بررسی پروفایل سرور "+s.ip+" انجام شد.");
                new AlertDialog.Builder(this)
                    .setTitle("گزارش بررسی پروفایل")
                    .setMessage(report+"\n\nتوجه: پذیرفته‌شدن فایل توسط تجزیه‌گر به معنی پشتیبانی همه گزینه‌ها یا اتصال VPN نیست.")
                    .setPositiveButton("انتقال به OpenVPN", (dialog,which)->exportProfile(s))
                    .setNegativeButton("بستن",null)
                    .show();
            });
        });
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
    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}
}
