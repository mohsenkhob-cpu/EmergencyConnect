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
    private static final long AUTO_ATTEMPT_TIMEOUT_SECONDS = 30;
    private static final String CHANNEL_ID = "rah_yab_vpn_status";
    private static final int NOTIFICATION_ID = 7301;
    private ParcelFileDescriptor tunInterface;
    private volatile boolean nativeCallbacksBound;
    private String nativeBindDiagnostic = "NOT_INITIALIZED";
    private volatile boolean foregroundStarted;
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
        android.content.SharedPreferences diagnostics =
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
        String claimed = diagnostics.getString("service_claimed_request_id", null);
        if (requestId.equals(claimed)) return false;
        return diagnostics.edit()
                .putString("service_claimed_request_id", requestId)
                .putString("service_start_claimed_at",
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                                java.util.Locale.US).format(new java.util.Date()))
                .commit();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
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
        if (ACTION_START.equals(action) && requestId != null && !claimStartRequest(requestId)) {
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("service_start_duplicate_ignored", requestId)
                    .commit();
            return START_NOT_STICKY;
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
            autoSearchEnabled = false;
            autoConnected = false;
            autoProfiles = null;
            CountDownLatch activeAttempt = attemptSignal;
            if (activeAttempt != null) activeAttempt.countDown();
            attemptSignal = null;
            setLampState("disconnected");
            try {
                sessionExecutor.execute(() -> {
                    synchronized (nativeSessionLock) {
                        OpenVpn3Bridge.stopSession();
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
        // Test a small bounded group: measure actual tunnel traffic rather than
        // trusting VPN Gate's advertised ping/speed or accepting first success.
        final int candidatesToMeasure = Math.min(4, count);
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
            if (!autoSearchEnabled || serviceDestroyed) return;
            String profile = autoProfiles.get(i);
            String endpoint = profileEndpoint(profile);
            synchronized (triedServerEndpoints) {
                if (!endpoint.isEmpty()) triedServerEndpoints.add(endpoint);
            }
            CountDownLatch signal = new CountDownLatch(1);
            attemptConnected = false;
            String progress = "در حال آزمایش سرور " + (i + 1) + " از "
                    + candidatesToMeasure + (endpoint.isEmpty() ? "" : " — " + endpoint)
                    + "؛ بررسی اتصال و کیفیت";
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
            String attemptOutcome = attemptConnected ? "OPENVPN_CONNECTED_EVENT"
                    : callbackReceived ? "NATIVE_CALLBACK_WITHOUT_CONNECTED"
                    : "NATIVE_CALLBACK_TIMEOUT";
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("auto_last_attempt_endpoint", endpoint)
                    .putString("auto_last_attempt_protocol", profileProtocol(profile))
                    .putString("auto_last_attempt_started_at", formatDiagnosticTime(attemptStartedAt))
                    .putString("auto_last_attempt_finished_at", formatDiagnosticTime(System.currentTimeMillis()))
                    .putString("auto_last_attempt_outcome", attemptOutcome)
                    .putString("auto_last_attempt_connected_event", String.valueOf(attemptConnected))
                    .putString("auto_last_attempt_callback_received", String.valueOf(callbackReceived))
                    .putString("auto_current_wait_state", attemptOutcome)
                    .commit();
            appendAutoHistory("AUTO_ATTEMPT_RESULT",
                    "سرور " + endpoint + "؛ پروتکل " + profileProtocol(profile)
                            + "؛ نتیجه " + attemptOutcome
                            + "؛ رویداد اتصال " + attemptConnected
                            + "؛ دریافت callback " + callbackReceived);

            // A user stop/revoke may release the latch before the connection
            // attempt itself finishes. Do not run HTTPS/quality probes against
            // a tunnel that is being cancelled; stop it and leave cleanup to
            // the serialized lifecycle path.
            if (!autoSearchEnabled || serviceDestroyed) {
                synchronized (nativeSessionLock) {
                    OpenVpn3Bridge.stopSession();
                }
                setServerTestStatus(endpoint, "cancelled");
                appendAutoHistory("AUTO_ATTEMPT_CANCELLED",
                        "آزمون سرور به‌دلیل توقف یا خروج سرویس لغو شد");
                return;
            }

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
                OpenVpn3Bridge.stopSession();
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
                OpenVpn3Bridge.stopSession();
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
                    OpenVpn3Bridge.stopSession();
                }
                return;
            }
            if (connected && verifyTunnelTraffic(baselineExitIp)) {
                autoConnected = true;
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
                return;
            }
            appendAutoHistory("AUTO_BEST_RECONNECT_FAILED",
                    "اتصال مجدد به بهترین گزینه تأیید نشد؛ اتصال امن متوقف و جست‌وجو ادامه می‌یابد");
            synchronized (nativeSessionLock) {
                OpenVpn3Bridge.stopSession();
            }
        }

        if (!autoSearchEnabled || serviceDestroyed) return;
        appendAutoHistory("AUTO_REFRESH",
                "هیچ‌یک از " + candidatesToMeasure
                        + " گزینه اتصال نهایی قابل‌تأیید نداشتند؛ دریافت سرورهای تازه آغاز می‌شود");
        updateStatus("این گروه سرور نتیجه قابل‌قبول نداشت؛ دریافت فهرست تازه…");
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                .putString("session_start_result",
                        "تعداد گزینه‌های دارای آزمون کیفیت: " + measured
                                + "؛ در حال دریافت فهرست تازه…")
                .apply();
        ArrayList<String> freshProfiles = fetchFreshProfiles(40);
        if (!autoSearchEnabled || serviceDestroyed) return;
        if (freshProfiles.isEmpty()) {
            autoSearchEnabled = false;
            autoConnected = false;
            setLampState("disconnected");
            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
                    .putString("session_start_result",
                            "سرور تازه‌ای از منبع دریافت نشد؛ بعداً دوباره تلاش کنید.")
                    .apply();
            updateStatus("سرور تازه‌ای دریافت نشد؛ منبع فعلاً پاسخ‌گو نیست یا سرور جدیدی باقی نمانده است.");
            return;
        }
        autoProfiles = new ArrayList<>(freshProfiles);
        appendAutoHistory("AUTO_REFRESH_READY",
                freshProfiles.size() + " سرور تازه دریافت شد؛ نوبت بعدی در صف سرویس قرار گرفت");

        // Do not recurse from runAutoSearch(). This method already runs on the
        // single-thread session executor; direct recursion would grow the Java
        // stack forever when every batch fails. Queue the next batch so this
        // invocation can return and the executor can process stop requests.
        try {
            sessionExecutor.execute(() -> {
                if (!autoSearchEnabled || serviceDestroyed) return;
                runAutoSearch(freshProfiles, 0, startId);
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            autoSearchEnabled = false;
            autoConnected = false;
            setLampState("disconnected");
            appendAutoHistory("AUTO_REFRESH_QUEUE_FAILED",
                    "صف اجرای جست‌وجوی بعدی در دسترس نیست؛ جست‌وجو متوقف شد");
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

    private void scheduleAutoFailover() {
        if (!autoSearchEnabled || !autoConnected
                || !failoverQueued.compareAndSet(false, true)) return;
        autoConnected = false;
        try {
            sessionExecutor.execute(() -> {
                failoverQueued.set(false);
                if (!autoSearchEnabled || serviceDestroyed || autoProfiles == null) return;
                appendAutoHistory("AUTO_FAILOVER", "قطع نشست؛ جست‌وجوی سرور جایگزین آغاز شد");
                runAutoSearch(autoProfiles, nextAutoIndex, autoStartId);
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            failoverQueued.set(false);
            autoSearchEnabled = false;
            autoConnected = false;
            setLampState("disconnected");
            appendAutoHistory("AUTO_FAILOVER_QUEUE_CLOSED",
                    "صف سرویس بسته شده بود؛ جست‌وجوی جایگزین آغاز نشد");
        }
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
            String claimedId = diagnosticsNow.getString("service_claimed_request_id", null);
            diagnosticsNow.edit()
                    .putString("service_recovery_handler_ran_at",
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                                    java.util.Locale.US).format(new java.util.Date()))
                    .putString("service_recovery_pending_request_id", String.valueOf(requestId))
                    .putString("service_recovery_claimed_request_id", String.valueOf(claimedId))
                    .putString("service_recovery_profiles_present",
                            String.valueOf(profilesJson != null && !profilesJson.trim().isEmpty()))
                    .commit();
            if (requestId == null || requestId.trim().isEmpty()
                    || profilesJson == null || profilesJson.trim().isEmpty()) {
                diagnosticsNow.edit().putString("service_start_recovery",
                        "skipped: no pending request/profiles").commit();
                return;
            }
            if (requestId.equals(claimedId)) {
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
                        .putString("service_start_recovery", "onCreate delayed replay")
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
        if (profile == null) return "unknown";
        String lower = profile.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("proto udp")) return "UDP";
        if (lower.contains("proto tcp")) return "TCP";
        return "OpenVPN";
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
        if ("CONNECTED".equals(name) && !error && !fatal) {
            // OpenVPN's CONNECTED event is not proof of working internet.
            // The automatic-search worker sets the connected lamp only
            // after an unprotected HTTP probe succeeds through Android VPN.
            if (autoSearchEnabled) setLampState("searching");
            else setLampState("connected");
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
        if (!safeInfo.isEmpty() && (error || fatal)) {
            // Reuse the already-redacted, newline-normalized diagnostic text.
            // Raw native error details must never leak credentials into the
            // Android foreground notification.
            message += " — " + safeInfo;
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
        autoSearchEnabled = false;
        autoConnected = false;
        autoProfiles = null;
        CountDownLatch activeAttempt = attemptSignal;
        if (activeAttempt != null) activeAttempt.countDown();
        attemptSignal = null;
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
                    OpenVpn3Bridge.stopSession();
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
