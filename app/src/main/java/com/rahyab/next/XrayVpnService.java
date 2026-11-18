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
    private static final long HEALTH_INTERVAL_MS = 40_000L;
    private static final long HEALTH_RECHECK_MS = 12_000L;
    private static final long RECOVERY_PAUSE_MS = 90_000L;
    private static final int MAX_RECOVERY_ROUNDS = 2;
    private static final int RECOVERY_CANDIDATES = 3;
    private String lastState = "starting";
    private String activeConfig;
    private Network underlyingNetwork;
    private int failedChecks;
    private int recoveryRounds;
    private final java.util.Set<String> failedRelays = new java.util.HashSet<>();
    private final Runnable healthCheck = this::checkMediaHealth;
    private Notification.Builder connectionNotification;
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
            connectionNotification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentTitle("فیلتر محسن • Xray آزمایشی")
                    .setContentText("اتصال آزمایشی")
                    .setOngoing(true).addAction(new Notification.Action.Builder(
                            null, "قطع", stop).build());
            startForeground(7401, connectionNotification.build());
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
        underlyingNetwork = underlying;
        activeConfig = config;
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
                                if (measureQuality) scheduleHealthCheck(HEALTH_INTERVAL_MS);
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

    private void scheduleHealthCheck(long delay) {
        main.removeCallbacks(healthCheck);
        if (!stopping) main.postDelayed(healthCheck, delay);
    }

    private void checkMediaHealth() {
        if (stopping) return;
        verifier.execute(() -> {
            int media = TunnelVerifier.probeXrayMedia(this);
            main.post(() -> {
                if (stopping) return;
                if (media != 0) {
                    failedChecks = 0;
                    if ("health_retry".equals(lastState) || "health_degraded".equals(lastState)) {
                        publishConnectionState("verified", "دسترسی دوباره برقرار شد");
                    }
                    scheduleHealthCheck(HEALTH_INTERVAL_MS);
                } else if (++failedChecks == 1) {
                    if (!"health_degraded".equals(lastState)) {
                        publishConnectionState("health_retry", "در حال بررسی دوبارهٔ دسترسی به یوتیوب و اینستاگرام");
                    }
                    scheduleHealthCheck(HEALTH_RECHECK_MS);
                } else if (recoveryRounds < MAX_RECOVERY_ROUNDS) {
                    recoverRelay();
                } else {
                    markDegraded();
                }
            });
        });
    }

    private void recoverRelay() {
        recoveryRounds++;
        publishConnectionState("recovering", "دسترسی قطع شده؛ در حال آزمون سرور جایگزین Xray");
        String previous = activeConfig;
        int fd = tun.getFd();
        verifier.execute(() -> {
            boolean switched = false;
            try {
                failedRelays.add(XrayProfile.relayKey(previous));
                PublicProxyProvider.Result feed = PublicProxyProvider.fetch(
                        url -> underlyingNetwork.openConnection(url, java.net.Proxy.NO_PROXY),
                        () -> stopping);
                for (String candidate : XrayProfile.fromPublicFeed(feed.profile, failedRelays, RECOVERY_CANDIDATES)) {
                    if (stopping) return;
                    String relayKey = XrayProfile.relayKey(candidate);
                    failedRelays.add(relayKey);
                    switched = true;
                    if (restartCore(candidate, fd) && !stopping
                            && TunnelVerifier.verify(this).verified && !stopping
                            && TunnelVerifier.probeXrayMedia(this) == 3) {
                        failedRelays.remove(relayKey);
                        main.post(() -> {
                            if (stopping) return;
                            activeConfig = candidate;
                            failedChecks = 0;
                            downloadKbps = -1;
                            publishConnectionState("recovered", "سرور جایگزین Xray وصل شد");
                            scheduleHealthCheck(HEALTH_INTERVAL_MS);
                        });
                        return;
                    }
                }
            } catch (Exception ignored) {
                // Keep source URLs, server addresses and configuration out of diagnostics.
            }
            if (stopping) return;
            boolean restored = !switched || restartCore(previous, fd);
            main.post(() -> {
                if (stopping) return;
                if (restored) markDegraded();
                else shutdown("recovery_failed");
            });
        });
    }

    private boolean restartCore(String config, int fd) {
        try {
            return coreExecutor.submit(() -> {
                if (stopping) return false;
                LibXray.stopAndroidTun();
                if (stopping) return false;
                // Native Xray duplicates the Android descriptor, so the VPN stays established.
                LibXray.startAndroidTun(config, fd);
                return true;
            }).get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.RejectedExecutionException error) {
            return false;
        }
    }

    private void markDegraded() {
        failedChecks = 0;
        publishConnectionState("health_degraded", "دسترسی تأیید نشد؛ سرور جایگزین مناسب پیدا نشد");
        scheduleHealthCheck(RECOVERY_PAUSE_MS);
    }

    private void publishConnectionState(String state, String message) {
        event(state);
        getSystemService(NotificationManager.class).notify(7401,
                connectionNotification.setContentText(message).build());
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
        main.removeCallbacks(healthCheck);
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
