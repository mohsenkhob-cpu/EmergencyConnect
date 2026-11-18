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
    private long downloadKbps = -1;
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
            shutdown(intent != null && intent.getBooleanExtra("quality_retry", false)
                    ? "quality_retry" : "stopped");
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
            openTunnel(config, intent.getBooleanExtra("measure_quality", false));
        } catch (Exception error) {
            shutdown("tun_failed");
        }
        return START_NOT_STICKY;
    }

    private void openTunnel(String config, boolean measureQuality) throws IOException {
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
            String stage = "core_binding_failed";
            try {
                registerSocketProtection();
                stage = "core_dns_failed";
                LibXray.setDNS(this::protectTransport, endpoint);
                stage = "core_failed";
                LibXray.startAndroidTun(config, fd);
                main.post(() -> {
                    if (stopping || protectionFailed) return;
                    event("engine_started");
                    verifier.execute(() -> {
                        TunnelVerifier.Result result = TunnelVerifier.verify(this);
                        long speed = measureQuality && result.verified && !stopping
                                ? TunnelVerifier.sampleDownloadKbps(this) : -1L;
                        main.post(() -> {
                            if (stopping || protectionFailed) return;
                            if (result.verified) {
                                downloadKbps = speed;
                                event("verified");
                            } else shutdown("https_failed");
                        });
                        if (result.verified && !stopping) {
                            String youtubeResult = TunnelVerifier.probeYouTube(this);
                            main.post(() -> {
                                if (!stopping && !protectionFailed) broadcastState(youtubeResult);
                            });
                        }
                    });
                });
            } catch (LinkageError error) {
                main.post(() -> shutdown("core_binding_failed"));
            } catch (Exception error) {
                String failure = "core_failed".equals(stage) ? classifyCoreFailure(error) : stage;
                main.post(() -> shutdown(failure));
            }
        });
    }

    private static String classifyCoreFailure(Exception error) {
        // Native errors may contain user settings. Only publish fixed categories.
        String message = error.getMessage();
        if (message == null) return "core_failed";
        if (message.contains("fail to get system interface information"))
            return "core_interface_query_failed";
        if (message.contains("bad file descriptor"))
            return "core_tun_fd_failed";
        if (message.contains("operation not permitted") || message.contains("permission denied"))
            return "core_permission_failed";
        if (message.contains("failed to build") || message.contains("failed to parse") ||
                message.contains("failed to load config"))
            return "core_config_failed";
        return "core_failed";
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
        broadcastState(state);
    }

    private void broadcastState(String state) {
        sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("state", state)
                .putExtra("download_kbps", downloadKbps));
    }

    @Override public void onRevoke() {
        main.post(() -> shutdown("stopped"));
    }

    @Override public void onDestroy() {
        shutdown("stopped");
        super.onDestroy();
    }
}
