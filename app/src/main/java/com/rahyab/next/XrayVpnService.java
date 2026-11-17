package com.rahyab.next;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import libXray.LibXray;

public final class XrayVpnService extends VpnService {
    static final String EVENT = "com.mohsen.filter.XRAY_EVENT";
    static final String STOP = "com.mohsen.filter.XRAY_STOP";
    static final String STATUS = "com.mohsen.filter.XRAY_STATUS";
    private String lastState = "starting";
    private static final String CHANNEL = "xray_vpn";
    private final ExecutorService coreExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService verifier = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    // Xray appends process-global socket callbacks. Keep a single callback
    // and let it refer to the current service instance after each restart.
    private static volatile XrayVpnService socketOwner;
    private static boolean dialerRegistered;
    private ParcelFileDescriptor tun;
    private boolean starting;
    private volatile boolean stopping;
    private volatile boolean protectionFailed;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            shutdown("stopped");
            return START_NOT_STICKY;
        }
        if (STATUS.equals(intent.getAction())) {
            if (starting || stopping) event(lastState);
            else shutdown("stopped");
            return START_NOT_STICKY;
        }
        if (starting || stopping) return START_NOT_STICKY;
        String config = intent.getStringExtra("config");
        if (config == null || config.length() > 16_384) {
            shutdown("invalid");
            return START_NOT_STICKY;
        }
        starting = true;
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Xray", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 7401,
                new Intent(this, XrayVpnService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            startForeground(7401, new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentTitle("فیلتر محسن • Xray آزمایشی")
                    .setContentText("اتصال آزمایشی")
                    .setOngoing(true).addAction(new Notification.Action.Builder(
                            null, "قطع", stop).build()).build());
            openTunnel(config);
        } catch (Exception error) {
            shutdown("tun_failed");
        }
        return START_NOT_STICKY;
    }

    private void openTunnel(String config) throws IOException {
        if (prepare(this) != null) throw new IOException();
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        Network underlying = manager.getActiveNetwork();
        if (underlying == null || manager.getNetworkCapabilities(underlying) == null ||
                manager.getNetworkCapabilities(underlying).hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_VPN)) throw new IOException();
        LinkProperties link = manager.getLinkProperties(underlying);
        if (link == null || link.getDnsServers().isEmpty()) throw new IOException();
        InetAddress resolver = link.getDnsServers().get(0);
        String dns = resolver.getHostAddress();
        final String endpoint = dns.contains(":") ? "[" + dns + "]:53" : dns + ":53";

        tun = new Builder().setSession("فیلتر محسن • Xray").setMtu(1400)
                .addAddress("172.28.91.1", 30).addRoute("0.0.0.0", 0)
                .addAddress("fd00:6d6f:6873::1", 126).addRoute("::", 0)
                .addDnsServer("1.1.1.1").setUnderlyingNetworks(new Network[]{underlying})
                .establish();
        if (tun == null) throw new IOException();
        int fd = tun.getFd();
        coreExecutor.execute(() -> {
            if (stopping) return;
            try {
                registerSocketProtection();
                LibXray.setDNS(this::protectTransport, endpoint);
                LibXray.startAndroidTun(config, fd);
                main.post(() -> {
                    if (stopping || protectionFailed) return;
                    event("engine_started");
                    verifier.execute(() -> {
                        TunnelVerifier.Result result = TunnelVerifier.verify(this);
                        main.post(() -> {
                            if (stopping || protectionFailed) return;
                            if (result.verified) event("verified");
                            else shutdown("https_failed");
                        });
                    });
                });
            } catch (Exception | LinkageError error) {
                main.post(() -> shutdown("core_failed"));
            }
        });
    }

    private void registerSocketProtection() {
        synchronized (XrayVpnService.class) {
            socketOwner = this;
            if (dialerRegistered) return;
            LibXray.registerDialerController(fd -> {
                XrayVpnService owner = socketOwner;
                return owner != null && owner.protectTransport(fd);
            });
            dialerRegistered = true;
        }
    }

    private boolean protectTransport(long fd) {
        boolean protectedSocket = !stopping && fd >= 0 && fd <= Integer.MAX_VALUE && protect((int) fd);
        if (!protectedSocket && !stopping) {
            protectionFailed = true;
            main.post(() -> shutdown("protect_failed"));
        }
        return protectedSocket;
    }

    private void shutdown(String reason) {
        if (stopping) return;
        stopping = true;
        verifier.shutdownNow();
        coreExecutor.execute(() -> {
            try {
                try { LibXray.stopAndroidTun(); }
                finally { LibXray.resetDNS(); }
            } catch (Exception | LinkageError ignored) {
                // Release the Android interface even if the native core failed.
            } finally {
                synchronized (XrayVpnService.class) {
                    if (socketOwner == this) socketOwner = null;
                }
                try {
                    if (tun != null) tun.close();
                } catch (IOException ignored) {
                    // Already closed by Android.
                }
                main.post(() -> {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    event(reason);
                    stopSelf();
                });
            }
        });
        coreExecutor.shutdown();
    }

    private void event(String state) {
        lastState = state;
        sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("state", state));
    }

    @Override public void onRevoke() {
        main.post(() -> shutdown("stopped"));
    }

    @Override public void onDestroy() {
        shutdown("stopped");
        super.onDestroy();
    }
}
