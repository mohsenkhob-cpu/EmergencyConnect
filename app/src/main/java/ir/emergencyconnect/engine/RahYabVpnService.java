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
import com.rahyab.next.VpnGateProvider;
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
    public static final String EXTRA_REQUEST_ID = "ir.emergencyconnect.engine.REQUEST_ID";
    private static final String PENDING_START_PREFS = "rah_yab_pending_start";
    private static final String PENDING_START_ID = "request_id";
    private static final String PENDING_START_PROFILES = "profiles_json";
    private static final String PENDING_START_CREATED_AT = "created_at_ms";
    // Pending start requests exist only to bridge Android's one-shot service
    // delivery/recovery window; they are not persistent auto-connect settings.
    private static final long PENDING_START_MAX_AGE_MS = 90_000L;
    // Public VPN Gate relays often do not answer at all. Keep each candidate
    // bounded so one dead relay cannot stall a user-initiated smart search.
    // 15 seconds allows an initial OpenVPN poll while cutting the old 30s wait.
    private static final long AUTO_ATTEMPT_TIMEOUT_SECONDS = 15;
    // Connected-session quality supervision. A single bad sample never triggers a switch.
    private static final long AUTO_MONITOR_INTERVAL_SECONDS = 45L;
    private static final long AUTO_MONITOR_LATENCY_LIMIT_MS = 1500L;
    private static final long AUTO_MONITOR_SPEED_LIMIT_KBPS = 250L;
    private static final int AUTO_MONITOR_BAD_SAMPLES_TO_SWITCH = 2;
    private static final int AUTO_FAILOVER_CANDIDATES = 3;
    private static final int AUTO_MAX_FAILOVERS_PER_SESSION = 2;
    private static final long AUTO_FAILOVER_COOLDOWN_MS = 180_000L;
    private static final String CHANNEL_ID = "rah_yab_vpn_status";
    private static final int NOTIFICATION_ID = 7301;
    private ParcelFileDescriptor tunInterface;
    private volatile boolean nativeCallbacksBound;
    private String nativeBindDiagnostic = "NOT_INITIALIZED";
    private volatile boolean foregroundStarted;
    private final ExecutorService sessionExecutor = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ScheduledExecutorService qualityMonitorExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "RahYab-quality-monitor");
                thread.setDaemon(true);
                return thread;
            });
    private volatile java.util.concurrent.ScheduledFuture<?> qualityMonitorFuture;
    private final Object nativeSessionLock = new Object();
    private volatile boolean serviceDestroyed;
    // Set at the very first instruction of ACTION_STOP to close the race between
    // a native callback and the session worker waking from its latch.
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    // Distinguish DISCONNECTED callbacks caused by our own native cleanup.
    private final AtomicBoolean nativeStopInProgress = new AtomicBoolean(false);
    private volatile boolean autoSearchEnabled;
    private volatile boolean autoConnected;
    private volatile boolean attemptConnected;
    private volatile CountDownLatch attemptSignal;
    private volatile ArrayList<String> autoProfiles;
    private volatile int nextAutoIndex;
    // One explicit user tap may test at most two bounded batches. A fresh
    // list must not turn a single tap into an endless background search.
    private static final int AUTO_MAX_CANDIDATES_PER_REQUEST = 12;
    private volatile int autoCandidatesTried;
    private volatile String activeAutoProfile;
    private volatile String activeTunnelExitIp;
    private volatile long activeTunnelLatencyMs = -1L;
    private volatile long activeTunnelThroughputKbps = -1L;
    private volatile int monitorBadSamples;
    private volatile int monitorLowSpeedSamples;
    private volatile int monitorSampleCount;
    private volatile int autoFailoversThisSession;
    private volatile long lastAutoFailoverAtMs;
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
            try {
                // Once Android recognizes this app as an active VPN, the
                // systemExempted type is the preferred VPN service category.
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                        .putString("foreground_service_type", "systemExempted")
                        .commit();
            } catch (SecurityException notActiveYet) {
                // During initial VPN setup the TUN is not active yet, so some
                // Android builds do not grant the VPN system exemption until
                // after Builder.establish(). Use the declared special-use
                // setup category to satisfy the immediate FGS deadline.
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                        .putString("foreground_service_type",
                                "specialUse (VPN setup fallback): "
                                        + notActiveYet.getClass().getSimpleName())
                        .commit();
            }
        } else {
            startForeground(NOTIFICATION_ID, notification);
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("foreground_service_type", "legacy")
                    .commit();
        }
        foregroundStarted = true;
    }

    private synchronized boolean claimStartRequest(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) return false;
        android.content.SharedPreferences pending =
                getSharedPreferences(PENDING_START_PREFS, MODE_PRIVATE);
        android.content.SharedPreferences diagnostics =
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
        String pendingId = pending.getString(PENDING_START_ID, null);
        long createdAt = pending.getLong(PENDING_START_CREATED_AT, 0L);
        long age = System.currentTimeMillis() - createdAt;
        String cancelledId = diagnostics.getString(
                "service_start_cancelled_request_id", null);
        String claimed = diagnostics.getString("service_claimed_request_id", null);
        if (!requestId.equals(pendingId) || createdAt <= 0L
                || age < 0L || age > PENDING_START_MAX_AGE_MS
                || requestId.equals(cancelledId) || requestId.equals(claimed)) {
            diagnostics.edit()
                    .putString("service_start_rejected_request_id", requestId)
                    .putString("service_start_rejected_reason",
                            !requestId.equals(pendingId) ? "not_pending"
                                    : createdAt <= 0L ? "missing_timestamp"
                                    : age < 0L ? "clock_moved_backwards"
                                    : age > PENDING_START_MAX_AGE_MS ? "expired"
                                    : requestId.equals(cancelledId) ? "cancelled"
                                    : "already_claimed")
                    .commit();
            if (requestId.equals(pendingId) && age > PENDING_START_MAX_AGE_MS) {
                pending.edit().clear().commit();
            }
            return false;
        }
        boolean claimedNow = diagnostics.edit()
                .putString("service_claimed_request_id", requestId)
                .putString("service_start_claimed_at",
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                                java.util.Locale.US).format(new java.util.Date()))
                .commit();
        if (claimedNow && requestId.equals(pending.getString(PENDING_START_ID, null))) {
            pending.edit().clear().commit();
        }
        return claimedNow;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) stopRequested.set(true);
        String requestId = intent == null ? null : intent.getStringExtra(EXTRA_REQUEST_ID);
        android.content.SharedPreferences entryDiagnostics =
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
        entryDiagnostics.edit()
                .putString("service_start_callback_observed_at",
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                                java.util.Locale.US).format(new java.util.Date()))
                .putString("service_start_callback_action", String.valueOf(action))
                .putString("service_start_callback_request_id", String.valueOf(requestId))
                .commit();
        android.util.Log.i("RahYabVpnService", "onStartCommand callback observed; action="
                + String.valueOf(action) + ", requestId=" + String.valueOf(requestId));
        if (ACTION_START.equals(action)
                && (requestId == null || !claimStartRequest(requestId))) {
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("service_start_duplicate_ignored", String.valueOf(requestId))
                    .commit();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            cancelQualityMonitor();
            stopRequested.set(false);
            autoCandidatesTried = 0;
            autoConnected = false;
            autoSearchEnabled = false;
            activeAutoProfile = null;
            activeTunnelExitIp = null;
            activeTunnelLatencyMs = -1L;
            activeTunnelThroughputKbps = -1L;
            monitorBadSamples = 0;
            monitorLowSpeedSamples = 0;
            monitorSampleCount = 0;
            autoFailoversThisSession = 0;
            lastAutoFailoverAtMs = 0L;
            failoverQueued.set(false);
        }
        android.util.Log.i("RahYabVpnService", "onStartCommand entered; startId="
                + startId + ", action=" + String.valueOf(action));
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("service_start_command_entered",
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                java.util.Locale.US).format(new java.util.Date())
                                + " (action=" + String.valueOf(action) + ")")
                .putString("service_start_request_id", String.valueOf(requestId))
                .commit();

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
            android.content.SharedPreferences pending =
                    getSharedPreferences(PENDING_START_PREFS, MODE_PRIVATE);
            String cancelledRequestId = pending.getString(PENDING_START_ID, null);
            if (cancelledRequestId != null) {
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                        .putString("service_start_cancelled_request_id",
                                cancelledRequestId)
                        .commit();
            }
            pending.edit().clear().commit();
            autoSearchEnabled = false;
            autoConnected = false;
            cancelQualityMonitor();
            autoProfiles = null;
            activeAutoProfile = null;
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("service_stop_requested_at", formatDiagnosticTime(System.currentTimeMillis()))
                    .putString("service_stop_reason", "ACTION_STOP received; active automatic attempt released if waiting")
                    .putString("service_stop_active_endpoint",
                            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE)
                                    .getString("auto_current_endpoint", ""))
                    .commit();
            appendAutoHistory("AUTO_STOP_REQUESTED",
                    "دستور توقف دریافت شد؛ آزمون جاری در صورت انتظار لغو می‌شود و به‌عنوان خرابی سرور شمرده نخواهد شد");
            CountDownLatch activeAttempt = attemptSignal;
            if (activeAttempt != null) activeAttempt.countDown();
            attemptSignal = null;
            setLampState("disconnected");
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .remove("auto_current_endpoint")
                    .remove("auto_current_started_at")
                    .remove("auto_current_protocol")
                    .putString("auto_current_wait_state", "CANCELLED_BY_STOP_OR_SERVICE")
                    .putString("session_start_result",
                            "اتصال به درخواست کاربر متوقف شد؛ برای شروع دوباره اتصال هوشمند را بزنید.")
                    .commit();
            updateStatus("اتصال برقرار نیست؛ برای شروع، دکمه اتصال هوشمند را بزنید.");
            try {
                sessionExecutor.execute(() -> {
                    synchronized (nativeSessionLock) {
                        stopNativeSession();
                    }
                    closeTun();
                    stopSelf(startId);
                });
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                // onDestroy already owns native/TUN cleanup; a late stop
                // command must not crash Android's service main thread.
                appendAutoHistory("STOP_QUEUE_CLOSED",
                        "درخواست توقف پس از بسته‌شدن صف سرویس دریافت شد؛ پاک‌سازی نهایی در حال انجام است");
            }
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
            try {
                sessionExecutor.execute(() -> {
                // Serialize JNI binding with native stop/unbind. Otherwise
                // onDestroy() could unbind first and a late bind could recreate
                // a leaked global service reference after teardown.
                synchronized (nativeSessionLock) {
                    if (serviceDestroyed) return;
                    // JNI binding can perform native initialization. Keep it off
                    // Android's service main thread: foreground promotion is
                    // already complete before this executor task can run.
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
                    if (serviceDestroyed) return;
                }
                if (profiles != null && !profiles.isEmpty()) {
                    synchronized (triedServerEndpoints) { triedServerEndpoints.clear(); }
                    runAutoSearch(profiles, 0, startId);
                } else {
                    startSingleProfile(profile);
                }
                });
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                autoSearchEnabled = false;
                autoConnected = false;
                setLampState("disconnected");
                appendAutoHistory("START_QUEUE_CLOSED",
                        "صف اجرای سرویس برای شروع نشست در دسترس نیست");
                updateStatus("شروع اتصال ممکن نشد؛ سرویس در حال بسته‌شدن است.");
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void startSingleProfile(String profile) {
        autoSearchEnabled = false;
        autoConnected = false;
        cancelQualityMonitor();
        autoProfiles = null;
        activeAutoProfile = null;
        CountDownLatch activeAttempt = attemptSignal;
        if (activeAttempt != null) activeAttempt.countDown();
        attemptSignal = null;
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("session_start_result", "درخواست به موتور ارسال شد؛ منتظر پاسخ…").apply();
        final String result;
        synchronized (nativeSessionLock) {
            if (serviceDestroyed) return;
            stopNativeSession();
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
        // Test a small bounded group: measure actual tunnel traffic rather than
        // trusting VPN Gate's advertised ping/speed or accepting first success.
        // Measure a broader but still bounded sample. Six candidates improve
        // the chance of finding a live relay without making each user-initiated
        // attempt unreasonably long on unstable public VPN Gate servers.
        final int remainingCandidates = Math.max(0,
                AUTO_MAX_CANDIDATES_PER_REQUEST - autoCandidatesTried);
        final int candidatesToMeasure = Math.min(6, Math.min(count, remainingCandidates));
        if (candidatesToMeasure <= 0) {
            autoSearchEnabled = false;
            autoConnected = false;
            setLampState("disconnected");
            updateStatus("این نوبت جست‌وجو به سقف ۱۲ سرور رسید و اتصال تأیید نشد؛ برای تلاش تازه، اتصال هوشمند را دوباره بزنید.");
            return;
        }
        String baselineExitIp = fetchPublicIp();
        appendAutoHistory("AUTO_BASELINE_IP",
                baselineExitIp == null ? "IP پیش از تونل قابل تشخیص نبود؛ تأیید نهایی IP ممکن نیست"
                        : "IP خروجی پیش از تونل ثبت شد");

        String bestProfile = null;
        String bestEndpoint = "";
        long bestLatencyMs = Long.MAX_VALUE;
        long bestThroughputKbps = -1L;
        double bestQualityScore = Double.MAX_VALUE;
        int measured = 0;

        for (int offset = 0; offset < candidatesToMeasure; offset++) {
            int i = (begin + offset) % count;
            if (isSessionCancelled()) return;
            String profile = autoProfiles.get(i);
            String endpoint = profileEndpoint(profile);
            synchronized (triedServerEndpoints) {
                if (!endpoint.isEmpty()) triedServerEndpoints.add(endpoint);
            }
            CountDownLatch signal = new CountDownLatch(1);
            attemptConnected = false;
            String progress = "در حال آزمایش سرور " + (offset + 1) + " از "
                    + candidatesToMeasure + (endpoint.isEmpty() ? "" : " — " + endpoint)
                    + "؛ بررسی اتصال و کیفیت";
            updateStatus(progress);
            setServerTestStatus(endpoint, "testing");
            setLampState("searching");
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("session_start_result", progress).apply();

            // Clear the previous candidate's transport observation so a
            // missing event cannot make the report display a stale TCP/UDP value.
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .remove("native_observed_transport_protocol")
                    .remove("native_observed_transport_at")
                    .remove("native_observed_transport_detail")
                    .commit();
            final String result;
            autoCandidatesTried++;
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putInt("auto_candidates_tried", autoCandidatesTried)
                    .putInt("auto_candidates_limit", AUTO_MAX_CANDIDATES_PER_REQUEST)
                    .commit();
            synchronized (nativeSessionLock) {
                if (!autoSearchEnabled || serviceDestroyed) {
                    attemptSignal = null;
                    return;
                }
                stopNativeSession();
                attemptSignal = signal;
                result = OpenVpn3Bridge.startSession(profile);
            }
            boolean accepted = result != null
                    && result.startsWith("درخواست اتصال به هسته OpenVPN 3 تحویل شد");
            if (!accepted) {
                setServerTestStatus(endpoint, "failed");
                appendAutoHistory("AUTO_SEARCH_SKIP",
                        "پاسخ شروع موتور برای " + endpoint + ": "
                                + (result == null ? "بدون پاسخ" : result));
                attemptSignal = null;
                continue;
            }
            long attemptStartedAt = System.currentTimeMillis();
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("auto_current_endpoint", endpoint)
                    .putString("auto_current_started_at", formatDiagnosticTime(attemptStartedAt))
                    .putString("auto_current_protocol", profileProtocol(profile))
                    .putString("auto_current_wait_state", "waiting_for_native_event")
                    .commit();
            final boolean callbackReceived;
            try {
                callbackReceived = signal.await(AUTO_ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                attemptSignal = null;
                return;
            }
            attemptSignal = null;
            // A stop/revoke also releases this latch. Check cancellation BEFORE
            // interpreting the signal as an OpenVPN callback, otherwise a user
            // pressing Disconnect is misreported as a failed server callback.
            final boolean cancelled = isSessionCancelled();
            String attemptOutcome = cancelled ? "CANCELLED_BY_STOP_OR_SERVICE"
                    : attemptConnected ? "OPENVPN_CONNECTED_EVENT"
                    : callbackReceived ? "NATIVE_CALLBACK_WITHOUT_CONNECTED"
                    : "NATIVE_CALLBACK_TIMEOUT";
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("auto_last_attempt_endpoint", endpoint)
                    .putString("auto_last_attempt_protocol", profileProtocol(profile))
                    .putString("auto_last_attempt_started_at", formatDiagnosticTime(attemptStartedAt))
                    .putString("auto_last_attempt_finished_at", formatDiagnosticTime(System.currentTimeMillis()))
                    .putString("auto_last_attempt_outcome", attemptOutcome)
                    .putString("auto_last_attempt_connected_event", String.valueOf(attemptConnected))
                    .putString("auto_last_attempt_callback_received",
                            String.valueOf(callbackReceived && !cancelled))
                    .putString("auto_last_attempt_cancelled", String.valueOf(cancelled))
                    .putString("auto_current_wait_state", attemptOutcome)
                    .commit();
            if (cancelled) {
                synchronized (nativeSessionLock) {
                    stopNativeSession();
                }
                setServerTestStatus(endpoint, "cancelled");
                appendAutoHistory("AUTO_ATTEMPT_CANCELLED",
                        "آزمون سرور " + endpoint
                                + " با درخواست توقف یا خروج سرویس لغو شد؛ نتیجه شبکه‌ای برای این سرور ثبت نشد");
                return;
            }
            appendAutoHistory("AUTO_ATTEMPT_RESULT",
                    "سرور " + endpoint + "؛ پروتکل " + profileProtocol(profile)
                            + "؛ نتیجه " + attemptOutcome
                            + "؛ رویداد اتصال " + attemptConnected
                            + "؛ دریافت callback " + callbackReceived);

            if (attemptConnected) {
                updateStatus("تونل برقرار شد؛ بررسی عبور اینترنت و اندازه‌گیری تأخیر…");
                boolean trafficVerified = verifyTunnelTraffic(baselineExitIp);
                if (trafficVerified) {
                    int stableProbes = measureTunnelStability();
                    if (stableProbes >= 4) {
                        long latency = measureTunnelLatencyMs();
                        if (latency >= 0) {
                            measured++;
                            long throughputKbps = measureTunnelDownloadKbps();
                            // Stability is a gate first, then a small penalty for
                            // one missed probe; throughput remains an approximate signal.
                            double qualityScore = latency + (5 - stableProbes) * 250.0
                                    + (throughputKbps > 0
                                    ? 1000.0 / Math.max(0.1, throughputKbps / 1000.0)
                                    : 0.0);
                            appendAutoHistory("AUTO_QUALITY_MEASURED",
                                    "سرور " + endpoint + "؛ پایداری HTTPS: "
                                            + stableProbes + "/5؛ تأخیر: " + latency
                                            + " ms؛ سرعت دریافت: "
                                            + (throughputKbps > 0
                                            ? throughputKbps + " kbps" : "نامشخص")
                                            + "؛ امتیاز مقایسه: "
                                            + String.format(java.util.Locale.US, "%.1f", qualityScore));
                            if (qualityScore < bestQualityScore) {
                                bestQualityScore = qualityScore;
                                bestLatencyMs = latency;
                                bestThroughputKbps = throughputKbps;
                                bestProfile = profile;
                                bestEndpoint = endpoint;
                            }
                        } else {
                            appendAutoHistory("AUTO_QUALITY_FAILED",
                                    "سرور " + endpoint + " آزمون تأخیر را کامل نکرد");
                        }
                    } else {
                        appendAutoHistory("AUTO_STABILITY_FAILED",
                                "سرور " + endpoint + " فقط " + stableProbes
                                        + "/5 آزمون پایداری را گذراند؛ برای انتخاب رد شد");
                    }
                } else {
                    appendAutoHistory("AUTO_TUNNEL_PROBE_FAILED",
                            "تونل سرور " + endpoint
                                    + " عبور واقعی اینترنت را تأیید نکرد");
                }
            }

            setServerTestStatus(endpoint, "tested");
            synchronized (nativeSessionLock) {
                stopNativeSession();
            }
            if (bestProfile != null) {
                updateStatus("مقایسه سرورها: بهترین نتیجه فعلی "
                        + bestLatencyMs + " میلی‌ثانیه؛ ادامه بررسی…");
            }
        }

        if (!autoSearchEnabled || serviceDestroyed) return;

        // Reconnect to the measured winner; never leave the last test candidate active.
        if (bestProfile != null) {
            updateStatus("بهترین سرور آزمایش شد؛ در حال برقراری اتصال نهایی…");
            CountDownLatch finalSignal = new CountDownLatch(1);
            attemptConnected = false;
            attemptSignal = finalSignal;
            String startResult;
            synchronized (nativeSessionLock) {
                if (!autoSearchEnabled || serviceDestroyed) {
                    attemptSignal = null;
                    return;
                }
                stopNativeSession();
                startResult = OpenVpn3Bridge.startSession(bestProfile);
            }
            boolean started = startResult != null
                    && startResult.startsWith("درخواست اتصال به هسته OpenVPN 3 تحویل شد");
            boolean connected = false;
            if (started) {
                try {
                    connected = finalSignal.await(AUTO_ATTEMPT_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS) && attemptConnected;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            attemptSignal = null;
            if (!autoSearchEnabled || serviceDestroyed) {
                synchronized (nativeSessionLock) {
                    stopNativeSession();
                }
                return;
            }
            if (connected && verifyTunnelTraffic(baselineExitIp)) {
                autoConnected = true;
                activeAutoProfile = bestProfile;
                activeTunnelLatencyMs = bestLatencyMs;
                activeTunnelThroughputKbps = bestThroughputKbps;
                nextAutoIndex = (begin + candidatesToMeasure) % count;
                setServerTestStatus(bestEndpoint, "connected");
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                        .putString("session_start_result",
                                "بهترین سرور از میان " + candidatesToMeasure
                                        + " گزینه با تأخیر " + bestLatencyMs
                                        + " میلی‌ثانیه انتخاب شد؛ عبور اینترنت دوباره تأیید شد.")
                        .apply();
                appendAutoHistory("AUTO_BEST_SELECTED",
                        "از میان " + candidatesToMeasure + " گزینه، "
                                + bestEndpoint + " با میانگین " + bestLatencyMs
                                + " میلی‌ثانیه انتخاب و اتصال نهایی تأیید شد");
                setLampState("connected");
                updateStatus("اتصال هوشمند برقرار است — "
                        + bestEndpoint + "؛ تأخیر " + bestLatencyMs + " ms"
                        + (bestThroughputKbps > 0
                        ? "؛ دریافت " + bestThroughputKbps + " kbps" : ""));
                startQualityMonitor();
                return;
            }
            appendAutoHistory("AUTO_BEST_RECONNECT_FAILED",
                    "اتصال مجدد به بهترین گزینه تأیید نشد؛ اتصال امن متوقف و جست‌وجو ادامه می‌یابد");
            synchronized (nativeSessionLock) {
                stopNativeSession();
            }
        }

        if (!autoSearchEnabled || serviceDestroyed) return;
        if (autoCandidatesTried >= AUTO_MAX_CANDIDATES_PER_REQUEST) {
            autoSearchEnabled = false;
            autoConnected = false;
            setLampState("disconnected");
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("session_start_result",
                            "۱۲ سرور در این نوبت بررسی شدند؛ اتصال واقعی تأیید نشد.")
                    .putInt("auto_candidates_tried", autoCandidatesTried)
                    .putInt("auto_candidates_limit", AUTO_MAX_CANDIDATES_PER_REQUEST)
                    .remove("auto_current_endpoint")
                    .remove("auto_current_started_at")
                    .commit();
            appendAutoHistory("AUTO_SEARCH_LIMIT_REACHED",
                    "سقف ۱۲ سرور برای این درخواست کاربر تکمیل شد؛ جست‌وجوی خودکار متوقف شد");
            updateStatus("۱۲ سرور بررسی شدند اما اتصال پایدار تأیید نشد؛ برای شروع یک نوبت تازه، اتصال هوشمند را بزنید.");
            return;
        }
        // Prefer the untested profiles already downloaded for this explicit
        // user request. A temporary VPN Gate/CDN outage must not discard the
        // remaining candidates in the current 30-profile snapshot.
        ArrayList<String> remainingProfiles = new ArrayList<>();
        synchronized (triedServerEndpoints) {
            for (String candidate : autoProfiles) {
                String candidateEndpoint = profileEndpoint(candidate);
                if (candidateEndpoint.isEmpty()
                        || !triedServerEndpoints.contains(candidateEndpoint)) {
                    remainingProfiles.add(candidate);
                }
            }
        }
        ArrayList<String> nextProfiles = remainingProfiles;
        boolean usingCachedBatch = !remainingProfiles.isEmpty();
        if (!usingCachedBatch) {
            appendAutoHistory("AUTO_REFRESH",
                    "همه پروفایل‌های فعلی بررسی شدند؛ دریافت فهرست تازه آغاز می‌شود");
            updateStatus("فهرست فعلی تمام شد؛ در حال دریافت سرورهای تازه…");
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("session_start_result",
                            "تعداد گزینه‌های دارای آزمون کیفیت: " + measured
                                    + "؛ دریافت فهرست تازه…")
                    .apply();
            nextProfiles = fetchFreshProfiles(40);
        } else {
            appendAutoHistory("AUTO_REUSE_REMAINING",
                    remainingProfiles.size()
                            + " پروفایل آزمایش‌نشده از فهرست قبلی؛ بدون وابستگی به دریافت اینترنتی دوباره");
            updateStatus("در حال بررسی سرورهای باقی‌مانده از فهرست دریافت‌شده…");
        }
        if (!autoSearchEnabled || serviceDestroyed) return;
        if (nextProfiles.isEmpty()) {
            autoSearchEnabled = false;
            autoConnected = false;
            setLampState("disconnected");
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("session_start_result",
                            "سرور آزمایش‌نشده یا تازه‌ای در دسترس نیست؛ بعداً دوباره تلاش کنید.")
                    .apply();
            updateStatus("فهرست فعلی تمام شده و منبع دریافت سرور تازه پاسخ‌گو نیست؛ بعداً دوباره تلاش کنید.");
            appendAutoHistory("AUTO_REFRESH_EMPTY",
                    "هیچ پروفایل آزمایش‌نشده یا تازه‌ای برای ادامه وجود ندارد");
            return;
        }
        autoProfiles = new ArrayList<>(nextProfiles);
        appendAutoHistory(usingCachedBatch ? "AUTO_REUSE_READY" : "AUTO_REFRESH_READY",
                nextProfiles.size() + (usingCachedBatch
                        ? " سرور باقی‌مانده از فهرست قبلی آماده آزمایش است"
                        : " سرور تازه دریافت شد؛ نوبت بعدی در صف سرویس قرار گرفت"));

        // Do not recurse from runAutoSearch(). This method already runs on the
        // single-thread session executor; direct recursion would grow the Java
        // stack forever when every batch fails. Queue the next batch so this
        // invocation can return and the executor can process stop requests.
        final ArrayList<String> queuedProfiles = new ArrayList<>(nextProfiles);
        try {
            sessionExecutor.execute(() -> {
                if (!autoSearchEnabled || serviceDestroyed) return;
                runAutoSearch(queuedProfiles, 0, startId);
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            autoSearchEnabled = false;
            autoConnected = false;
            setLampState("disconnected");
            appendAutoHistory("AUTO_REFRESH_QUEUE_FAILED",
                    "صف اجرای جست‌وجوی بعدی در دسترس نبود؛ جست‌وجو متوقف شد");
            updateStatus("ادامه جست‌وجوی خودکار ممکن نشد؛ دوباره اتصال هوشمند را بزنید.");
        }
    }

    /**
     * Short burst stability check through the active VPN tunnel.
     * Four of five independent HTTPS 204 requests must succeed.
     */
    private int measureTunnelStability() {
        String[] probes = new String[] {
                "https://connectivitycheck.gstatic.com/generate_204",
                "https://cp.cloudflare.com/generate_204",
                "https://connectivitycheck.gstatic.com/generate_204",
                "https://cp.cloudflare.com/generate_204",
                "https://connectivitycheck.gstatic.com/generate_204"
        };
        int successes = 0;
        for (int i = 0; i < probes.length; i++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(probes[i]).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                if (connection.getResponseCode() == 204) successes++;
            } catch (IOException | RuntimeException ignored) {
                // A failed sample reduces the stability score.
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (i < probes.length - 1) {
                try {
                    Thread.sleep(350L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return successes;
                }
            }
        }
        return successes;
    }

    /**
     * Measure a small, bounded download through the active VPN tunnel.
     * This is a rough comparison signal, not a full speed-test result.
     * Returns kilobits per second, or -1 when the probe is unavailable.
     */
    private long measureTunnelDownloadKbps() {
        HttpURLConnection connection = null;
        long bytesRead = 0L;
        long startedAt = System.nanoTime();
        try {
            URL url = new URL("https://speed.cloudflare.com/__down?bytes=131072");
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(6000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return -1L;
            try (java.io.InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    bytesRead += count;
                    if (bytesRead >= 256L * 1024L) break;
                }
            }
            long elapsedNanos = System.nanoTime() - startedAt;
            if (bytesRead < 16L * 1024L || elapsedNanos <= 0L) return -1L;
            double seconds = elapsedNanos / 1_000_000_000.0;
            return Math.max(1L, Math.round(bytesRead * 8.0 / seconds / 1000.0));
        } catch (IOException | RuntimeException ignored) {
            return -1L;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Three HTTPS 204 probes through the active VPN tunnel.
     * Returns the mean successful latency; at least two probes must succeed.
     */
    private long measureTunnelLatencyMs() {
        String[] probes = new String[] {
                "https://connectivitycheck.gstatic.com/generate_204",
                "https://cp.cloudflare.com/generate_204",
                "https://connectivitycheck.gstatic.com/generate_204"
        };
        long totalMs = 0L;
        int successes = 0;
        for (String probe : probes) {
            HttpURLConnection connection = null;
            long startedAt = System.nanoTime();
            try {
                connection = (HttpURLConnection) new URL(probe).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(4500);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                if (connection.getResponseCode() == 204) {
                    totalMs += TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - startedAt);
                    successes++;
                }
            } catch (IOException | RuntimeException ignored) {
                // A failed probe is not counted as a latency success.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return successes >= 2 ? totalMs / successes : -1L;
    }

    /**
     * Verify ordinary app traffic while Android's VPN routes are active.
     * These requests are intentionally NOT protected(), so a full-tunnel
     * profile must carry them through the VPN interface.
     */
    private boolean verifyTunnelTraffic(String baselineExitIp) {
        if (baselineExitIp == null || baselineExitIp.isEmpty()) {
            appendAutoHistory("AUTO_EXIT_IP_UNVERIFIED",
                    "IP پیش از اتصال ثبت نشده؛ برای جلوگیری از تأیید اشتباه، این سرور پذیرفته نشد");
            return false;
        }
        String[] probes = new String[] {
                "https://connectivitycheck.gstatic.com/generate_204",
                "https://cp.cloudflare.com/generate_204"
        };
        boolean httpsTrafficOk = false;
        for (String probe : probes) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(probe).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                int code = connection.getResponseCode();
                if (code == 204) {
                    httpsTrafficOk = true;
                    appendAutoHistory("AUTO_TUNNEL_PROBE_OK",
                            "پاسخ HTTP ۲۰۴ از آزمون اینترنت در زمان فعال بودن تونل دریافت شد");
                    break;
                }
                appendAutoHistory("AUTO_TUNNEL_PROBE_RESPONSE",
                        "آزمون اینترنت پاسخ HTTP " + code + " داد");
            } catch (IOException | RuntimeException e) {
                appendAutoHistory("AUTO_TUNNEL_PROBE_ERROR",
                        "آزمون اینترنت ناموفق: " + e.getClass().getSimpleName());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        if (!httpsTrafficOk) return false;

        String tunnelExitIp = fetchPublicIp();
        if (tunnelExitIp == null || tunnelExitIp.isEmpty()) {
            appendAutoHistory("AUTO_EXIT_IP_UNVERIFIED",
                    "IP خروجی در زمان تونل قابل دریافت نبود؛ اتصال تأیید نشد");
            return false;
        }
        if (baselineExitIp.equals(tunnelExitIp)) {
            appendAutoHistory("AUTO_EXIT_IP_UNCHANGED",
                    "IP خروجی با IP پیش از تونل تفاوت نداشت؛ احتمال عبور نکردن ترافیک از رله");
            return false;
        }
        activeTunnelExitIp = tunnelExitIp;
        appendAutoHistory("AUTO_EXIT_IP_CHANGED",
                "IP خروجی در زمان تونل با IP پیش از اتصال متفاوت است");
        return true;
    }

    /** Fetch a literal public IP over ordinary, unprotected HTTPS traffic. */
    private String fetchPublicIp() {
        String[] endpoints = new String[] {
                "https://api.ipify.org",
                "https://icanhazip.com"
        };
        for (String endpoint : endpoints) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(4500);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                if (connection.getResponseCode() != 200) continue;
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(connection.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    String value = reader.readLine();
                    if (value != null) {
                        value = value.trim();
                        if (value.length() <= 45
                                && value.matches("[0-9a-fA-F:.]+")
                                && (value.indexOf('.') >= 0 || value.indexOf(':') >= 0)) {
                            return value;
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Try the second independent endpoint.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return null;
    }

    /**
     * Refresh candidates through the same strict parser used by initial
     * discovery. Do not parse a mirror independently here: that would create
     * a weaker validation path for profiles received after the first batch.
     */
    private ArrayList<String> fetchFreshProfiles(int wanted) {
        ArrayList<String> profiles = new ArrayList<>();
        if (wanted <= 0) return profiles;
        try {
            VpnGateProvider.Result result = VpnGateProvider.fetch();
            for (VpnGateProvider.Server server : result.servers) {
                if (profiles.size() >= wanted) break;
                String endpoint = server.endpointKey();
                synchronized (triedServerEndpoints) {
                    if (endpoint.isEmpty() || triedServerEndpoints.contains(endpoint)) {
                        continue;
                    }
                }
                profiles.add(server.openVpnProfile);
            }
            appendAutoHistory("AUTO_REFRESH_SOURCE",
                    "دریافت تازه از منبع رسمی VPN Gate؛ پروفایل معتبر: "
                            + profiles.size());
        } catch (IOException | RuntimeException error) {
            appendAutoHistory("AUTO_REFRESH_SOURCE_FAILED",
                    "دریافت یا اعتبارسنجی منبع رسمی ناموفق بود: "
                            + error.getClass().getSimpleName());
        }
        return profiles;
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

    private String redactDiagnosticText(String value) {
        if (value == null || value.isEmpty()) return "";
        // Diagnostic reports must never expose credentials or private keys.
        return value.replaceAll(
                "(?i)(password|passwd|auth[_-]?token|private[_ -]?key|secret|token|username)"
                        + "\\s*[:=]\\s*[^\\s,;]+",
                "$1=<redacted>");
    }

    private void appendAutoHistory(String name, String info) {
        android.content.SharedPreferences diagnostics =
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
        String history = diagnostics.getString("native_event_history", "");
        String safeInfo = redactDiagnosticText(
                info == null ? "" : info.replace('\n', ' ').replace('\r', ' '));
        String timestamp = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date());
        String detail = timestamp + " | " + name + " — " + safeInfo;
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

    private void cancelQualityMonitor() {
        java.util.concurrent.ScheduledFuture<?> task = qualityMonitorFuture;
        qualityMonitorFuture = null;
        if (task != null) task.cancel(false);
    }

    private void startQualityMonitor() {
        cancelQualityMonitor();
        if (!autoSearchEnabled || !autoConnected || stopRequested.get()
                || serviceDestroyed || qualityMonitorExecutor.isShutdown()) return;
        try {
            qualityMonitorFuture = qualityMonitorExecutor.scheduleWithFixedDelay(
                    this::monitorTunnelQuality,
                    AUTO_MONITOR_INTERVAL_SECONDS,
                    AUTO_MONITOR_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
            appendAutoHistory("AUTO_MONITOR_STARTED",
                    "پایش کیفیت تونل پس از تأیید اتصال آغاز شد؛ فاصله نمونه‌برداری ۴۵ ثانیه");
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            appendAutoHistory("AUTO_MONITOR_START_FAILED",
                    "صف پایش کیفیت در دسترس نیست");
        }
    }

    private void monitorTunnelQuality() {
        if (!autoSearchEnabled || !autoConnected || stopRequested.get()
                || serviceDestroyed || failoverQueued.get()) return;
        int sampleNumber = ++monitorSampleCount;
        long latency = measureTunnelLatencyMs();
        int stable = measureTunnelStability();
        // Run the bounded download probe every third cycle to limit background data use.
        long speed = sampleNumber % 3 == 0 ? measureTunnelDownloadKbps() : -1L;
        if (!autoSearchEnabled || !autoConnected || stopRequested.get()
                || serviceDestroyed) return;

        activeTunnelLatencyMs = latency;
        if (speed > 0) {
            activeTunnelThroughputKbps = speed;
            if (speed < AUTO_MONITOR_SPEED_LIMIT_KBPS) monitorLowSpeedSamples++;
            else monitorLowSpeedSamples = 0;
        }
        String sample = "نمونه " + sampleNumber + "؛ تأخیر=" + latency
                + "ms؛ پایداری=" + stable + "/5؛ دریافت="
                + (speed > 0 ? speed + "kbps" : "اندازه‌گیری نشد");
        appendAutoHistory("AUTO_MONITOR_SAMPLE", sample);
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putLong("auto_monitor_last_at", System.currentTimeMillis())
                .putLong("auto_monitor_latency_ms", latency)
                .putInt("auto_monitor_stability", stable)
                .putLong("auto_monitor_download_kbps", speed)
                .putInt("auto_monitor_bad_samples", monitorBadSamples)
                .commit();

        boolean poorLatency = latency < 0L || latency > AUTO_MONITOR_LATENCY_LIMIT_MS;
        boolean poorStability = stable < 4;
        boolean poorSpeed = monitorLowSpeedSamples >= 2;
        boolean degraded = poorLatency || poorStability;
        if (degraded) {
            monitorBadSamples++;
            appendAutoHistory("AUTO_QUALITY_DEGRADED",
                    "افت کیفیت در نمونه " + sampleNumber + "؛ شمار نمونه‌های نامطلوب متوالی="
                            + monitorBadSamples);
        } else {
            monitorBadSamples = 0;
        }
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putInt("auto_monitor_bad_samples", monitorBadSamples).apply();

        if (monitorBadSamples >= AUTO_MONITOR_BAD_SAMPLES_TO_SWITCH || poorSpeed) {
            String reason = "افت کیفیت در دو نمونه متوالی؛ تأخیر " + latency
                    + "ms، پایداری " + stable + "/5، سرعت "
                    + (speed > 0 ? speed + "kbps" : "نمونه‌گیری نشده");
            requestAutoFailover(reason);
        } else if (sampleNumber == 1 || sampleNumber % 3 == 0) {
            updateStatus("اتصال برقرار است؛ پایش هوشمند کیفیت — تأخیر "
                    + (latency >= 0 ? latency + " ms" : "نامشخص")
                    + "؛ پایداری " + stable + "/5"
                    + (speed > 0 ? "؛ دریافت " + speed + " kbps" : ""));
        }
    }

    private void requestAutoFailover(String reason) {
        if (!autoSearchEnabled || !autoConnected || stopRequested.get()
                || serviceDestroyed || !failoverQueued.compareAndSet(false, true)) return;
        long now = System.currentTimeMillis();
        boolean urgentDisconnect = reason != null && reason.startsWith("قطع غیرمنتظره");
        if (autoFailoversThisSession >= AUTO_MAX_FAILOVERS_PER_SESSION) {
            failoverQueued.set(false);
            cancelQualityMonitor();
            appendAutoHistory("AUTO_FAILOVER_SESSION_LIMIT",
                    "سقف دو تعویض خودکار این نشست تکمیل شد");
            if (urgentDisconnect) {
                autoConnected = false;
                autoSearchEnabled = false;
                setLampState("disconnected");
                updateStatus("اتصال VPN قطع شد و سقف تعویض خودکار این نشست تکمیل شده است؛ برای تلاش تازه، اتصال هوشمند را بزنید.");
            } else {
                updateStatus("کیفیت اتصال پایین است؛ سقف تعویض خودکار این نشست تکمیل شد و اتصال فعلی حفظ می‌شود.");
            }
            return;
        }
        if (!urgentDisconnect && lastAutoFailoverAtMs > 0L
                && now - lastAutoFailoverAtMs < AUTO_FAILOVER_COOLDOWN_MS) {
            failoverQueued.set(false);
            appendAutoHistory("AUTO_FAILOVER_COOLDOWN",
                    "برای جلوگیری از جابه‌جایی پیاپی، تعویض خودکار در دوره مکث نادیده گرفته شد");
            monitorBadSamples = 0;
            monitorLowSpeedSamples = 0;
            startQualityMonitor();
            return;
        }
        cancelQualityMonitor();
        try {
            sessionExecutor.execute(() -> {
                try {
                    if (!autoSearchEnabled || serviceDestroyed || stopRequested.get()) return;
                    runAutoFailover(reason);
                } finally {
                    failoverQueued.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            failoverQueued.set(false);
            appendAutoHistory("AUTO_FAILOVER_QUEUE_CLOSED",
                    "صف سرویس بسته شده بود؛ تعویض خودکار آغاز نشد");
        }
    }

    private boolean connectProfileForFailover(String profile) {
        if (profile == null || profile.trim().isEmpty() || isSessionCancelled()) return false;
        CountDownLatch signal = new CountDownLatch(1);
        attemptConnected = false;
        attemptSignal = signal;
        String result;
        synchronized (nativeSessionLock) {
            if (isSessionCancelled()) {
                attemptSignal = null;
                return false;
            }
            stopNativeSession();
            result = OpenVpn3Bridge.startSession(profile);
        }
        boolean accepted = result != null
                && result.startsWith("درخواست اتصال به هسته OpenVPN 3 تحویل شد");
        boolean connected = false;
        if (accepted) {
            try {
                connected = signal.await(AUTO_ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        && attemptConnected;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        attemptSignal = null;
        return connected && !isSessionCancelled();
    }

    private boolean verifyTunnelTrafficForFailover(String previousExitIp,
                                                    boolean requireDifferentExitIp) {
        if (isSessionCancelled()) return false;
        String[] probes = new String[] {
                "https://connectivitycheck.gstatic.com/generate_204",
                "https://cp.cloudflare.com/generate_204"
        };
        boolean trafficOk = false;
        for (String probe : probes) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(probe).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                if (connection.getResponseCode() == 204) {
                    trafficOk = true;
                    break;
                }
            } catch (IOException | RuntimeException ignored) {
                // Try the second independent endpoint.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        if (!trafficOk) return false;
        String exitIp = fetchPublicIp();
        if (exitIp == null || exitIp.isEmpty()) return false;
        if (requireDifferentExitIp && previousExitIp != null
                && previousExitIp.equals(exitIp)) return false;
        activeTunnelExitIp = exitIp;
        return true;
    }

    private void runAutoFailover(String reason) {
        if (!autoSearchEnabled || serviceDestroyed || stopRequested.get()) return;
        autoConnected = false;
        final String previousProfile = activeAutoProfile;
        final String previousEndpoint = profileEndpoint(previousProfile);
        final String previousExitIp = activeTunnelExitIp;
        if (previousProfile == null || previousProfile.trim().isEmpty()) {
            autoSearchEnabled = false;
            setLampState("disconnected");
            appendAutoHistory("AUTO_FAILOVER_NO_ROLLBACK",
                    "پروفایل اتصال قبلی برای بازیابی در دسترس نیست؛ تعویض خودکار انجام نشد");
            updateStatus("پروفایل قبلی برای بازیابی در دسترس نیست؛ برای اتصال دوباره، اتصال هوشمند را بزنید.");
            return;
        }
        autoFailoversThisSession++;
        lastAutoFailoverAtMs = System.currentTimeMillis();
        setLampState("searching");
        appendAutoHistory("AUTO_FAILOVER_START",
                reason + "؛ سرور قبلی " + previousEndpoint
                        + "؛ نوبت تعویض " + autoFailoversThisSession
                        + " از " + AUTO_MAX_FAILOVERS_PER_SESSION);
        updateStatus("کیفیت اتصال افت کرده است؛ در حال بررسی حداکثر سه سرور جایگزین…");

        ArrayList<String> profiles = autoProfiles;
        if (profiles == null || profiles.isEmpty()) profiles = new ArrayList<>();
        String bestProfile = null;
        String bestEndpoint = "";
        long bestLatency = Long.MAX_VALUE;
        long bestSpeed = -1L;
        double bestScore = Double.MAX_VALUE;
        int tested = 0;
        int count = profiles.size();
        int start = count == 0 ? 0 : Math.max(0, nextAutoIndex % count);

        for (int offset = 0; offset < count && tested < AUTO_FAILOVER_CANDIDATES; offset++) {
            if (isSessionCancelled()) return;
            int index = (start + offset) % count;
            String candidate = profiles.get(index);
            String endpoint = profileEndpoint(candidate);
            if (candidate == null || candidate.trim().isEmpty()
                    || endpoint.isEmpty() || endpoint.equals(previousEndpoint)) continue;
            synchronized (triedServerEndpoints) {
                if (triedServerEndpoints.contains(endpoint)) continue;
                triedServerEndpoints.add(endpoint);
            }
            tested++;
            appendAutoHistory("AUTO_FAILOVER_CANDIDATE",
                    "آزمون جایگزین " + tested + " از " + AUTO_FAILOVER_CANDIDATES
                            + "؛ " + endpoint);
            if (!connectProfileForFailover(candidate)) {
                appendAutoHistory("AUTO_FAILOVER_CANDIDATE_FAILED",
                        endpoint + "؛ رویداد اتصال در مهلت مقرر تأیید نشد");
                continue;
            }
            if (!verifyTunnelTrafficForFailover(previousExitIp, true)) {
                appendAutoHistory("AUTO_FAILOVER_CANDIDATE_FAILED",
                        endpoint + "؛ عبور اینترنت یا تغییر IP خروجی تأیید نشد");
                synchronized (nativeSessionLock) { stopNativeSession(); }
                continue;
            }
            int stable = measureTunnelStability();
            long latency = measureTunnelLatencyMs();
            long speed = measureTunnelDownloadKbps();
            if (stable < 4 || latency < 0L) {
                appendAutoHistory("AUTO_FAILOVER_CANDIDATE_FAILED",
                        endpoint + "؛ کیفیت کافی نبود؛ پایداری " + stable
                                + "/5 و تأخیر " + latency + "ms");
                synchronized (nativeSessionLock) { stopNativeSession(); }
                continue;
            }
            double score = latency + (5 - stable) * 250.0
                    + (speed > 0 ? 1000.0 / Math.max(0.1, speed / 1000.0) : 0.0);
            if (score < bestScore) {
                bestScore = score;
                bestProfile = candidate;
                bestEndpoint = endpoint;
                bestLatency = latency;
                bestSpeed = speed;
            }
            synchronized (nativeSessionLock) { stopNativeSession(); }
        }

        if (isSessionCancelled()) return;
        if (bestProfile != null && connectProfileForFailover(bestProfile)
                && verifyTunnelTrafficForFailover(previousExitIp, true)
                && measureTunnelStability() >= 4) {
            autoConnected = true;
            activeAutoProfile = bestProfile;
            activeTunnelLatencyMs = bestLatency;
            activeTunnelThroughputKbps = bestSpeed;
            nextAutoIndex = count == 0 ? 0
                    : (profiles.indexOf(bestProfile) + 1) % count;
            setServerTestStatus(bestEndpoint, "connected");
            setLampState("connected");
            appendAutoHistory("AUTO_FAILOVER_SUCCESS",
                    "اتصال به سرور جایگزین " + bestEndpoint
                            + " تأیید شد؛ تأخیر " + bestLatency + "ms؛ سرعت "
                            + (bestSpeed > 0 ? bestSpeed + "kbps" : "نامشخص"));
            updateStatus("سرور جایگزین انتخاب شد — " + bestEndpoint
                    + "؛ تأخیر " + bestLatency + " ms"
                    + (bestSpeed > 0 ? "؛ دریافت " + bestSpeed + " kbps" : ""));
            monitorBadSamples = 0;
            startQualityMonitor();
            return;
        }

        if (isSessionCancelled()) return;
        appendAutoHistory("AUTO_FAILOVER_ROLLBACK",
                "جایگزین مناسبی پیدا نشد؛ تلاش برای بازیابی سرور قبلی " + previousEndpoint);
        if (connectProfileForFailover(previousProfile)
                && verifyTunnelTrafficForFailover(null, false)) {
            autoConnected = true;
            activeAutoProfile = previousProfile;
            setLampState("connected");
            appendAutoHistory("AUTO_FAILOVER_ROLLBACK_OK",
                    "اتصال قبلی " + previousEndpoint + " بازیابی و عبور اینترنت تأیید شد");
            updateStatus("سرور جایگزین مناسبی پیدا نشد؛ اتصال قبلی بازیابی شد — "
                    + previousEndpoint);
            monitorBadSamples = 0;
            startQualityMonitor();
        } else {
            autoConnected = false;
            autoSearchEnabled = false;
            activeAutoProfile = null;
            setLampState("disconnected");
            appendAutoHistory("AUTO_FAILOVER_ROLLBACK_FAILED",
                    "بازیابی سرور قبلی نیز تأیید نشد؛ برای جلوگیری از تلاش بی‌پایان، نشست متوقف شد");
            updateStatus("تعویض سرور موفق نبود و اتصال قبلی هم بازیابی نشد؛ برای تلاش تازه، اتصال هوشمند را بزنید.");
        }
    }

    private void scheduleAutoFailover() {
        if (!autoSearchEnabled || !autoConnected) return;
        requestAutoFailover("قطع غیرمنتظره نشست VPN");
    }

    private void recordLifecycleEvent(String key, Intent intent) {
        String action = intent == null ? "null" : String.valueOf(intent.getAction());
        String stamp = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date());
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString(key, stamp + " (action=" + action + ")")
                .commit();
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        recordLifecycleEvent("service_on_bind_entered_at", intent);
        android.util.Log.i("RahYabVpnService", "onBind entered; action="
                + (intent == null ? "null" : intent.getAction()));
        return super.onBind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        recordLifecycleEvent("service_on_unbind_entered_at", intent);
        android.util.Log.i("RahYabVpnService", "onUnbind entered; action="
                + (intent == null ? "null" : intent.getAction()));
        return super.onUnbind(intent);
    }

    @Override
    public void onRebind(Intent intent) {
        recordLifecycleEvent("service_on_rebind_entered_at", intent);
        android.util.Log.i("RahYabVpnService", "onRebind entered; action="
                + (intent == null ? "null" : intent.getAction()));
        super.onRebind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        android.content.SharedPreferences diagnostics =
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
        String enteredAt = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date());
        // Record callback entry before foreground promotion so even a failure
        // inside startForeground() remains distinguishable from no service creation.
        diagnostics.edit().putString("service_on_create_entered_at", enteredAt).commit();
        try {
            enterForeground();
            diagnostics.edit()
                    .putString("service_on_create_result", "موفق؛ سرویس به مرحله ایجاد رسید و پیش‌زمینه شد")
                    .commit();
        } catch (RuntimeException error) {
            String detail = error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "بدون توضیح" : error.getMessage());
            diagnostics.edit()
                    .putString("service_on_create_result", "ناموفق در پیش‌زمینه‌سازی")
                    .putString("service_start_error", detail)
                    .commit();
            stopSelf();
            return;
        }

        // Recovery path: record every decision before attempting a one-shot replay.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            android.content.SharedPreferences pending =
                    getSharedPreferences(PENDING_START_PREFS, MODE_PRIVATE);
            android.content.SharedPreferences diagnosticsNow =
                    getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
            String requestId = pending.getString(PENDING_START_ID, null);
            String profilesJson = pending.getString(PENDING_START_PROFILES, null);
            long createdAt = pending.getLong(PENDING_START_CREATED_AT, 0L);
            long requestAge = System.currentTimeMillis() - createdAt;
            String claimedId = diagnosticsNow.getString("service_claimed_request_id", null);
            String cancelledId = diagnosticsNow.getString(
                    "service_start_cancelled_request_id", null);
            diagnosticsNow.edit()
                    .putString("service_recovery_handler_ran_at",
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                                    java.util.Locale.US).format(new java.util.Date()))
                    .putString("service_recovery_pending_request_id", String.valueOf(requestId))
                    .putString("service_recovery_claimed_request_id", String.valueOf(claimedId))
                    .putString("service_recovery_profiles_present",
                            String.valueOf(profilesJson != null && !profilesJson.trim().isEmpty()))
                    .putLong("service_recovery_request_age_ms", requestAge)
                    .commit();
            if (requestId == null || requestId.trim().isEmpty()
                    || profilesJson == null || profilesJson.trim().isEmpty()) {
                diagnosticsNow.edit().putString("service_start_recovery",
                        "skipped: no pending request/profiles").commit();
                return;
            }
            if (createdAt <= 0L || requestAge < 0L
                    || requestAge > PENDING_START_MAX_AGE_MS) {
                pending.edit().clear().commit();
                diagnosticsNow.edit().putString("service_start_recovery",
                        "skipped: pending request expired or invalid").commit();
                return;
            }
            if (requestId.equals(cancelledId)) {
                pending.edit().clear().commit();
                diagnosticsNow.edit().putString("service_start_recovery",
                        "skipped: pending request was cancelled").commit();
                return;
            }
            if (requestId.equals(claimedId)) {
                pending.edit().clear().commit();
                diagnosticsNow.edit().putString("service_start_recovery",
                        "skipped: request already claimed").commit();
                return;
            }
            try {
                org.json.JSONArray json = new org.json.JSONArray(profilesJson);
                ArrayList<String> profiles = new ArrayList<>();
                for (int i = 0; i < json.length(); i++) {
                    String item = json.optString(i, "");
                    if (!item.trim().isEmpty()) profiles.add(item);
                }
                diagnosticsNow.edit().putInt("service_recovery_profile_count",
                        profiles.size()).commit();
                if (profiles.isEmpty()) {
                    diagnosticsNow.edit().putString("service_start_recovery",
                            "skipped: parsed profile list empty").commit();
                    return;
                }
                Intent replay = new Intent(this, RahYabVpnService.class);
                replay.setAction(ACTION_START);
                replay.putExtra(EXTRA_REQUEST_ID, requestId);
                replay.putStringArrayListExtra(EXTRA_PROFILES, profiles);
                diagnosticsNow.edit()
                        .putString("service_start_recovery", "onCreate delayed replay; explicit request still valid")
                        .putString("service_start_recovery_request_id", requestId)
                        .commit();
                onStartCommand(replay, 0, 1);
            } catch (Exception error) {
                diagnosticsNow.edit()
                        .putString("service_start_recovery_error",
                                error.getClass().getSimpleName() + ": "
                                        + String.valueOf(error.getMessage()))
                        .commit();
            }
        }, 1200L);
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
        // Enforce full-tunnel routing before Android creates the interface.
        // If a profile enables an address family without its default route,
        // or omits VPN DNS, do not risk traffic/DNS falling back to the base network.
        FullTunnelPolicy.validate(addresses, dnsServers, routeAddresses,
                routePrefixes, excludedRouteAddresses);

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

    private boolean isSessionCancelled() {
        return stopRequested.get() || !autoSearchEnabled || serviceDestroyed;
    }

    private void stopNativeSession() {
        nativeStopInProgress.set(true);
        try {
            OpenVpn3Bridge.stopSession();
        } finally {
            nativeStopInProgress.set(false);
        }
    }

    private void setLampState(String state) {
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("connection_lamp_state", state).apply();
    }

    private void updateStatus(String message) {
        // Persist the latest service state so the activity can display it when
        // it is visible again; notification updates remain foreground-only.
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("service_status_message", message)
                .putLong("service_status_updated_at", System.currentTimeMillis())
                .apply();
        if (!foregroundStarted) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildStatusNotification(message));
    }

    private static String formatDiagnosticTime(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                java.util.Locale.US).format(new java.util.Date(millis));
    }

    private static String profileProtocol(String profile) {
        if (profile == null || profile.trim().isEmpty()) return "نامشخص";
        // Read only active OpenVPN directives. A commented example such as
        // "# proto udp" must never override the actual "proto tcp-client".
        String configured = "نامشخص";
        for (String rawLine : profile.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2 || !"proto".equalsIgnoreCase(parts[0])) continue;
            String value = parts[1].toLowerCase(java.util.Locale.ROOT);
            if (value.startsWith("udp")) configured = "UDP";
            else if (value.startsWith("tcp")) configured = "TCP";
        }
        return configured;
    }

    private static String classifyNativeFailure(String eventName, String lowerInfo,
                                                boolean error) {
        // OpenVPN options routinely contain "tls-client". That is configuration,
        // not evidence of a TLS failure. Only classify explicit failure messages.
        if (lowerInfo.contains("tls error") || lowerInfo.contains("tls handshake failed")
                || lowerInfo.contains("tls key negotiation failed")
                || lowerInfo.contains("handshake failed")) return "TLS_HANDSHAKE";
        if (lowerInfo.contains("auth_failed") || lowerInfo.contains("authentication failed")
                || lowerInfo.contains("auth failure")) return "AUTHENTICATION";
        if (lowerInfo.contains("no route to host") || lowerInfo.contains("network is unreachable"))
            return "NO_ROUTE";
        if (lowerInfo.contains("connection refused")) return "CONNECTION_REFUSED";
        if (lowerInfo.contains("timed out") || lowerInfo.contains("timeout")
                || lowerInfo.contains("poll timeout")) return "TIMEOUT";
        if (lowerInfo.contains("resolve failed") || lowerInfo.contains("name or service not known")
                || lowerInfo.contains("unable to resolve")) return "DNS_RESOLUTION";
        if (lowerInfo.contains("reset by peer") || lowerInfo.contains("connection reset"))
            return "PEER_RESET";
        if (error && ("NATIVE_CONNECT_EXCEPTION".equals(eventName)
                || "NATIVE_CONNECT_RESULT".equals(eventName))) return "NATIVE_CONNECT_ERROR";
        if (error && ("CORE_LOG".equals(eventName) || "CORE_ERROR".equals(eventName)))
            return "NATIVE_CORE_ERROR";
        if ("DISCONNECTED".equals(eventName) || "EXITING".equals(eventName))
            return "DISCONNECTED_WITHOUT_DETAIL";
        return "";
    }

    private static String nativeTransportProtocol(String lowerInfo) {
        if (lowerInfo.contains(" via tcp") || lowerInfo.contains(" via tcp4")
                || lowerInfo.contains(" via tcp6")) return "TCP";
        if (lowerInfo.contains(" via udp") || lowerInfo.contains(" via udp4")
                || lowerInfo.contains(" via udp6")) return "UDP";
        return "";
    }

    /** Receive OpenVPN core events on its worker thread and reflect them in the foreground notification. */
    public void onNativeEvent(String name, String info, boolean error, boolean fatal) {
        // Native shutdown can emit a final callback while the service is being
        // revoked/destroyed. Do not touch Android state after teardown begins.
        if (serviceDestroyed) return;
        if (name == null) name = "UNKNOWN";
        // Keep each OpenVPN callback as one history entry. Some CORE_LOG
        // messages contain embedded newlines (for example Peer Info); without
        // normalization those lines consume the history limit and hide the
        // actual sequence of connection events.
        String safeInfo = redactDiagnosticText(
                info == null ? "" : info.replace('\r', ' ').replace('\n', ' ').trim());
        if (safeInfo.length() > 1200) safeInfo = safeInfo.substring(0, 1200);
        String timestamp = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date());
        String detail = timestamp + " | " + name
                + (safeInfo.isEmpty() ? "" : " — " + safeInfo)
                + (error || fatal ? " [ERROR]" : "");
        android.content.SharedPreferences diagnostics =
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
        String lowerInfo = safeInfo.toLowerCase(java.util.Locale.ROOT);
        String failureClass = classifyNativeFailure(name, lowerInfo, error || fatal);
        boolean disconnectEvent = "DISCONNECTED".equals(name) || "EXITING".equals(name);
        // Native stop() itself emits DISCONNECTED. Suppress that lifecycle callback
        // for user stops and for internal per-candidate timeout/cleanup.
        boolean lifecycleStopEvent = disconnectEvent
                && (stopRequested.get() || nativeStopInProgress.get()
                || !autoSearchEnabled || serviceDestroyed);
        if (lifecycleStopEvent) failureClass = "";
        String observedTransport = nativeTransportProtocol(lowerInfo);
        if (!observedTransport.isEmpty()) {
            diagnostics.edit()
                    .putString("native_observed_transport_protocol", observedTransport)
                    .putString("native_observed_transport_at", timestamp)
                    .putString("native_observed_transport_detail", safeInfo)
                    .apply();
        }
        android.content.SharedPreferences.Editor eventEditor = diagnostics.edit()
                .putString("native_last_event_at", timestamp)
                .putString("native_last_event_name", name)
                .putString("native_last_event_detail", safeInfo);
        if (!failureClass.isEmpty()) {
            eventEditor.putString("native_last_failure_class", failureClass)
                    .putString("native_last_failure_at", timestamp)
                    .putString("native_last_failure_event", name)
                    .putString("native_last_failure_detail", safeInfo);
        }
        if ("CONNECTED".equals(name)) {
            eventEditor.putString("native_connected_event_at", timestamp);
        } else if ("DISCONNECTED".equals(name) || "EXITING".equals(name)) {
            eventEditor.putString("native_disconnected_event_at", timestamp)
                    .putString("native_disconnected_event_detail", safeInfo);
        }
        eventEditor.apply();
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
        if (!lifecycleStopEvent) {
            if ("CONNECTED".equals(name) && !error && !fatal) {
                // OpenVPN's CONNECTED event is not proof of working internet.
                // The automatic-search worker sets the connected lamp only
                // after an unprotected HTTP probe succeeds through Android VPN.
                if (autoSearchEnabled) setLampState("searching");
                else setLampState("connected");
            } else if (error || fatal || disconnectEvent) {
                setLampState("disconnected");
            } else if ("CONNECTING".equals(name) || "WAIT".equals(name)) {
                setLampState("searching");
            }
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
        if (!safeInfo.isEmpty() && (error || fatal)) {
            // Reuse the already-redacted, newline-normalized diagnostic text.
            // Raw native error details must never leak credentials into the
            // Android foreground notification.
            message += " — " + safeInfo;
        }
        if (foregroundStarted && !"CORE_LOG".equals(name) && !lifecycleStopEvent) {
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
        stopRequested.set(true);
        serviceDestroyed = true;
        autoSearchEnabled = false;
        qualityMonitorExecutor.shutdownNow();
        autoConnected = false;
        cancelQualityMonitor();
        autoProfiles = null;
        activeAutoProfile = null;
        CountDownLatch activeAttempt = attemptSignal;
        if (activeAttempt != null) activeAttempt.countDown();
        attemptSignal = null;
        setLampState("disconnected");
        try {
            sessionExecutor.execute(() -> {
                synchronized (nativeSessionLock) {
                    stopNativeSession();
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
        stopRequested.set(true);
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("service_on_destroy_at", new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                        .format(new java.util.Date()))
                .commit();
        // Mark the service unavailable before scheduling native teardown. JNI
        // callbacks may still arrive while stopSession() joins the worker.
        serviceDestroyed = true;
        autoSearchEnabled = false;
        autoConnected = false;
        autoProfiles = null;
        CountDownLatch activeAttempt = attemptSignal;
        if (activeAttempt != null) activeAttempt.countDown();
        attemptSignal = null;
        sessionExecutor.shutdownNow();
        qualityMonitorExecutor.shutdownNow();
        setLampState("disconnected");

        // OpenVPN's native stop() joins its worker and can take noticeable
        // time. Never perform that join on Android's main/service thread.
        // Keep the JNI global service reference alive until the worker has
        // stopped, then close the TUN and unbind callbacks in that order.
        if (foregroundStarted) {
            stopForeground(true);
            foregroundStarted = false;
        }
        Thread cleanup = new Thread(() -> {
            // Keep the lifecycle lock for the complete stop -> TUN close ->
            // JNI unbind sequence. Binding and session starts use this same
            // lock, so no late task can recreate native state after teardown.
            synchronized (nativeSessionLock) {
                try {
                    stopNativeSession();
                } catch (RuntimeException | LinkageError error) {
                    // Continue best-effort teardown even if the Java/JNI bridge
                    // reports a failure. The native stop wrapper normally joins
                    // the worker before returning.
                    android.util.Log.e("RahYabVpnService",
                            "OpenVPN native stop failed during service cleanup", error);
                } finally {
                    try {
                        closeTun();
                    } catch (RuntimeException error) {
                        android.util.Log.e("RahYabVpnService",
                                "TUN close failed during service cleanup", error);
                    } finally {
                        if (nativeCallbacksBound) {
                            try {
                                OpenVpn3Bridge.unbindTunService();
                            } catch (RuntimeException | LinkageError error) {
                                android.util.Log.e("RahYabVpnService",
                                        "OpenVPN JNI unbind failed during service cleanup", error);
                            } finally {
                                nativeCallbacksBound = false;
                            }
                        }
                    }
                }
            }
        }, "RahYab-openvpn-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
        super.onDestroy();
    }
}
