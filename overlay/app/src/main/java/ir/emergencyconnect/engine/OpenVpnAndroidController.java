package ir.emergencyconnect.engine;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import de.blinkt.openvpn.api.APIVpnProfile;
import de.blinkt.openvpn.api.IOpenVPNAPIService;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;

/**
 * Controls the separately installed OpenVPN for Android app through its
 * documented Apache-licensed remote API. No OpenVPN engine code is embedded.
 */
public final class OpenVpnAndroidController {
    public static final int REQUEST_API_PERMISSION = 8610;
    public static final int REQUEST_VPN_PERMISSION = 8611;

    public interface Listener {
        void onStatus(String state, String message, String level);
        void onError(String message);
        void onInstallRequired();
    }

    private final Activity activity;
    private final Listener listener;
    private IOpenVPNAPIService service;
    private boolean bound;
    private String pendingConfig;
    private String pendingName;

    public OpenVpnAndroidController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void connect(String config, String profileName) {
        if (config == null || config.trim().isEmpty()) {
            listener.onError("فایل تنظیمات سرور خالی است.");
            return;
        }
        pendingConfig = config;
        pendingName = profileName == null ? "RahYab VPN" : profileName;
        if (bound) {
            try { activity.unbindService(connection); } catch (IllegalArgumentException ignored) {}
            bound = false;
            service = null;
        }
        Intent intent = new Intent("de.blinkt.openvpn.api.IOpenVPNAPIService");
        intent.setPackage("de.blinkt.openvpn");
        try {
            bound = activity.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                listener.onInstallRequired();
            } else {
                listener.onStatus("WAIT", "در حال برقراری ارتباط امن با برنامه OpenVPN for Android…", "INFO");
            }
        } catch (RuntimeException error) {
            listener.onError("ارتباط با برنامه OpenVPN برقرار نشد: " + error.getMessage());
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IOpenVPNAPIService.Stub.asInterface(binder);
            try {
                Intent permission = service.prepare(activity.getPackageName());
                if (permission != null) {
                    activity.startActivityForResult(permission, REQUEST_API_PERMISSION);
                } else {
                    requestVpnPermission();
                }
            } catch (Exception error) {
                listener.onError("مجوز کنترل برنامه OpenVPN دریافت نشد: " + error.getMessage());
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            listener.onStatus("DISCONNECTED", "ارتباط با موتور مستقل OpenVPN قطع شد.", "WARN");
        }
    };

    private void requestVpnPermission() throws RemoteException {
        if (service == null) {
            listener.onError("ارتباط با سرویس OpenVPN برقرار نیست.");
            return;
        }
        Intent permission = service.prepareVPNService();
        if (permission != null) {
            activity.startActivityForResult(permission, REQUEST_VPN_PERMISSION);
        } else {
            startProfile();
        }
    }

    private void startProfile() {
        if (service == null || pendingConfig == null) {
            listener.onError("اطلاعات پروفایل یا ارتباط با OpenVPN موجود نیست.");
            return;
        }
        try {
            service.registerStatusCallback(statusCallback);
            APIVpnProfile profile = service.addNewVPNProfile(pendingName, true, pendingConfig);
            if (profile == null || profile.mUUID == null || profile.mUUID.isEmpty()) {
                listener.onError("برنامه OpenVPN نتوانست پروفایل سرور را ثبت کند.");
                return;
            }
            service.startProfile(profile.mUUID);
            listener.onStatus("CONNECTING", "پروفایل به موتور مستقل OpenVPN تحویل شد؛ منتظر نتیجه واقعی اتصال هستیم.", "INFO");
            pendingConfig = null;
        } catch (Exception error) {
            listener.onError("شروع اتصال با موتور مستقل ناموفق بود: " + error.getMessage());
        }
    }

    private final IOpenVPNStatusCallback statusCallback = new IOpenVPNStatusCallback.Stub() {
        @Override public void newStatus(String uuid, String state, String message, String level) {
            listener.onStatus(state, message, level);
        }
    };

    /** Returns true when the result belongs to this controller. */
    public boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode == REQUEST_API_PERMISSION) {
            if (resultCode == Activity.RESULT_OK) {
                try { requestVpnPermission(); }
                catch (RemoteException error) { listener.onError("بررسی مجوز VPN ناموفق بود: " + error.getMessage()); }
            } else {
                listener.onError("اجازه کنترل OpenVPN داده نشد.");
            }
            return true;
        }
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == Activity.RESULT_OK) {
                startProfile();
            } else {
                listener.onError("مجوز سیستم برای ایجاد VPN داده نشد.");
            }
            return true;
        }
        return false;
    }

    /** Disconnects the external engine if its service is currently available. */
    public void disconnect() {
        try {
            if (service != null) service.disconnect();
        } catch (RemoteException error) {
            listener.onError("قطع اتصال از موتور OpenVPN ناموفق بود: " + error.getMessage());
        }
    }

    /** Unbind only; the VPN session is intentionally not stopped with the UI. */
    public void close() {
        if (service != null) {
            try { service.unregisterStatusCallback(statusCallback); } catch (RemoteException ignored) {}
        }
        if (bound) {
            try { activity.unbindService(connection); } catch (IllegalArgumentException ignored) {}
        }
        bound = false;
        service = null;
    }
}
