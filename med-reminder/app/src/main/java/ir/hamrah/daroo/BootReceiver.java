package ir.hamrah.daroo;
import android.content.*;
public class BootReceiver extends BroadcastReceiver{
    @Override public void onReceive(Context c,Intent i){AlarmScheduler.scheduleAll(c);}
}
