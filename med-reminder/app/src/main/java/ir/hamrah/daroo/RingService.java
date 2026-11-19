package ir.hamrah.daroo;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.net.Uri;
import android.os.*;

public class RingService extends Service{
    private static final String CH="hamrah_alarm",BACK="hamrah_backup";
    private static final int ID=1128;
    private MediaPlayer player;
    private Vibrator vibration;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Runnable stopTask;
    static void createChannel(Context c){
        NotificationChannel ch=new NotificationChannel(CH,"زنگ یادآوری",NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null,null);ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        ((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }
    static void launch(Context c,String name){
        Intent i=new Intent(c,RingService.class).setAction("START").putExtra("title",name);
        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);
        else c.startService(i);
    }
    static void fallback(Context c,String name){
        try{
            NotificationChannel ch=new NotificationChannel(BACK,"یادآوری جایگزین",NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                 new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            NotificationManager n=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            n.createNotificationChannel(ch);
            n.notify(ID+1,new Notification.Builder(c,BACK).setSmallIcon(R.drawable.ic_app)
               .setContentTitle("یادآوری").setContentText(name).setCategory(Notification.CATEGORY_ALARM)
               .setAutoCancel(true).build());
        }catch(Exception ignored){}
    }
    private PendingIntent action(String type,int req){
        return PendingIntent.getService(this,req,new Intent(this,RingService.class).setAction(type),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification notification(String title){
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CH).setSmallIcon(R.drawable.ic_app)
                .setContentTitle("زنگ یادآوری").setContentText(title).setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open)
                .addAction(android.R.drawable.ic_media_pause,"خاموش",action("STOP",2))
                .addAction(android.R.drawable.ic_popup_reminder,"۱۰ دقیقه بعد",action("SNOOZE",3)).build();
    }
    @Override public int onStartCommand(Intent i,int flags,int startId){
        if(i==null)return START_NOT_STICKY;
        if("STOP".equals(i.getAction())){stop();stopSelf();return START_NOT_STICKY;}
        if("SNOOZE".equals(i.getAction())){AlarmScheduler.snooze(this,10);stop();stopSelf();return START_NOT_STICKY;}
        stop();createChannel(this);
        String title=i.getStringExtra("title");
        if(title==null)title="یادآوری";
        try{
            if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification(title),ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            else startForeground(ID,notification(title));
        }catch(Exception ex){fallback(this,title);stopSelf();return START_NOT_STICKY;}
        getSharedPreferences("data",MODE_PRIVATE).edit().putBoolean("ringing",true).apply();
        play();
        stopTask=()->{stop();stopSelf();};handler.postDelayed(stopTask,90000);
        return START_NOT_STICKY;
    }
    private void play(){
        try{
            String raw=getSharedPreferences("data",MODE_PRIVATE).getString("ringtone",null);
            Uri uri=raw==null?RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM):Uri.parse(raw);
            if(uri==null)uri=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            player=new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this,uri);player.setLooping(true);player.prepare();player.start();
        }catch(Exception ignored){if(player!=null){player.release();player=null;}}
        if(getSharedPreferences("data",MODE_PRIVATE).getBoolean("vibrate",true)){
            vibration=(Vibrator)getSystemService(VIBRATOR_SERVICE);
            if(vibration!=null&&vibration.hasVibrator()){
                vibration.vibrate(VibrationEffect.createWaveform(new long[]{0,500,200,500,400},0));
            }
        }
    }
    private void stop(){
        getSharedPreferences("data",MODE_PRIVATE).edit().putBoolean("ringing",false).apply();
        if(stopTask!=null){handler.removeCallbacks(stopTask);stopTask=null;}
        if(player!=null){try{if(player.isPlaying())player.stop();}catch(Exception ignored){}
            try{player.release();}catch(Exception ignored){}player=null;}
        if(vibration!=null){vibration.cancel();vibration=null;}
    }
    @Override public void onDestroy(){stop();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
