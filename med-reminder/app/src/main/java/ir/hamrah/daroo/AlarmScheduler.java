package ir.hamrah.daroo;
import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.util.Calendar;

public class AlarmScheduler{
    static final String EXTRA_ID="id",EXTRA_NAME="name",EXTRA_ONE="oneshot";
    private static PendingIntent pi(Context c,int id,String name,boolean one){
        Intent i=new Intent(c,AlarmReceiver.class).setAction(one?"ONE":"DAILY")
            .putExtra(EXTRA_ID,id).putExtra(EXTRA_NAME,name).putExtra(EXTRA_ONE,one);
        return PendingIntent.getBroadcast(c,id,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static void cancel(Context c,int id){
        ((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(pi(c,id,"",false));
    }
    static void replace(Context c,String old,String newest){
        try{JSONArray a=new JSONArray(old);for(int j=0;j<a.length();j++)cancel(c,(int)a.getJSONObject(j).optLong("id"));}catch(Exception ignored){}
        scheduleAll(c);
    }
    static void scheduleAll(Context c){
        try{
            JSONArray a=new JSONArray(c.getSharedPreferences("data",Context.MODE_PRIVATE).getString("alarms","[]"));
            for(int j=0;j<a.length();j++){
                JSONObject o=a.getJSONObject(j);
                int id=(int)o.optLong("id");
                cancel(c,id);
                if(o.optBoolean("enabled"))scheduleDaily(c,id,o.optString("time"),o.optString("name","یادآوری"));
            }
        }catch(Exception ignored){}
    }
    static void nextDay(Context c,int id){
        try{
            JSONArray a=new JSONArray(c.getSharedPreferences("data",Context.MODE_PRIVATE).getString("alarms","[]"));
            for(int j=0;j<a.length();j++){
                JSONObject o=a.getJSONObject(j);
                if(id==(int)o.optLong("id")&&o.optBoolean("enabled")){
                    scheduleDaily(c,id,o.optString("time"),o.optString("name","یادآوری"));
                    return;
                }
            }
        }catch(Exception ignored){}
    }
    private static void scheduleDaily(Context c,int id,String time,String name){
        if(!time.matches("(?:[01]\\d|2[0-3]):[0-5]\\d"))return;
        Calendar cal=Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY,Integer.parseInt(time.substring(0,2)));
        cal.set(Calendar.MINUTE,Integer.parseInt(time.substring(3)));
        cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0);
        if(cal.getTimeInMillis()<=System.currentTimeMillis()+1000)cal.add(Calendar.DAY_OF_YEAR,1);
        schedule(c,id,name,cal.getTimeInMillis(),false);
    }
    static void snooze(Context c,int minutes){
        int id=900000+(int)(System.currentTimeMillis()%500000);
        schedule(c,id,"یادآوری دوباره",System.currentTimeMillis()+Math.max(1,minutes)*60000L,true);
    }
    private static void schedule(Context c,int id,String name,long time,boolean one){
        AlarmManager m=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent p=pi(c,id,name,one);
        try{
            if(Build.VERSION.SDK_INT<31||m.canScheduleExactAlarms()){
                PendingIntent display=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                m.setAlarmClock(new AlarmManager.AlarmClockInfo(time,display),p);
            }else m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,p);
        }catch(SecurityException ex){m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,p);}
    }
}
