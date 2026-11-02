package ir.emergencyconnect;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
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
        TextView note=txt("روش اتصال: این برنامه سرورهای VPN Gate را دریافت می‌کند و فایل تنظیمات را برای OpenVPN for Android می‌فرستد. برای ایجاد تونل واقعی باید برنامه OpenVPN for Android جداگانه نصب باشد؛ پس از واردکردن پروفایل، اتصال را در آن برنامه تأیید کنید.",13,Color.DKGRAY,false); note.setBackground(shape(Color.WHITE)); note.setPadding(14,14,14,14); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2); np.setMargins(0,12,0,8); root.addView(note);
        TextView warning=txt("شفاف‌سازی: موتور اتصال OpenVPN داخل این APK تعبیه نشده است؛ اتصال از طریق برنامه OpenVPN for Android انجام می‌شود. سرورهای عمومی داوطلبانه ممکن است قطع، کند یا دارای سیاست ثبت اطلاعات متفاوت باشند. برای بانکداری و اطلاعات حساس استفاده نکنید.",12,Color.rgb(150,70,30),true); root.addView(warning);
        list=new LinearLayout(this); list.setOrientation(1); root.addView(list);
        TextView foot=txt("منبع فهرست: VPN Gate دانشگاه تسوکوبا",11,Color.GRAY,false); foot.setGravity(Gravity.CENTER); root.addView(foot);
    }
    void loadServers(){
        refresh.setEnabled(false); status.setText("در حال دریافت فهرست سرورها…"); list.removeAllViews();
        worker.execute(()->{ ArrayList<Server> found=new ArrayList<>(); String error=null;
            try { HttpURLConnection c=(HttpURLConnection)new URL(API).openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setRequestProperty("User-Agent","RahYabVPN/1.0");
                BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8")); String line; boolean header=false;
                while((line=r.readLine())!=null){ if(line.startsWith("#HostName")){header=true; continue;} if(!header||line.startsWith("#")||line.trim().isEmpty()) continue;
                    List<String> f=parseCsv(line); if(f.size()<15) continue;
                    try { String b64=f.get(14).trim(); byte[] raw=Base64.decode(b64,Base64.DEFAULT); String cfg=new String(raw,"UTF-8"); if(!cfg.contains("client")||!cfg.contains("remote ")) continue;
                        Server s=new Server(); s.host=f.get(0); s.ip=f.get(1); s.ping=parseInt(f.get(3)); s.speed=parseLong(f.get(4)); s.country=f.get(5); s.config=cfg; found.add(s);
                    }catch(Exception ignored){}
                } r.close(); c.disconnect();
            }catch(Exception e){error=e.getMessage();}
            final String err=error; runOnUiThread(()->{refresh.setEnabled(true); servers.clear(); servers.addAll(found); if(found.isEmpty()){status.setText("سروری دریافت نشد. اینترنت را بررسی و دوباره تلاش کنید.\n"+(err==null?"":err));} else { Collections.sort(servers,(a,b)->Long.compare(b.speed,a.speed)); status.setText("تعداد "+servers.size()+" سرور دریافت شد. فهرست بر اساس سرعت اعلام‌شده مرتب شده؛ برای واردکردن پروفایل روی سرور بزنید."); render(); }});
        });
    }
    static int parseInt(String s){try{return Integer.parseInt(s.trim());}catch(Exception e){return 9999;}}
    static long parseLong(String s){try{return Long.parseLong(s.trim());}catch(Exception e){return 0;}}
    List<String> parseCsv(String line){ ArrayList<String> out=new ArrayList<>(); StringBuilder x=new StringBuilder(); boolean q=false; for(int i=0;i<line.length();i++){char ch=line.charAt(i); if(ch=='"'){ if(q&&i+1<line.length()&&line.charAt(i+1)=='"'){x.append('"');i++;}else q=!q;} else if(ch==','&&!q){out.add(x.toString());x.setLength(0);}else x.append(ch);} out.add(x.toString()); return out; }
    void render(){ list.removeAllViews(); int n=Math.min(servers.size(),80); for(int i=0;i<n;i++){ final Server s=servers.get(i); LinearLayout card=new LinearLayout(this); card.setOrientation(1); card.setPadding(12,10,12,10); card.setBackground(shape(Color.WHITE)); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.setMargins(0,6,0,6); card.setLayoutParams(cp);
        card.addView(txt((s.country==null?"نامشخص":s.country)+"  |  "+s.ip,16,navy,true)); card.addView(txt("پینگ گزارش‌شده: "+(s.ping>=9999?"نامشخص":s.ping+" ms")+"   |   سرعت اعلامی: "+(s.speed/1000000)+" Mbps",12,Color.DKGRAY,false)); card.addView(txt("برای انتقال تنظیمات به OpenVPN لمس کنید",12,teal,true)); card.setOnClickListener(v->importProfile(s)); list.addView(card);
    }}
    void importProfile(Server s){ try{ File dir=new File(getCacheDir(),"profiles"); dir.mkdirs(); File f=new File(dir,"vpngate-"+s.ip.replace('.','_')+".ovpn"); FileOutputStream o=new FileOutputStream(f); o.write(s.config.getBytes("UTF-8")); o.close(); Uri u=FileProvider.getUriForFile(this,getPackageName()+".provider",f); Intent i=new Intent(Intent.ACTION_VIEW); i.setDataAndType(u,"application/x-openvpn-profile"); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(i,"باز کردن پروفایل با برنامه OpenVPN")); }catch(Exception e){ new AlertDialog.Builder(this).setTitle("برنامه OpenVPN لازم است").setMessage("برای اتصال واقعی، برنامه OpenVPN for Android را از منبع معتبر نصب کنید. سپس دوباره سرور را انتخاب و پروفایل را در آن وارد کنید.").setPositiveButton("متوجه شدم",null).show(); }}
    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}
}
