package ir.emergencyconnect.engine;

import android.net.VpnService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.IpPrefix;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import android.util.Base64;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android TUN adapter scaffold for the embedded OpenVPN 3 engine.
 *
 * This class owns Android's VPN interface. The OpenVPN protocol/session is
 * managed by the native OpenVPNClient adapter; this class alone is not a VPN.
 */
public final class RahYabVpnService extends VpnService {
    public static final String ACTION_START = "ir.emergencyconnect.engine.START_VPN";
    public static final String ACTION_STOP = "ir.emergencyconnect.engine.STOP_VPN";
    public static final String EXTRA_PROFILE = "ir.emergencyconnect.engine.PROFILE";
    public static final String EXTRA_PROFILES = "ir.emergencyconnect.engine.PROFILES";
    private static final long AUTO_ATTEMPT_TIMEOUT_SECONDS = 12;
    private static final String CHANNEL_ID = "rah_yab_vpn_status";
    private static final int NOTIFICATION_ID = 7301;
    private ParcelFileDescriptor tunInterface;
    private boolean nativeCallbacksBound;
    private String nativeBindDiagnostic = "NOT_INITIALIZED";
    private boolean foregroundStarted;
    private final ExecutorService sessionExecutor = Executors.newSingleThreadExecutor();
    private final Object nativeSessionLock = new Object();
    private volatile boolean serviceDestroyed;
    private volatile boolean autoSearchEnabled;
    private volatile boolean autoConnected;
    private volatile boolean attemptConnected;
    private volatile CountDownLatch attemptSignal;
    private volatile ArrayList<String> autoProfiles;
    private volatile int nextAutoIndex;
    private volatile int autoStartId;
    private final AtomicBoolean failoverQueued = new AtomicBoolean(false);
    private final java.util.Set<String> triedServerEndpoints = new java.util.HashSet<>();
    private static final String VPN_GATE_API = "https://www.vpngate.net/api/iphone/";

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "وضعیت راه‌یاب VPN",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("اعلان دائمی هنگام فعالیت سرویس VPN");
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildStatusNotification(String message) {
        Intent stopIntent = new Intent(this, RahYabVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPending = PendingIntent.getService(this, 7302, stopIntent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("راه‌یاب VPN")
                .setContentText(message)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "توقف", stopPending)
                .build();
    }

    private void enterForeground() {
        if (foregroundStarted) return;
        ensureNotificationChannel();
        Notification notification = buildStatusNotification(
                "در حال آماده‌سازی اتصال داخلی…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        // Android requires a foreground service to promote itself immediately
        // after startForegroundService(). Do this before preferences, extras,
        // profile parsing, or executor submission so startup cannot time out.
        if (!ACTION_STOP.equals(action)) {
            try {
                enterForeground();
            } catch (RuntimeException error) {
                String detail = error.getClass().getSimpleName() + ": "
                        + (error.getMessage() == null ? "بدون توضیح" : error.getMessage());
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE)
                        .edit().putString("service_start_error", detail).apply();
                setLampState("disconnected");
                stopSelf(startId);
                return START_NOT_STICKY;
            }
        }

        if (ACTION_STOP.equals(action)) {
            autoSearchEnabled = false;
            autoConnected = false;
            autoProfiles = null;
            CountDownLatch activeAttempt = attemptSignal;
            if (activeAttempt != null) activeAttempt.countDown();
            attemptSignal = null;
            setLampState("disconnected");
            sessionExecutor.execute(() -> {
                synchronized (nativeSessionLock) {
                    OpenVpn3Bridge.stopSession();
                }
                closeTun();
                stopSelf(startId);
            });
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("service_start_received", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()))
                    .putString("session_start_result", "سرویس VPN درخواست اتصال را دریافت کرد؛ در حال آماده‌سازی موتور…")
                    .apply();
            setLampState("searching");
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE)
                    .edit()
                    .remove("service_start_error")
                    .remove("native_last_event")
                    .remove("native_event_history")
                    .putString("session_start_result", "درخواست شروع نشست در حال آماده‌سازی است…")
                    .apply();
            final String profile = intent.getStringExtra(EXTRA_PROFILE);
            final ArrayList<String> profiles = intent.getStringArrayListExtra(EXTRA_PROFILES);
            if ((profile == null || profile.trim().isEmpty())
                    && (profiles == null || profiles.isEmpty())) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            sessionExecutor.execute(() -> {
                if (serviceDestroyed) return;
                // JNI binding can perform native initialization. Keep it off
                // Android's service main thread: the service must already be
                // foreground before any native work begins.
                if (!nativeCallbacksBound) {
                    nativeBindDiagnostic = OpenVpn3Bridge.bindTunService(this);
                    nativeCallbacksBound = "OK".equals(nativeBindDiagnostic);
                    getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                            .putString("native_bind_result", nativeBindDiagnostic).apply();
                }
                if (!nativeCallbacksBound) {
                    setLampState("disconnected");
                    updateStatus("اتصال داخلی در دسترس نیست: اتصال پل JNI برقرار نشد — "
                            + nativeBindDiagnostic);
                    stopSelf(startId);
                    return;
                }
                if (profiles != null && !profiles.isEmpty()) {
                    synchronized (triedServerEndpoints) { triedServerEndpoints.clear(); }
                    runAutoSearch(profiles, 0, startId);
                } else {
                    startSingleProfile(profile);
                }
            });
            return START_NOT_STICKY;
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void startSingleProfile(String profile) {
        autoSearchEnabled = false;
        autoConnected = false;
        autoProfiles = null;
        CountDownLatch activeAttempt = attemptSignal;
        if (activeAttempt != null) activeAttempt.countDown();
        attemptSignal = null;
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("session_start_result", "درخواست به موتور ارسال شد؛ منتظر پاسخ…").apply();
        final String result;
        synchronized (nativeSessionLock) {
            if (serviceDestroyed) return;
            OpenVpn3Bridge.stopSession();
            if (serviceDestroyed) return;
            result = OpenVpn3Bridge.startSession(profile);
        }
        String safeResult = result == null ? "هسته پاسخی نداد" : result;
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("session_start_result", safeResult).apply();
        updateStatus(safeResult);
    }

    private void runAutoSearch(ArrayList<String> profiles, int startIndex, int startId) {
        if (profiles == null || profiles.isEmpty() || serviceDestroyed) return;
        autoSearchEnabled = true;
        autoProfiles = new ArrayList<>(profiles);
        autoStartId = startId;
        autoConnected = false;
        int count = autoProfiles.size();
        int begin = Math.max(0, Math.min(startIndex, count - 1));
        for (int offset = 0; offset < count; offset++) {
            int i = (begin + offset) % count;
            if (!autoSearchEnabled || serviceDestroyed) return;
            String profile = autoProfiles.get(i);
            String endpoint = profileEndpoint(profile);
            synchronized (triedServerEndpoints) { if (!endpoint.isEmpty()) triedServerEndpoints.add(endpoint); }
            CountDownLatch signal = new CountDownLatch(1);
            attemptConnected = false;
            String progress = "در حال آزمایش سرور " + (i + 1) + " از " + count
                    + (endpoint.isEmpty() ? "" : " — " + endpoint)
                    + "؛ حداکثر ۱۲ ثانیه";
            updateStatus(progress);
            setServerTestStatus(endpoint, "testing");
            setLampState("searching");
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("session_start_result", progress).apply();
            final String result;
            synchronized (nativeSessionLock) {
                if (!autoSearchEnabled || serviceDestroyed) {
                    attemptSignal = null;
                    return;
                }
                OpenVpn3Bridge.stopSession();
                attemptSignal = signal;
                result = OpenVpn3Bridge.startSession(profile);
            }
            boolean accepted = result != null
                    && result.startsWith("درخواست اتصال به هسته OpenVPN 3 تحویل شد");
            if (!accepted) {
                setServerTestStatus(endpoint, "failed");
                appendAutoHistory("AUTO_SEARCH_SKIP", "پاسخ شروع موتور برای " + endpoint + ": " + (result == null ? "بدون پاسخ" : result));
                attemptSignal = null;
                appendAutoHistory("AUTO_SEARCH_SKIP", "سرور " + (i + 1)
                        + ": " + (result == null ? "بدون پاسخ" : result));
                continue;
            }
            try {
                signal.await(AUTO_ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                attemptSignal = null;
                return;
            }
            boolean connected = attemptConnected;
            attemptSignal = null;
            if (connected) {
                setServerTestStatus(endpoint, "connected");
                autoConnected = true;
                nextAutoIndex = (i + 1) % count;
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                        .putString("session_start_result", "اتصال واقعی OpenVPN با سرور "
                                + (i + 1) + " از " + count + " برقرار شد.").apply();
                appendAutoHistory("AUTO_SEARCH_CONNECTED", "سرور " + (i + 1)
                        + " از " + count + "؛ اتصال واقعی تأیید شد");
                setLampState("connected");
                updateStatus("اتصال برقرار شد؛ سرور " + (i + 1) + " از " + count
                        + (endpoint.isEmpty() ? "" : " — " + endpoint));
                return;
            }
            setServerTestStatus(endpoint, "failed");
            synchronized (nativeSessionLock) {
                OpenVpn3Bridge.stopSession();
            }
            appendAutoHistory("AUTO_SEARCH_NEXT", "سرور " + (i + 1)
                    + " موفق نشد؛ انتقال به سرور بعدی");
        }
        if (!autoSearchEnabled || serviceDestroyed) return;
        appendAutoHistory("AUTO_REFRESH", "این نوبت تمام شد؛ دریافت ۴۰ سرور تازه آغاز می‌شود");
        updateStatus("۴۰ سرور این نوبت موفق نشدند؛ در حال دریافت ۴۰ سرور تازه…");
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("session_start_result", "۴۰ سرور بررسی شد؛ در حال دریافت فهرست تازه…").apply();
        ArrayList<String> freshProfiles = fetchFreshProfiles(40);
        if (!autoSearchEnabled || serviceDestroyed) return;
        if (freshProfiles.isEmpty()) {
            autoSearchEnabled = false;
            autoConnected = false;
            setLampState("disconnected");
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("session_start_result", "سرور تازه‌ای از منبع دریافت نشد؛ بعداً دوباره تلاش کنید.").apply();
            updateStatus("سرور تازه‌ای دریافت نشد؛ منبع فعلاً پاسخ‌گو نیست یا سرور جدیدی باقی نمانده است.");
            return;
        }
        autoProfiles = new ArrayList<>(freshProfiles);
        appendAutoHistory("AUTO_REFRESH_READY", freshProfiles.size() + " سرور تازه دریافت شد");
        runAutoSearch(freshProfiles, 0, startId);
    }

    private ArrayList<String> fetchFreshProfiles(int wanted) {
        ArrayList<String> profiles = new ArrayList<>();
        String[] sources = new String[] { VPN_GATE_API,
                "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv" };
        for (String source : sources) {
            HttpURLConnection connection = null;
            BufferedReader reader = null;
            try {
                connection = (HttpURLConnection) new URL(source).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", "RahYabVPN/2.0");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) continue;
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                String line; boolean header = false; int bytes = 0;
                while ((line = reader.readLine()) != null && profiles.size() < wanted) {
                    bytes += line.length();
                    if (bytes > 8 * 1024 * 1024) break;
                    if (line.startsWith("#HostName")) { header = true; continue; }
                    if (!header || line.startsWith("#") || line.trim().isEmpty()) continue;
                    java.util.List<String> fields = parseCsvFields(line);
                    if (fields.size() < 15) continue;
                    try {
                        byte[] raw = Base64.decode(fields.get(14).trim(), Base64.DEFAULT);
                        if (raw.length == 0 || raw.length > 256 * 1024) continue;
                        String config = new String(raw, "UTF-8");
                        if (!config.contains("client") || !config.contains("remote ")) continue;
                        String endpoint = profileEndpoint(config);
                        synchronized (triedServerEndpoints) {
                            if (endpoint.isEmpty() || triedServerEndpoints.contains(endpoint)) continue;
                        }
                        profiles.add(config);
                    } catch (IllegalArgumentException ignored) { }
                }
                if (!profiles.isEmpty()) break;
            } catch (Exception ignored) {
            } finally {
                if (reader != null) try { reader.close(); } catch (IOException ignored) { }
                if (connection != null) connection.disconnect();
            }
        }
        return profiles;
    }

    private java.util.List<String> parseCsvFields(String line) {
        ArrayList<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(field.toString()); field.setLength(0);
            } else field.append(c);
        }
        fields.add(field.toString());
        return fields;
    }

    private String profileEndpoint(String profile) {
        if (profile == null) return "";
        for (String line : profile.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("remote ")) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 3) return parts[1] + ":" + parts[2];
            }
        }
        return "";
    }

    private void setServerTestStatus(String endpoint, String state) {
        if (endpoint == null || endpoint.isEmpty()) return;
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("server_test_" + endpoint, state).apply();
    }

    private void appendAutoHistory(String name, String info) {
        android.content.SharedPreferences diagnostics =
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
        String history = diagnostics.getString("native_event_history", "");
        String detail = name + " — " + info.replace('\n', ' ').replace('\r', ' ');
        String next = history.isEmpty() ? detail : history + "\n" + detail;
        String[] entries = next.split("\n");
        if (entries.length > 30) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = entries.length - 30; i < entries.length; i++) {
                if (trimmed.length() > 0) trimmed.append('\n');
                trimmed.append(entries[i]);
            }
            next = trimmed.toString();
        }
        diagnostics.edit().putString("native_last_event", detail)
                .putString("native_event_history", next).apply();
    }

    private void scheduleAutoFailover() {
        if (!autoSearchEnabled || !autoConnected
                || !failoverQueued.compareAndSet(false, true)) return;
        autoConnected = false;
        sessionExecutor.execute(() -> {
            failoverQueued.set(false);
            if (!autoSearchEnabled || serviceDestroyed || autoProfiles == null) return;
            appendAutoHistory("AUTO_FAILOVER", "قطع نشست؛ جست‌وجوی سرور جایگزین آغاز شد");
            runAutoSearch(autoProfiles, nextAutoIndex, autoStartId);
        });
    }

    @Override
    public void onCreate() {
        // Do not initialize JNI here. Android calls onCreate() before
        // onStartCommand(); blocking native initialization here can prevent
        // startForeground() from being called before the system deadline.
        super.onCreate();
    }

    /**
     * Compatibility entry point retained for the first single-IPv4 prototype.
     */
    public synchronized int establishTun(
            String sessionName,
            String ipv4Address,
            int prefixLength,
            String dnsServer,
            String routeAddress,
            int routePrefix,
            int mtu) throws Exception {
        String[] addresses = new String[]{ipv4Address};
        int[] prefixes = new int[]{prefixLength};
        String[] dns = dnsServer == null || dnsServer.isEmpty()
                ? new String[0] : new String[]{dnsServer};
        String[] routes = routeAddress == null || routeAddress.isEmpty()
                ? new String[0] : new String[]{routeAddress};
        int[] routePrefixes = routes.length == 0
                ? new int[0] : new int[]{routePrefix};
        return establishTun(sessionName, addresses, prefixes, dns,
                routes, routePrefixes, mtu);
    }

    /**
     * Establish a TUN interface with all addresses, DNS servers and routes
     * collected by the future OpenVPN 3 TunBuilder JNI adapter.
     *
     * The returned detached descriptor is owned by native code and must be
     * closed exactly once by that session during teardown.
     */
    public synchronized int establishTun(
            String sessionName,
            String[] addresses,
            int[] prefixes,
            String[] dnsServers,
            String[] routeAddresses,
            int[] routePrefixes,
            int mtu) throws Exception {
        return establishTun(sessionName, addresses, prefixes, dnsServers,
                routeAddresses, routePrefixes, new String[0], new int[0],
                new String[0], mtu);
    }

    /**
     * Full route form used by the embedded engine. Excluded routes are
     * supported only on Android 13/API 33 and newer; older versions fail
     * explicitly rather than silently routing traffic differently.
     */
    public synchronized int establishTun(
            String sessionName,
            String[] addresses,
            int[] prefixes,
            String[] dnsServers,
            String[] routeAddresses,
            int[] routePrefixes,
            String[] excludedRouteAddresses,
            int[] excludedRoutePrefixes,
            String[] searchDomains,
            int mtu) throws Exception {

        closeTun();

        if (addresses == null || prefixes == null
                || addresses.length == 0 || addresses.length > 32
                || addresses.length != prefixes.length) {
            throw new IllegalArgumentException("TUN addresses/prefixes are missing, excessive or mismatched");
        }
        if (dnsServers == null || dnsServers.length > 8
                || routeAddresses == null || routePrefixes == null
                || routeAddresses.length > 512 || routeAddresses.length != routePrefixes.length
                || excludedRouteAddresses == null || excludedRoutePrefixes == null
                || excludedRouteAddresses.length > 512
                || excludedRouteAddresses.length != excludedRoutePrefixes.length
                || searchDomains == null || searchDomains.length > 16) {
            throw new IllegalArgumentException("TUN DNS/routes are invalid or excessive");
        }
        if (mtu != 0 && (mtu < 576 || mtu > 65535)) {
            throw new IllegalArgumentException("Invalid TUN MTU");
        }
        if (sessionName != null && sessionName.length() > 128) {
            throw new IllegalArgumentException("TUN session name is too long");
        }
        if (excludedRouteAddresses.length > 0 && Build.VERSION.SDK_INT < 33) {
            throw new UnsupportedOperationException(
                    "Excluded VPN routes require Android 13 or newer");
        }

        Builder builder = new Builder()
                .setSession(sessionName == null || sessionName.isEmpty()
                        ? "RahYabVPN" : sessionName);

        for (int i = 0; i < addresses.length; i++) {
            InetAddress address = parseNumericAddress(addresses[i], "TUN address");
            int maxPrefix = address.getAddress().length * 8;
            if (prefixes[i] < 0 || prefixes[i] > maxPrefix) {
                throw new IllegalArgumentException("Invalid TUN address prefix");
            }
            builder.addAddress(address, prefixes[i]);
        }

        for (String dns : dnsServers) {
            builder.addDnsServer(parseNumericAddress(dns, "DNS server"));
        }

        for (String domain : searchDomains) {
            if (domain == null || domain.isEmpty() || domain.length() > 253
                    || !domain.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) {
                throw new IllegalArgumentException("Invalid DNS search domain");
            }
            builder.addSearchDomain(domain);
        }

        for (int i = 0; i < routeAddresses.length; i++) {
            InetAddress route = parseNumericAddress(routeAddresses[i], "route");
            int maxPrefix = route.getAddress().length * 8;
            if (routePrefixes[i] < 0 || routePrefixes[i] > maxPrefix) {
                throw new IllegalArgumentException("Invalid route prefix");
            }
            builder.addRoute(route, routePrefixes[i]);
        }

        for (int i = 0; i < excludedRouteAddresses.length; i++) {
            InetAddress route = parseNumericAddress(
                    excludedRouteAddresses[i], "excluded route");
            int maxPrefix = route.getAddress().length * 8;
            if (excludedRoutePrefixes[i] < 0 || excludedRoutePrefixes[i] > maxPrefix) {
                throw new IllegalArgumentException("Invalid excluded route prefix");
            }
            builder.excludeRoute(new IpPrefix(route, excludedRoutePrefixes[i]));
        }

        if (mtu != 0) {
            builder.setMtu(mtu);
        }

        tunInterface = builder.establish();
        if (tunInterface == null) {
            return -1;
        }

        ParcelFileDescriptor established = tunInterface;
        int fd = established.detachFd();
        tunInterface = null;
        established.close();
        return fd;
    }

    /**
     * Reject hostnames here: OpenVPN supplies numeric addresses to the
     * TunBuilder callbacks and DNS resolution must not occur on this path.
     */
    private static InetAddress parseNumericAddress(String value, String field)
            throws Exception {
        if (value == null || value.isEmpty() || value.length() > 64) {
            throw new IllegalArgumentException("Invalid numeric " + field);
        }

        // Never allow a hostname or a shortened/ambiguous IPv4 literal to
        // reach InetAddress resolution from the VPN configuration callback.
        if (value.indexOf(':') < 0) {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) {
                throw new IllegalArgumentException("Invalid IPv4 " + field);
            }
            byte[] address = new byte[4];
            for (int i = 0; i < octets.length; i++) {
                String octet = octets[i];
                if (octet.isEmpty() || octet.length() > 3
                        || !octet.matches("[0-9]+")) {
                    throw new IllegalArgumentException("Invalid IPv4 " + field);
                }
                int number = Integer.parseInt(octet);
                if (number > 255) {
                    throw new IllegalArgumentException("Invalid IPv4 " + field);
                }
                address[i] = (byte) number;
            }
            return InetAddress.getByAddress(address);
        }

        // A colon is mandatory for IPv6; restrict input to literal syntax
        // before parsing so this path cannot perform hostname lookups.
        if (!value.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalArgumentException("Invalid IPv6 " + field);
        }
        InetAddress parsed = InetAddress.getByName(value);
        if (parsed.getAddress().length != 16) {
            throw new IllegalArgumentException("Invalid IPv6 " + field);
        }
        return parsed;
    }

    private void setLampState(String state) {
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("connection_lamp_state", state).apply();
    }

    private void updateStatus(String message) {
        if (!foregroundStarted) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildStatusNotification(message));
    }

    /** Receive OpenVPN core events on its worker thread and reflect them in the foreground notification. */
    public void onNativeEvent(String name, String info, boolean error, boolean fatal) {
        if (name == null) name = "UNKNOWN";
        // Keep each OpenVPN callback as one history entry. Some CORE_LOG
        // messages contain embedded newlines (for example Peer Info); without
        // normalization those lines consume the history limit and hide the
        // actual sequence of connection events.
        String safeInfo = info == null ? "" : info.replace('\r', ' ').replace('\n', ' ').trim();
        if (safeInfo.length() > 1200) safeInfo = safeInfo.substring(0, 1200);
        String detail = name + (safeInfo.isEmpty() ? "" : " — " + safeInfo)
                + (error || fatal ? " [ERROR]" : "");
        android.content.SharedPreferences diagnostics =
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
        String history = diagnostics.getString("native_event_history", "");
        String nextHistory = history.isEmpty() ? detail : history + "\n" + detail;
        String[] entries = nextHistory.split("\n");
        if (entries.length > 30) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = entries.length - 30; i < entries.length; i++) {
                if (trimmed.length() > 0) trimmed.append('\n');
                trimmed.append(entries[i]);
            }
            nextHistory = trimmed.toString();
        }
        diagnostics.edit().putString("native_last_event", detail)
                .putString("native_event_history", nextHistory).apply();
        CountDownLatch waiting = attemptSignal;
        if (waiting != null) {
            if ("CONNECTED".equals(name) && !error && !fatal) {
                attemptConnected = true;
                waiting.countDown();
            } else if (error || fatal || "NATIVE_CONNECT_RESULT".equals(name)
                    || "NATIVE_CONNECT_EXCEPTION".equals(name)
                    || "EXITING".equals(name)) {
                attemptConnected = false;
                waiting.countDown();
            }
        }
        if (autoConnected && ("NATIVE_CONNECT_EXCEPTION".equals(name)
                || "DISCONNECTED".equals(name) || "EXITING".equals(name))) {
            scheduleAutoFailover();
        }
        if ("CONNECTED".equals(name) && !error && !fatal) {
            setLampState("connected");
        } else if (error || fatal || "DISCONNECTED".equals(name) || "EXITING".equals(name)) {
            setLampState("disconnected");
        } else if ("CONNECTING".equals(name) || "WAIT".equals(name)) {
            setLampState("searching");
        }
        String message;
        if ("CONNECTED".equals(name)) {
            message = "اتصال برقرار شد";
        } else if (error || fatal) {
            message = "خطای اتصال: " + name;
        } else if ("CONNECTING".equals(name) || "WAIT".equals(name)) {
            message = "در حال اتصال: " + name;
        } else if ("DISCONNECTED".equals(name) || "EXITING".equals(name)) {
            message = "اتصال قطع شد";
        } else {
            message = "وضعیت VPN: " + name;
        }
        if (info != null && !info.isEmpty() && (error || fatal)) {
            message += " — " + info;
        }
        if (foregroundStarted && !"CORE_LOG".equals(name)) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildStatusNotification(message));
            }
        }
    }

    /** Protect transport sockets from being routed into their own TUN. */
    public boolean protectTransportSocket(int socketFd) {
        return protect(socketFd);
    }

    public synchronized void closeTun() {
        if (tunInterface != null) {
            try {
                tunInterface.close();
            } catch (Exception ignored) {
                // Teardown is best-effort; native owns detached descriptors.
            }
            tunInterface = null;
        }
    }

    /**
     * Handle VPN consent revocation and replacement by another VPN.
     * Stop the native worker before closing the TUN descriptor. Native stop
     * joins its worker, so perform it on the service executor, not the main
     * thread that receives this callback.
     */
    @Override
    public void onRevoke() {
        serviceDestroyed = true;
        setLampState("disconnected");
        try {
            sessionExecutor.execute(() -> {
                synchronized (nativeSessionLock) {
                    OpenVpn3Bridge.stopSession();
                }
                closeTun();
                if (foregroundStarted) {
                    stopForeground(true);
                    foregroundStarted = false;
                }
                stopSelf();
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // onDestroy has already shut down the executor and owns cleanup.
        }
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        serviceDestroyed = true;
        autoSearchEnabled = false;
        autoConnected = false;
        autoProfiles = null;
        attemptSignal = null;
        sessionExecutor.shutdownNow();
        setLampState("disconnected");
        // Stop and join the native worker before closing the TUN or releasing
        // the JNI service reference. This prevents callbacks into a destroyed
        // Android service during teardown.
        synchronized (nativeSessionLock) {
            OpenVpn3Bridge.stopSession();
        }
        closeTun();
        if (foregroundStarted) {
            stopForeground(true);
            foregroundStarted = false;
        }
        if (nativeCallbacksBound) {
            OpenVpn3Bridge.unbindTunService();
            nativeCallbacksBound = false;
        }
        super.onDestroy();
    }
}
