package ir.hamrah.daroo;
import android.content.*;
public class AlarmReceiver extends BroadcastReceiver{
    @Override public void onReceive(Context c,Intent i){
        int id=i.getIntExtra(AlarmScheduler.EXTRA_ID,0);
        if(!i.getBooleanExtra(AlarmScheduler.EXTRA_ONE,false))AlarmScheduler.nextDay(c,id);
        String name=i.getStringExtra(AlarmScheduler.EXTRA_NAME);
        try{RingService.launch(c,name==null?"یادآوری":name);}
        catch(Exception ex){RingService.fallback(c,name==null?"یادآوری":name);}
    }
}
