package com.rahyab.next;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.VpnService;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import hev.htproxy.TProxyService;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two real, isolated native cores, one Android TUN owner, and one userspace
 * tun2socks bridge. This is NOT a wrapper that starts another VPN app.
 *
 * The app UID is excluded from its own TUN to prevent an engine's discovery
 * sockets from recursively entering the VPN. Verification is always via
 * engine SOCKS5 + authenticated public HTTPS, plus an independent check
 * that the Android TUN and forwarding bridge are running.
 */
public final class NativeCoreVpnService extends VpnService {
    public static final String START = "com.mohsen.filter.nativecore.START";
    public static final String STOP = "com.mohsen.filter.nativecore.STOP";
    public static final String EXTRA_ENGINE = "engine";
    public static final String FLUXCORE = "Fluxcore";
    public static final String AETHER = "Aether";

    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean stopped = true;
    private volatile boolean closing = false;
    private volatile Process engineProcess;
    private volatile ParcelFileDescriptor tun;
    private volatile ParcelFileDescriptor tunCopy;
    private volatile String currentEngine = "";
    private volatile Thread worker;
    private static volatile NativeCoreVpnService activeInstance;
    private final Object teardownLock = new Object();
    private static final int SOCKS_PORT = 18080;
    private static final int HTTP_PORT = 18081;
    private final Object networkLock = new Object();
    private record Uplink(Network network, long changedAt) { }
    private volatile Uplink uplink = new Uplink(null, 0L);
    private ConnectivityManager.NetworkCallback networkCallback;
    private long establishedAt;

    private void watchDefaultNetwork() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return;
        synchronized (networkLock) {
            uplink = new Uplink(manager.getActiveNetwork(), 0L);
        }
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { update(network, null); }
            @Override public void onLost(Network network) { update(null, network); }

            private void update(Network next, Network lost) {
                synchronized (networkLock) {
                    if (networkCallback != this || (lost != null && !lost.equals(uplink.network()))
                            || Objects.equals(uplink.network(), next)) return;
                    uplink = new Uplink(next, SystemClock.elapsedRealtime());
                    networkLock.notifyAll();
                }
            }
        };
        synchronized (networkLock) { networkCallback = callback; }
        try {
            manager.registerDefaultNetworkCallback(callback);
        } catch (RuntimeException ignored) {
            synchronized (networkLock) { networkCallback = null; }
        }
    }

    private void stopWatchingNetwork() {
        ConnectivityManager.NetworkCallback callback;
        synchronized (networkLock) {
            callback = networkCallback;
            networkCallback = null;
            networkLock.notifyAll();
        }
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager != null && callback != null) {
            try { manager.unregisterNetworkCallback(callback); }
            catch (RuntimeException ignored) { }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopAndExit();
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction())) return START_NOT_STICKY;
        String requested = intent.getStringExtra(EXTRA_ENGINE);
        if (!FLUXCORE.equals(requested) && !AETHER.equals(requested)) {
            report("موتور درخواست‌شده معتبر نیست", "failed");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!stopped || closing) {
            report("اتصال قبلی هنوز در حال آزادسازی است؛ پس از اتمام دوباره تلاش کنید", "failed");
            return START_NOT_STICKY;
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("nativecore",
                "فیلتر محسن — موتورهای جدید", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, "nativecore")
                .setContentTitle("فیلتر محسن — " + requested)
                .setContentText("آزمایش اتصال رمزگذاری‌شده")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true).build();
        startForeground(7511, notification);

        stopped = false;
        activeInstance = this;
        currentEngine = requested;
        watchDefaultNetwork();
        int token = generation.incrementAndGet();
        report(requested + " — جست‌وجوی مسیر قابل‌دسترسی…", "searching");
        Thread thread = new Thread(() -> runSession(requested, token),
                "mohsen-" + requested.toLowerCase(java.util.Locale.ROOT));
        worker = thread;
        thread.start();
        return START_NOT_STICKY;
    }

    private boolean current(int token) {
        return !stopped && generation.get() == token
                && !Thread.currentThread().isInterrupted();
    }

    private void report(String message, String lamp) {
        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
            String old = prefs.getString("app_event_history", "");
            String stamp = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            // All messages are locally authored, and native stdout, identity,
            // gateway secrets and connection keys are never persisted.
            String safe = message.replace('\n', ' ').replace('\r', ' ');
            String history = old.isEmpty() ? stamp + " | موتور جدید | " + safe
                    : old + "\n" + stamp + " | موتور جدید | " + safe;
            String[] lines = history.split("\n");
            if (lines.length > 120) {
                StringBuilder tail = new StringBuilder();
                for (int i = lines.length - 120; i < lines.length; i++) {
                    if (tail.length() > 0) tail.append('\n');
                    tail.append(lines[i]);
                }
                history = tail.toString();
            }
            prefs.edit()
                .putString("service_status_message", message)
                .putString("connection_lamp_state", lamp)
                .putLong("service_status_updated_at", System.currentTimeMillis())
                .putString("app_event_history", history).apply();
        } catch (RuntimeException ignored) {
            // Diagnostics must never crash a working tunnel.
        }
    }

    private void runSession(String name, int token) {
        try {
            // A short bounded recovery window for network changes. Never run
            // overlapping native engines or two TUN bridges.
            for (int attempt = 1; attempt <= 3 && current(token); attempt++) {
                synchronized (teardownLock) { teardownNative(); }
                establishedAt = 0L;
                String error = startOne(name, token);
                if (!current(token)) return;
                if (error == null) return; // user-initiated stop
                if (establishedAt > 0L && SystemClock.elapsedRealtime() - establishedAt >= 60_000L) {
                    // A healthy session gets a fresh recovery budget.
                    attempt = 1;
                }
                report(name + " — " + error + "؛ تلاش " + attempt + " از ۳", "searching");
                if (attempt < 3) Thread.sleep(2500L * attempt);
            }
            if (current(token)) report(name + " — اتصال قابل‌استفاده تأیید نشد", "failed");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | LinkageError error) {
            if (current(token)) report(name + " — خطای موتور: "
                    + error.getClass().getSimpleName(), "failed");
        } finally {
            // The STOP command can invalidate this generation while a new
            // session is being scheduled. A stale worker must never close the
            // newer session's TUN/process.
            if (generation.get() == token) {
                synchronized (teardownLock) { teardownNative(); }
                stopped = true;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    /** Returns a safe diagnostic string on error, null only when cancelled. */
    private String startOne(String name, int token) throws InterruptedException {
        boolean flux = FLUXCORE.equals(name);
        String binaryName = flux ? "libfluxcore.so" : "libaether.so";
        File binary = new File(getApplicationInfo().nativeLibraryDir, binaryName);
        if (!binary.isFile() || !binary.canExecute()) return "فایل اجرایی موتور موجود نیست";

        File workDir = new File(getFilesDir(), flux ? "fluxcore" : "aether");
        if (!workDir.isDirectory() && !workDir.mkdirs()) return "پوشه موتور قابل ساخت نیست";

        File certificate = prepareCertificateBundle();
        if (!certificate.isFile()) return "گواهی‌های معتبر TLS آماده نشد";

        String[][] options = flux
                ? new String[][] {{ binary.getAbsolutePath(), "run", "--listen",
                    "127.0.0.1:" + SOCKS_PORT, "--http-listen", "127.0.0.1:" + HTTP_PORT,
                    "--country", "auto" }}
                : new String[][] {
                    { binary.getAbsolutePath(), "--bind", "127.0.0.1:" + SOCKS_PORT,
                        "--config", new File(workDir, "identity.toml").getAbsolutePath(),
                        "--log-level", "info", "--scan", "balanced", "--masque", "--h2" },
                    { binary.getAbsolutePath(), "--bind", "127.0.0.1:" + SOCKS_PORT,
                        "--config", new File(workDir, "identity.toml").getAbsolutePath(),
                        "--log-level", "info", "--scan", "balanced", "--masque", "--h3" }
                };

        boolean ready = false;
        String lastFailure = "";
        for (int mode = 0; mode < options.length && current(token) && !ready; mode++) {
            report(name + (flux ? " — کشف درگاه‌های اینترنت" :
                    mode == 0 ? " — جست‌وجوی مسیر HTTP/2" : " — جست‌وجوی مسیر HTTP/3"),
                    "searching");
            Process child;
            try {
                ProcessBuilder pb = new ProcessBuilder(options[mode]);
                pb.directory(workDir).redirectErrorStream(true);
                pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                pb.environment().put("SSL_CERT_FILE", certificate.getAbsolutePath());
                child = pb.start();
            } catch (IOException error) {
                lastFailure = "اجرای فایل موتور ممکن نشد";
                continue;
            }
            synchronized (teardownLock) {
                if (!current(token)) {
                    child.destroyForcibly();
                    return null;
                }
                engineProcess = child;
            }
            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null && child.isAlive()) {
                        // Drain diagnostics to prevent blocking native stdout.
                        // Never persist logs containing traffic or account data.
                    }
                } catch (IOException ignored) { }
            }, "mohsen-native-output");
            drain.setDaemon(true);
            drain.start();

            final long budget = flux ? 125_000L : mode == 0 ? 90_000L : 220_000L;
            long deadline = SystemClock.elapsedRealtime() + budget;
            long nextProbe = 0L;
            while (current(token) && child.isAlive()
                    && SystemClock.elapsedRealtime() < deadline) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextProbe) {
                    nextProbe = now + 5500L;
                    if (NativeProxyProbe.verify(SOCKS_PORT)) {
                        ready = true;
                        break;
                    }
                }
                Thread.sleep(400L);
            }
            if (!ready) {
                lastFailure = child.isAlive() ? "مسیر HTTPS در مهلت مقرر باز نشد"
                        : "فرایند موتور پایان یافت";
                destroyProcess(child);
            }
        }
        if (!current(token)) return null;
        if (!ready) return lastFailure.isEmpty() ? "مسیر قابل‌استفاده پیدا نشد" : lastFailure;

        report(name + " — عبور HTTPS از موتور تأیید شد؛ ایجاد VPN…", "searching");
        try {
            Builder builder = new Builder().setSession("فیلتر محسن — " + name)
                .addAddress("172.19.0.2", 24)
                .addAddress("fd00::2", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setMtu(1500)
                .setBlocking(false);
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            builder.addDisallowedApplication(getPackageName()); // engine's own transport bypass
            builder.addDnsServer(flux ? "9.9.9.9" : "1.1.1.1");
            synchronized (teardownLock) {
                if (!current(token)) return null;
                ParcelFileDescriptor created = builder.establish();
                if (created == null) return "اندروید اجازه تونل نداد";
                tun = created;
                tunCopy = ParcelFileDescriptor.dup(created.getFileDescriptor());
                try {
                    Os.fcntlInt(tunCopy.getFileDescriptor(), OsConstants.F_SETFD,
                            OsConstants.FD_CLOEXEC);
                } catch (Exception ignored) { }
                File tunConfig = new File(workDir, "tunnel.yml");
                String mapped = flux ? "mapdns:\n    address: 9.9.9.9\n"
                        + "    port: 53\n    network: 100.64.0.0\n"
                        + "    netmask: 255.192.0.0\n    cache-size: 10000\n" : "";
                String config = "tunnel:\n  mtu: 1500\n  ipv4: 172.19.0.2\n"
                        + "  ipv6: 'fd00::2'\n  icmp: 'reply'\n"
                        + "socks5:\n  address: 127.0.0.1\n  port: " + SOCKS_PORT
                        + "\n  udp: 'udp'\n" + mapped
                        + "misc:\n  task-stack-size: 32768\n  log-level: warn\n";
                try (FileOutputStream output = new FileOutputStream(tunConfig)) {
                    output.write(config.getBytes(StandardCharsets.UTF_8));
                }
                if (!TProxyService.TProxyStartService(tunConfig.getAbsolutePath(),
                        tunCopy.getFd())) return "پل انتقال ترافیک VPN راه‌اندازی نشد";
            }
            boolean bridge = false;
            for (int i = 0; i < 20 && current(token); i++) {
                if (TProxyService.TProxyIsRunning()) { bridge = true; break; }
                Thread.sleep(120L);
            }
            if (!bridge || !current(token)) return current(token)
                    ? "پل انتقال ترافیک فعال نشد" : null;
        } catch (Exception | LinkageError error) {
            return "ساخت تونل ناموفق: " + error.getClass().getSimpleName();
        }

        // An excluded app UID cannot always bind a direct test to its own
        // Android VPN. Such a failed probe is NOT proof that user apps are
        // offline. Require (1) authenticated HTTPS from the actual native
        // SOCKS engine and (2) an established, running TUN/tun2socks bridge.
        // Never say "VPN HTTPS verified" unless that separate test really passed.
        if (!NativeProxyProbe.verify(SOCKS_PORT) || !hasRunningTunnel())
            return "پل VPN یا عبور HTTPS موتور تأیید نشد";
        if (!current(token)) return null;
        report(name + " — HTTPS موتور تأیید شد؛ تونل VPN فعال است", "connected");

        establishedAt = SystemClock.elapsedRealtime();
        Uplink observed = uplink;
        long nextProbe = establishedAt + 30_000L;
        int consecutiveFailures = 0;
        boolean waitingForNetwork = false;
        boolean recheckingAfterHandoff = false;
        while (current(token)) {
            Uplink route = uplink;
            if (route != observed) {
                observed = route;
                consecutiveFailures = 0;
                nextProbe = route.changedAt() + 2_000L;
                recheckingAfterHandoff = true;
            }
            if (route.network() == null && route.changedAt() != 0L) {
                if (!waitingForNetwork) {
                    waitingForNetwork = true;
                    report(name + " — منتظر برگشت اینترنت گوشی…", "searching");
                }
                waitForNetworkOrTime(token, route, 30_000L);
                continue;
            }
            long delay = nextProbe - SystemClock.elapsedRealtime();
            if (delay > 0L) {
                waitForNetworkOrTime(token, route, delay);
                continue;
            }
            if (!hasRunningTunnel()) return "تونل یا موتور بسته شد";
            boolean reachable = NativeProxyProbe.verify(SOCKS_PORT);
            if (!current(token)) return null;
            if (route != uplink) continue;
            if (reachable) {
                if (waitingForNetwork || consecutiveFailures > 0 || recheckingAfterHandoff) {
                    report(name + " — عبور HTTPS دوباره تأیید شد", "connected");
                }
                waitingForNetwork = false;
                recheckingAfterHandoff = false;
                consecutiveFailures = 0;
                nextProbe = SystemClock.elapsedRealtime() + 30_000L;
            } else if (++consecutiveFailures >= 2) {
                return "دو آزمون پیاپی HTTPS موتور ناموفق بود";
            } else {
                report(name + " — افت موقت کیفیت؛ بررسی مجدد…",
                        waitingForNetwork ? "searching" : "connected");
                nextProbe = SystemClock.elapsedRealtime() + 5_000L;
            }
        }
        return null;
    }

    private void waitForNetworkOrTime(int token, Uplink route, long delay) throws InterruptedException {
        synchronized (networkLock) {
            if (current(token) && uplink == route) networkLock.wait(delay);
        }
    }

    private File prepareCertificateBundle() {
        File certs = new File(getFilesDir(), "nativecore-ca-roots.pem");
        if (certs.isFile() && certs.length() > 1000
                && System.currentTimeMillis() - certs.lastModified()
                        < 7L * 24L * 60L * 60L * 1000L) return certs;
        try {
            KeyStore store = KeyStore.getInstance("AndroidCAStore");
            store.load(null);
            try (FileOutputStream out = new FileOutputStream(certs)) {
                java.util.Enumeration<String> aliases = store.aliases();
                while (aliases.hasMoreElements()) {
                    String alias = aliases.nextElement();
                    // Match Android's system trust anchors rather than
                    // accidentally trusting user-added debugging roots.
                    if (!alias.startsWith("system:")) continue;
                    Certificate cert = store.getCertificate(alias);
                    if (cert == null) continue;
                    out.write("-----BEGIN CERTIFICATE-----\n".getBytes(StandardCharsets.US_ASCII));
                    out.write(Base64.getMimeEncoder(64, new byte[] { '\n' })
                            .encode(cert.getEncoded()));
                    out.write("\n-----END CERTIFICATE-----\n"
                            .getBytes(StandardCharsets.US_ASCII));
                }
            }
        } catch (Exception error) {
            certs.delete();
        }
        return certs;
    }

    private void destroyProcess(Process process) {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException ignored) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        if (engineProcess == process) engineProcess = null;
    }

    private void teardownNative() {
        // Native stop must finish before closing its duplicate TUN fd.
        try { TProxyService.TProxyStopService(); } catch (LinkageError | RuntimeException ignored) { }
        if (tunCopy != null) { try { tunCopy.close(); } catch (IOException ignored) { } tunCopy = null; }
        if (tun != null) { try { tun.close(); } catch (IOException ignored) { } tun = null; }
        Process old = engineProcess;
        engineProcess = null;
        destroyProcess(old);
    }

    private void stopAndExit() {
        if (closing) return;
        stopped = true;
        closing = true;
        generation.incrementAndGet();
        Thread old = worker;
        if (old != null) old.interrupt();
        report("اتصال " + currentEngine + " قطع شد", "disconnected");
        new Thread(() -> {
            try {
                synchronized (teardownLock) { teardownNative(); }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                closing = false;
            }
        }, "mohsen-core-stop").start();
    }

    @Override public void onRevoke() {
        stopAndExit();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        stopped = true;
        stopWatchingNetwork();
        generation.incrementAndGet();
        Thread old = worker;
        if (old != null) old.interrupt();
        synchronized (teardownLock) { teardownNative(); }
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    public static boolean isSessionRunning() {
        return activeInstance != null;
    }

    public static String runningEngine() {
        NativeCoreVpnService instance = activeInstance;
        return instance != null && !instance.stopped ? instance.currentEngine : "";
    }

    /** The app UID is excluded from VPN; getActiveNetwork() may be Wi-Fi
     * even while other apps are using our live TUN. Check the service owner. */
    public static boolean hasRunningTunnel() {
        NativeCoreVpnService instance = activeInstance;
        if (instance == null || instance.stopped || instance.tun == null
                || instance.engineProcess == null || !instance.engineProcess.isAlive())
            return false;
        try { return TProxyService.TProxyIsRunning(); }
        catch (RuntimeException | LinkageError error) { return false; }
    }

    static long sampleDownloadKbps() {
        return hasRunningTunnel() ? NativeProxyProbe.sampleDownloadKbps(SOCKS_PORT) : -1L;
    }

    public static long[] tunnelBytes() {
        try {
            long[] stats = TProxyService.TProxyGetStats();
            if (stats != null && stats.length >= 4)
                return new long[] { stats[3], stats[1] }; // rx bytes, tx bytes
        } catch (RuntimeException | LinkageError ignored) { }
        return null;
    }
}
