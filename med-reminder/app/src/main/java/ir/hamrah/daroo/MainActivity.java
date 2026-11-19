package ir.hamrah.daroo;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.webkit.*;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView browser;
    private static final int PICK_RING=24, SAVE_FILE=25, PICK_FILE=26;
    private String pendingFileContent;
    private ValueCallback<Uri[]> fileCallback;
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(0xff0b5957);
        browser=new WebView(this);
        WebSettings s=browser.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true); s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        browser.setWebViewClient(new WebViewClient());
        browser.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p){
                if(fileCallback!=null)fileCallback.onReceiveValue(null);
                fileCallback=cb;
                try { startActivityForResult(p.createIntent(),PICK_FILE);return true; }
                catch(Exception e){fileCallback=null;cb.onReceiveValue(null);return false;}
            }
        });
        browser.addJavascriptInterface(new Bridge(),"MedBridge");
        setContentView(browser);
        browser.loadUrl("file:///android_asset/index.html");
        RingService.createChannel(this);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},100);
    }
    @Override protected void onResume(){super.onResume();AlarmScheduler.scheduleAll(this);}
    private void show(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_LONG).show());}
    private class Bridge{
        @JavascriptInterface public void syncReminders(String json,boolean vib){
            String old=getSharedPreferences("data",MODE_PRIVATE).getString("alarms","[]");
            getSharedPreferences("data",MODE_PRIVATE).edit().putString("alarms",json).putBoolean("vibrate",vib).apply();
            AlarmScheduler.replace(MainActivity.this,old,json);
        }
        @JavascriptInterface public void openSettings(){runOnUiThread(()->{
            AlarmManager m=(AlarmManager)getSystemService(ALARM_SERVICE);
            if(Build.VERSION.SDK_INT>=31&&!m.canScheduleExactAlarms()){
                try {startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}
                catch(Exception ex){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
            } else if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},100);
            } else startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
        });}
        @JavascriptInterface public void pickRingtone(){runOnUiThread(()->{
            Intent i=new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,RingtoneManager.TYPE_ALARM);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,false);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,true);
            startActivityForResult(i,PICK_RING);
        });}
        @JavascriptInterface public void testAlarm(){runOnUiThread(()->RingService.launch(MainActivity.this,"آزمایش صدای زنگ"));}
        @JavascriptInterface public void shareText(String t){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,t);
            startActivity(Intent.createChooser(i,"ارسال گزارش"));
        });}
        @JavascriptInterface public void copyText(String t){runOnUiThread(()->{
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
              .setPrimaryClip(ClipData.newPlainText("گزارش",t));
        });}
        @JavascriptInterface public void snooze(int minutes){AlarmScheduler.snooze(MainActivity.this,minutes);}
        @JavascriptInterface public boolean isRinging(){return getSharedPreferences("data",MODE_PRIVATE).getBoolean("ringing",false);}
        @JavascriptInterface public void stopRing(){runOnUiThread(()->startService(new Intent(MainActivity.this,RingService.class).setAction("STOP")));}
        @JavascriptInterface public void saveFile(String n,String data,String mime){runOnUiThread(()->{
            pendingFileContent=data;
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mime);i.putExtra(Intent.EXTRA_TITLE,n);
            startActivityForResult(i,SAVE_FILE);
        });}
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==PICK_FILE){
            if(fileCallback!=null){
                fileCallback.onReceiveValue(result==RESULT_OK?WebChromeClient.FileChooserParams.parseResult(result,data):null);
                fileCallback=null;
            }
        }
        if(req==PICK_RING&&result==RESULT_OK&&data!=null){
            Uri uri=data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if(uri!=null){
                getSharedPreferences("data",MODE_PRIVATE).edit().putString("ringtone",uri.toString()).apply();
                show("صدای زنگ ذخیره شد.");
            }
        }
        if(req==SAVE_FILE&&result==RESULT_OK&&data!=null&&data.getData()!=null&&pendingFileContent!=null){
            try(OutputStream o=getContentResolver().openOutputStream(data.getData())){
                if(o!=null)o.write(pendingFileContent.getBytes(StandardCharsets.UTF_8));
                show("فایل ذخیره شد.");
            }catch(Exception ex){show("ذخیره فایل ناموفق بود.");}
            pendingFileContent=null;
        }
    }
    @Override protected void onDestroy(){if(browser!=null){browser.removeJavascriptInterface("MedBridge");browser.destroy();}super.onDestroy();}
}
