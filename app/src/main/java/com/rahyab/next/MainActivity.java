package com.rahyab.next;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.net.Uri;
import android.net.VpnService;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import ir.emergencyconnect.engine.RahYabVpnService;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_CONFIG = 4101;
    private static final int REQUEST_VPN_PERMISSION = 4102;
    private static final int REQUEST_SINGBOX_PROFILE = 4103;
    private static final int REQUEST_SINGBOX_VPN_PERMISSION = 4104;
    private static final int REQUEST_OPENVPN_VPN_PERMISSION = 4105;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private GoBackend wireGuardBackend;
    private Config pendingWireGuardConfig;
    private String pendingSingBoxJson;
    private ArrayList<String> pendingOpenVpnProfiles;
    private Button singBoxConnectButton;
    private Button discoveryButton;
    private TextView status;
    private Button connectButton;
    private Button disconnectButton;
    private boolean singBoxReceiverRegistered = false;
    private static final String PREFS_SERVERS = "rahyab_saved_servers";
    private static final String KEY_SERVERS = "profiles";

    private final BroadcastReceiver singBoxReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !SingBoxVpnService.ACTION_EVENT.equals(intent.getAction())) return;
            String event = intent.getStringExtra(SingBoxVpnService.EXTRA_EVENT);
            String detail = intent.getStringExtra(SingBoxVpnService.EXTRA_DETAIL);
            if ("STARTING".equals(event)) {
                setStatus("در حال راه‌اندازی موتور sing-box…");
                singBoxConnectButton.setEnabled(false);
            } else if ("ENGINE_STARTED".equals(event)) {
                singBoxConnectButton.setEnabled(true);
                disconnectButton.setEnabled(true);
                verifyTunnel("sing-box");
            } else if ("TUN_ESTABLISHED".equals(event)) {
                setStatus("تونل اندروید ایجاد شد؛ بررسی عبور واقعی اینترنت لازم است");
                singBoxConnectButton.setEnabled(true);
                disconnectButton.setEnabled(true);
            } else if ("STOPPED".equals(event)) {
                // sing-box may be stopped intentionally while another VPN engine starts.
                // Do not overwrite the shared VPN status or disable its disconnect control here.
            } else if ("ERROR".equals(event)) {
                setStatus("خطای sing-box: " + (detail == null ? "نامشخص" : detail));
                singBoxConnectButton.setEnabled(true);
                disconnectButton.setEnabled(false);
            }
        }
    };

    private final Tunnel wireGuardTunnel = new Tunnel() {
        @Override public String getName() { return "RahYab-WireGuard"; }
        @Override public void onStateChange(State state) {
            runOnUiThread(() -> {
                if (state == State.UP) {
                    disconnectButton.setEnabled(true);
                    verifyTunnel("WireGuard");
                } else {
                    setStatus("تونل WireGuard خاموش شد");
                    disconnectButton.setEnabled(false);
                }
            });
        }
    };

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        v.setPadding(dp(8), dp(5), dp(8), dp(5));
        return v;
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
        appendAppDiagnostic("وضعیت", value);
    }

    /** Keep a bounded, local diagnostic history; never store imported VPN credentials here. */
    private void appendAppDiagnostic(String category, String message) {
        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
            String old = prefs.getString("app_event_history", "");
            String stamp = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            String safe = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
            String next = old.isEmpty() ? stamp + " | " + category + " | " + safe
                    : old + "\n" + stamp + " | " + category + " | " + safe;
            String[] lines = next.split("\n");
            if (lines.length > 120) {
                StringBuilder trimmed = new StringBuilder();
                for (int i = lines.length - 120; i < lines.length; i++) {
                    if (trimmed.length() > 0) trimmed.append('\n');
                    trimmed.append(lines[i]);
                }
                next = trimmed.toString();
            }
            prefs.edit().putString("app_event_history", next).apply();
        } catch (RuntimeException ignored) {
            // Diagnostics must never interrupt VPN operation.
        }
    }

    private String buildDiagnosticReport() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
        android.content.SharedPreferences singBox =
                getSharedPreferences("rah_yab_singbox_status", MODE_PRIVATE);
        StringBuilder report = new StringBuilder();
        report.append("گزارش عیب‌یابی راه‌یاب\n");
        report.append("زمان تهیه: ").append(new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date())).append('\n');
        report.append("نسخه اندروید: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("مدل دستگاه: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append("\n\n");

        appendReportSection(report, "رویدادهای برنامه",
                prefs.getString("app_event_history", ""));
        appendReportSection(report, "رویدادهای موتور OpenVPN",
                prefs.getString("native_event_history", ""));
        appendReportSection(report, "آخرین وضعیت سرویس",
                prefs.getString("service_status_message", ""));
        appendReportSection(report, "ورود سرویس به onCreate (چرخه ایجاد)",
                prefs.getString("service_on_create_entered_at", ""));
        appendReportSection(report, "ورود اتصال سیستمی به onBind",
                prefs.getString("service_on_bind_entered_at", ""));
        appendReportSection(report, "خروج اتصال سیستمی از onUnbind",
                prefs.getString("service_on_unbind_entered_at", ""));
        appendReportSection(report, "اتصال مجدد سیستمی به onRebind",
                prefs.getString("service_on_rebind_entered_at", ""));
        appendReportSection(report, "نتیجه ایجاد و پیش‌زمینه‌سازی سرویس",
                prefs.getString("service_on_create_result", ""));
        appendReportSection(report, "ورود دستور به onStartCommand",
                prefs.getString("service_start_command_entered", ""));
        appendReportSection(report, "زمان خروج سرویس از چرخه حیات",
                prefs.getString("service_on_destroy_at", ""));
        appendReportSection(report, "زمان دریافت درخواست شروع اتصال",
                prefs.getString("service_start_received", ""));
        appendReportSection(report, "نتیجه شروع نشست",
                prefs.getString("session_start_result", ""));
        appendReportSection(report, "زمان درخواست صفحه اصلی برای اجرای سرویس",
                prefs.getString("service_launch_requested_at", ""));
        appendReportSection(report, "نتیجه اتصال پل JNI",
                prefs.getString("native_bind_result", ""));
        appendReportSection(report, "نوع سرویس پیش‌زمینه",
                prefs.getString("foreground_service_type", ""));
        appendReportSection(report, "آخرین رویداد داخلی موتور",
                prefs.getString("native_last_event", ""));
        appendReportSection(report, "آخرین رویداد sing-box",
                singBox.getString("last_event", "") + " | "
                        + singBox.getString("last_detail", ""));
        appendReportSection(report, "خطای شروع سرویس",
                prefs.getString("service_start_error", ""));
        appendReportSection(report, "وضعیت چراغ اتصال",
                prefs.getString("connection_lamp_state", "نامشخص"));
        report.append("\nپروفایل‌ها و کلیدهای اتصال عمداً در این گزارش درج نمی‌شوند.\n");
        return report.toString();
    }

    private void appendReportSection(StringBuilder report, String title, String value) {
        report.append("===== ").append(title).append(" =====\n");
        report.append(value == null || value.trim().isEmpty()
                ? "اطلاعاتی ثبت نشده است" : value).append("\n\n");
    }

    private void showDiagnosticsDialog() {
        TextView content = new TextView(this);
        content.setText(buildDiagnosticReport());
        content.setTextSize(12);
        content.setTextColor(Color.rgb(220, 230, 245));
        content.setPadding(dp(12), dp(10), dp(12), dp(10));
        content.setTextIsSelectable(true);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.rgb(12, 23, 40));
        logScroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle("گزارش و عیب‌یابی راه‌یاب")
                .setMessage("گزارش رویدادها و خطاهای اخیر؛ برای ارسال به پشتیبانی، گزارش را کپی کنید.")
                .setView(logScroll)
                .setPositiveButton("کپی گزارش", (dialog, which) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                "گزارش عیب‌یابی راه‌یاب", buildDiagnosticReport()));
                        Toast.makeText(this, "گزارش کپی شد؛ آن را در گفت‌وگو جای‌گذاری کنید",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("به‌روزرسانی", (dialog, which) -> showDiagnosticsDialog())
                .setNegativeButton("بستن", null)
                .show();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        wireGuardBackend = new GoBackend(getApplicationContext());
        getWindow().setStatusBarColor(Color.rgb(9, 18, 35));
        getWindow().setNavigationBarColor(Color.rgb(9, 18, 35));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(9, 18, 35));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(26), dp(22), dp(24));
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(page);

        TextView brand = label("راه‌یاب", 29, Color.WHITE, true);
        brand.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        page.addView(brand);
        TextView subtitle = label("اتصال ساده، انتخاب هوشمند", 14, Color.rgb(164, 181, 205), false);
        subtitle.setGravity(Gravity.RIGHT);
        page.addView(subtitle);

        status = label("هنوز به سروری متصل نیستید", 16, Color.rgb(220, 230, 245), true);
        status.setBackground(shape(Color.rgb(22, 36, 59), 18));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(58));
        statusParams.topMargin = dp(30);
        page.addView(status, statusParams);

        TextView orb = label("⏻", 54, Color.WHITE, true);
        orb.setBackground(shape(Color.rgb(27, 112, 108), 100));
        LinearLayout.LayoutParams orbParams = new LinearLayout.LayoutParams(dp(154), dp(154));
        orbParams.gravity = Gravity.CENTER_HORIZONTAL;
        orbParams.topMargin = dp(32);
        orbParams.bottomMargin = dp(12);
        page.addView(orb, orbParams);

        TextView helper = label("یافتن خودکار سرورهای عمومی رایگان؛ کیفیت و حریم خصوصی آن‌ها تضمین‌شده نیست", 14,
                Color.rgb(164, 181, 205), false);
        page.addView(helper);

        discoveryButton = new Button(this);
        discoveryButton.setText("اتصال هوشمند");
        discoveryButton.setTextSize(17);
        discoveryButton.setTextColor(Color.WHITE);
        discoveryButton.setAllCaps(false);
        discoveryButton.setBackground(shape(Color.rgb(31, 151, 137), 18));
        LinearLayout.LayoutParams discoveryParams = new LinearLayout.LayoutParams(-1, dp(58));
        discoveryParams.topMargin = dp(16);
        page.addView(discoveryButton, discoveryParams);
        discoveryButton.setOnClickListener(v -> startSmartOpenVpnSearch());

        LinearLayout advancedPanel = new LinearLayout(this);
        advancedPanel.setOrientation(LinearLayout.VERTICAL);
        advancedPanel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        advancedPanel.setPadding(dp(12), dp(8), dp(12), dp(12));
        advancedPanel.setBackground(shape(Color.rgb(17, 29, 48), 18));
        advancedPanel.setVisibility(View.GONE);

        connectButton = new Button(this);
        connectButton.setText("انتخاب فایل WireGuard");
        connectButton.setTextSize(17);
        connectButton.setTextColor(Color.WHITE);
        connectButton.setAllCaps(false);
        connectButton.setBackground(shape(Color.rgb(31, 151, 137), 18));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(58));
        buttonParams.topMargin = dp(24);
        advancedPanel.addView(connectButton, buttonParams);
        connectButton.setOnClickListener(v -> chooseWireGuardFile());

        disconnectButton = new Button(this);
        disconnectButton.setText("قطع اتصال");
        disconnectButton.setAllCaps(false);
        disconnectButton.setEnabled(false);
        LinearLayout.LayoutParams disconnectParams = new LinearLayout.LayoutParams(-1, dp(52));
        disconnectParams.topMargin = dp(10);
        page.addView(disconnectButton, disconnectParams);
        disconnectButton.setOnClickListener(v -> disconnectAllEngines());

        Button advancedToggle = new Button(this);
        advancedToggle.setText("تنظیمات پیشرفته و ورود دستی  +");
        advancedToggle.setAllCaps(false);
        advancedToggle.setTextColor(Color.rgb(220, 230, 245));
        advancedToggle.setBackground(shape(Color.rgb(34, 49, 72), 16));
        LinearLayout.LayoutParams advancedToggleParams =
                new LinearLayout.LayoutParams(-1, dp(50));
        advancedToggleParams.topMargin = dp(16);
        page.addView(advancedToggle, advancedToggleParams);
        page.addView(advancedPanel);
        Button diagnosticsButton = new Button(this);
        diagnosticsButton.setText("گزارش و عیب‌یابی  •  کپی گزارش");
        diagnosticsButton.setAllCaps(false);
        diagnosticsButton.setTextColor(Color.WHITE);
        diagnosticsButton.setBackground(shape(Color.rgb(54, 83, 125), 16));
        LinearLayout.LayoutParams diagnosticsParams =
                new LinearLayout.LayoutParams(-1, dp(52));
        diagnosticsParams.topMargin = dp(12);
        page.addView(diagnosticsButton, diagnosticsParams);
        diagnosticsButton.setOnClickListener(v -> showDiagnosticsDialog());

        advancedToggle.setOnClickListener(v -> {
            boolean show = advancedPanel.getVisibility() != View.VISIBLE;
            advancedPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedToggle.setText(show
                    ? "بستن تنظیمات پیشرفته  −"
                    : "تنظیمات پیشرفته و ورود دستی  +");
        });

        Button singBoxButton = new Button(this);
        singBoxButton.setText("وارد کردن فایل sing-box (JSON)");
        singBoxButton.setAllCaps(false);
        singBoxButton.setTextColor(Color.WHITE);
        singBoxButton.setBackground(shape(Color.rgb(54, 83, 125), 18));
        LinearLayout.LayoutParams singBoxParams = new LinearLayout.LayoutParams(-1, dp(54));
        singBoxParams.topMargin = dp(12);
        advancedPanel.addView(singBoxButton, singBoxParams);
        singBoxButton.setOnClickListener(v -> chooseSingBoxFile());

        Button pasteLinkButton = new Button(this);
        pasteLinkButton.setText("افزودن لینک VMess یا Trojan");
        pasteLinkButton.setAllCaps(false);
        pasteLinkButton.setTextColor(Color.WHITE);
        pasteLinkButton.setBackground(shape(Color.rgb(54, 83, 125), 18));
        LinearLayout.LayoutParams pasteLinkParams = new LinearLayout.LayoutParams(-1, dp(54));
        pasteLinkParams.topMargin = dp(8);
        advancedPanel.addView(pasteLinkButton, pasteLinkParams);
        pasteLinkButton.setOnClickListener(v -> showOtherImportDialog());

        Button vlessButton = new Button(this);
        vlessButton.setText("افزودن لینک VLESS");
        vlessButton.setAllCaps(false);
        vlessButton.setTextColor(Color.WHITE);
        vlessButton.setBackground(shape(Color.rgb(54, 83, 125), 18));
        LinearLayout.LayoutParams vlessParams = new LinearLayout.LayoutParams(-1, dp(54));
        vlessParams.topMargin = dp(8);
        advancedPanel.addView(vlessButton, vlessParams);
        vlessButton.setOnClickListener(v -> showVlessImportDialog());

        singBoxConnectButton = new Button(this);
        singBoxConnectButton.setText("اتصال با پروفایل sing-box");
        singBoxConnectButton.setAllCaps(false);
        singBoxConnectButton.setTextColor(Color.WHITE);
        singBoxConnectButton.setEnabled(false);
        singBoxConnectButton.setBackground(shape(Color.rgb(31, 151, 137), 18));
        LinearLayout.LayoutParams singBoxConnectParams = new LinearLayout.LayoutParams(-1, dp(54));
        singBoxConnectParams.topMargin = dp(8);
        advancedPanel.addView(singBoxConnectButton, singBoxConnectParams);
        singBoxConnectButton.setOnClickListener(v -> requestSingBoxConnection());

        Button savedServersButton = new Button(this);
        savedServersButton.setText("فهرست سرورهای ذخیره‌شده");
        savedServersButton.setAllCaps(false);
        savedServersButton.setTextColor(Color.WHITE);
        savedServersButton.setBackground(shape(Color.rgb(54, 83, 125), 18));
        LinearLayout.LayoutParams savedParams = new LinearLayout.LayoutParams(-1, dp(54));
        savedParams.topMargin = dp(8);
        advancedPanel.addView(savedServersButton, savedParams);
        savedServersButton.setOnClickListener(v -> showSavedServers());

        TextView note = label("پروفایل‌های WireGuard، sing-box، VLESS، VMess و Trojan قابل وارد کردن هستند. پشتیبانی هر قالب به ساختار لینک یا فایل بستگی دارد.",
                12, Color.rgb(235, 190, 115), false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(24);
        advancedPanel.addView(note, noteParams);
        setContentView(scroll);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!singBoxReceiverRegistered) {
            IntentFilter filter = new IntentFilter(SingBoxVpnService.ACTION_EVENT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(singBoxReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(singBoxReceiver, filter);
            }
            singBoxReceiverRegistered = true;
        }
        // Recover the latest service state in case its broadcast arrived while
        // this Activity was temporarily in the background.
        restoreSingBoxStatus();
    }

    private void restoreSingBoxStatus() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("rahyab_singbox_status", MODE_PRIVATE);
        long eventTime = prefs.getLong("last_event_time", 0L);
        if (eventTime == 0L || System.currentTimeMillis() - eventTime > 10 * 60 * 1000L) return;
        String event = prefs.getString("last_event", "");
        String detail = prefs.getString("last_detail", "");
        if ("STARTING".equals(event)) {
            setStatus("در حال راه‌اندازی موتور sing-box…");
            singBoxConnectButton.setEnabled(false);
        } else if ("ENGINE_STARTED".equals(event)) {
            singBoxConnectButton.setEnabled(true);
            disconnectButton.setEnabled(true);
            verifyTunnel("sing-box");
        } else if ("TUN_ESTABLISHED".equals(event)) {
            setStatus("تونل اندروید ایجاد شد؛ بررسی عبور واقعی اینترنت لازم است");
            singBoxConnectButton.setEnabled(true);
            disconnectButton.setEnabled(true);
        } else if ("STOPPED".equals(event)) {
            setStatus("اتصال VPN قطع شد");
            singBoxConnectButton.setEnabled(true);
            disconnectButton.setEnabled(false);
        } else if ("ERROR".equals(event)) {
            setStatus("خطای sing-box: " +
                    (detail == null || detail.trim().isEmpty() ? "نامشخص" : detail));
            singBoxConnectButton.setEnabled(true);
            disconnectButton.setEnabled(false);
        }
    }

    @Override protected void onStop() {
        if (singBoxReceiverRegistered) {
            unregisterReceiver(singBoxReceiver);
            singBoxReceiverRegistered = false;
        }
        super.onStop();
    }

    /**
     * A running engine or an established Android TUN is not proof of working VPN traffic.
     * Confirm that Android routes this app over a VPN transport and that HTTPS egress works.
     */
    private void verifyTunnel(String engineName) {
        if (worker.isShutdown()) return;
        setStatus("تونل " + engineName + " آماده شد؛ در حال بررسی عبور واقعی اینترنت…");
        try {
            worker.execute(() -> {
                TunnelVerifier.Result result = TunnelVerifier.verify(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (result.verified) {
                        setStatus("اتصال " + engineName + " تأیید شد • IP خروجی: " + result.exitIp);
                    } else {
                        setStatus("اتصال " + engineName + " هنوز تأیید نشده: " + result.message);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Activity is shutting down; do not report a false connection state.
        }
    }

    private void discoverFreeServers() {
        if (discoveryButton == null || !discoveryButton.isEnabled()) return;
        discoveryButton.setEnabled(false);
        setStatus("در حال دریافت فهرست سرورهای رایگان از منبع رسمی VPN Gate…");
        worker.execute(() -> {
            try {
                VpnGateProvider.Result result = VpnGateProvider.fetch();
                runOnUiThread(() -> {
                    discoveryButton.setEnabled(true);
                    if (isFinishing() || isDestroyed()) return;
                    int count = Math.min(result.servers.size(), 25);
                    String[] labels = new String[count];
                    for (int i = 0; i < count; i++) {
                        VpnGateProvider.Server server = result.servers.get(i);
                        String country = server.country.isEmpty() ? "کشور نامشخص" : server.country;
                        String host = server.hostName.isEmpty() ? server.ip : server.hostName;
                        String ping = server.advertisedPingMs == Integer.MAX_VALUE
                                ? "نامشخص" : server.advertisedPingMs + " ms";
                        long speedMb = server.advertisedSpeedBytesPerSecond / (1024L * 1024L);
                        labels[i] = country + " | " + host + "\n" + server.ip + ":" + server.port
                                + " | پینگ اعلامی: " + ping + " | سرعت اعلامی: " + speedMb + " MB/s";
                    }
                    setStatus("فهرست دریافت شد: " + result.servers.size()
                            + " پروفایل عمومی؛ هنوز هیچ سروری آزمایش یا متصل نشده است");
                    new AlertDialog.Builder(this)
                            .setTitle("سرورهای عمومی VPN Gate")
                            .setMessage("این سرورها داوطلبانه و عمومی‌اند. اطلاعات پینگ و سرعت را خود منبع اعلام کرده و کیفیت واقعی یا امنیت را ثابت نمی‌کند. در این مرحله فقط فهرست دریافت می‌شود؛ اتصال خودکار هنوز فعال نیست.")
                            .setItems(labels, (dialog, which) -> {
                                VpnGateProvider.Server selected = result.servers.get(which);
                                setStatus("سرور انتخاب‌شده فقط شناسایی شد: " + selected.ip
                                        + ":" + selected.port + "؛ موتور اتصال داخلی OpenVPN هنوز آماده نیست");
                            })
                            .setPositiveButton("بستن", null)
                            .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    discoveryButton.setEnabled(true);
                    if (isFinishing() || isDestroyed()) return;
                    setStatus("جستجوی سرورهای رایگان ناموفق بود: " + safeMessage(error));
                    Toast.makeText(this, "دریافت فهرست سرورها انجام نشد", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void chooseWireGuardFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_CONFIG);
    }

    private void chooseSingBoxFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_SINGBOX_PROFILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPENVPN_VPN_PERMISSION && resultCode != RESULT_OK) {
            pendingOpenVpnProfiles = null;
            if (discoveryButton != null) discoveryButton.setEnabled(true);
            if (disconnectButton != null) disconnectButton.setEnabled(false);
            setStatus("اجازه ایجاد اتصال VPN داده نشد؛ برای اتصال هوشمند دوباره تلاش کنید");
            return;
        }
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQUEST_VPN_PERMISSION) {
            startWireGuard();
            return;
        }
        if (requestCode == REQUEST_SINGBOX_VPN_PERMISSION) {
            startSingBox();
            return;
        }
        if (requestCode == REQUEST_OPENVPN_VPN_PERMISSION) {
            startOpenVpnService();
            return;
        }
        if (data == null) return;
        if (requestCode == REQUEST_SINGBOX_PROFILE) {
            Uri uri = data.getData();
            if (uri == null) return;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) throw new IllegalStateException("فایل قابل خواندن نیست");
                byte[] buffer = new byte[8192];
                int count;
                int totalBytes = 0;
                final int maxProfileBytes = 2 * 1024 * 1024;
                while ((count = in.read(buffer)) != -1) {
                    totalBytes += count;
                    if (totalBytes > maxProfileBytes) {
                        throw new IllegalArgumentException("حجم فایل پروفایل نباید بیشتر از ۲ مگابایت باشد");
                    }
                    out.write(buffer, 0, count);
                }
                pendingSingBoxJson = new String(out.toByteArray(), StandardCharsets.UTF_8);
                SingBoxProfile profile = SingBoxProfile.parse(pendingSingBoxJson);
                saveSingBoxProfile(pendingSingBoxJson, profile);
                singBoxConnectButton.setEnabled(true);
                setStatus("پروفایل آماده اتصال: " + profile.getProtocol()
                        + " | " + profile.getOutboundTag());
            } catch (Exception e) {
                setStatus("پروفایل sing-box معتبر نیست: " + safeMessage(e));
            }
        } else if (requestCode == REQUEST_CONFIG) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                // Some document providers grant temporary read access but do not support
                // persistable permissions. The profile is parsed immediately, so persistence
                // is optional and must not prevent importing the file.
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Continue with the temporary permission returned by ACTION_OPEN_DOCUMENT.
                }
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("فایل قابل خواندن نیست");
                    pendingWireGuardConfig = Config.parse(in);
                }
                Intent permission = VpnService.prepare(this);
                if (permission != null) {
                    startActivityForResult(permission, REQUEST_VPN_PERMISSION);
                } else {
                    startWireGuard();
                }
            } catch (Exception e) {
                setStatus("خطا در خواندن فایل WireGuard: " + safeMessage(e));
            }
        }
    }


    private JSONArray readSavedServers() {
        String raw = getSharedPreferences(PREFS_SERVERS, MODE_PRIVATE)
                .getString(KEY_SERVERS, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void saveSingBoxProfile(String rawJson, SingBoxProfile profile) throws Exception {
        JSONArray old = readSavedServers();
        JSONArray updated = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("tag", profile.getOutboundTag());
        item.put("protocol", profile.getProtocol());
        item.put("json", rawJson);
        boolean replaced = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject existing = old.optJSONObject(i);
            if (existing == null) continue;
            if (profile.getOutboundTag().equals(existing.optString("tag"))) {
                if (!replaced) {
                    updated.put(item);
                    replaced = true;
                }
            } else if (updated.length() < 99) {
                updated.put(existing);
            }
        }
        if (!replaced) updated.put(item);
        getSharedPreferences(PREFS_SERVERS, MODE_PRIVATE).edit()
                .putString(KEY_SERVERS, updated.toString()).apply();
    }

    private void showSavedServers() {
        JSONArray servers = readSavedServers();
        if (servers.length() == 0) {
            new AlertDialog.Builder(this).setTitle("سرورهای ذخیره‌شده")
                    .setMessage("هنوز سروری ذخیره نشده است. ابتدا فایل JSON یک سرور را وارد کنید.")
                    .setPositiveButton("متوجه شدم", null).show();
            return;
        }
        String[] labels = new String[servers.length()];
        for (int i = 0; i < servers.length(); i++) {
            JSONObject item = servers.optJSONObject(i);
            labels[i] = item == null ? "پروفایل نامعتبر" :
                    item.optString("tag", "بدون نام") + "  •  " +
                    item.optString("protocol", "نامشخص");
        }
        final int[] selected = {0};
        new AlertDialog.Builder(this)
                .setTitle("انتخاب سرور")
                .setSingleChoiceItems(labels, 0, (dialog, which) -> selected[0] = which)
                .setPositiveButton("انتخاب و اتصال", (dialog, which) -> {
                    JSONObject item = servers.optJSONObject(selected[0]);
                    if (item == null) {
                        setStatus("اطلاعات این سرور معتبر نیست");
                        return;
                    }
                    pendingSingBoxJson = item.optString("json", null);
                    if (pendingSingBoxJson == null || pendingSingBoxJson.trim().isEmpty()) {
                        setStatus("فایل ذخیره‌شده ناقص است");
                        return;
                    }
                    singBoxConnectButton.setEnabled(true);
                    setStatus("سرور انتخاب شد: " + labels[selected[0]]);
                    requestSingBoxConnection();
                })
                .setNegativeButton("بستن", null)
                .setNeutralButton("حذف سرور", (dialog, which) -> {
                    JSONArray remaining = new JSONArray();
                    for (int i = 0; i < servers.length(); i++) {
                        if (i != selected[0]) remaining.put(servers.optJSONObject(i));
                    }
                    getSharedPreferences(PREFS_SERVERS, MODE_PRIVATE).edit()
                            .putString(KEY_SERVERS, remaining.toString()).apply();
                    setStatus("سرور انتخاب‌شده از فهرست حذف شد");
                })
                .show();
    }



    private void showOtherImportDialog() {
        EditText input = new EditText(this);
        input.setHint("vmess://... یا trojan://...");
        input.setTextSize(13);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("افزودن لینک VMess یا Trojan")
                .setMessage("لینک اشتراک را وارد کنید. فرمت‌های رایج VMess (TCP/WS) و Trojan (TCP/WS) پشتیبانی می‌شوند.")
                .setView(input)
                .setPositiveButton("بررسی و ذخیره", (dialog, which) -> {
                    try {
                        String generated = SingBoxProfile.fromOtherShareLink(
                                input.getText().toString().trim());
                        SingBoxProfile profile = SingBoxProfile.parse(generated);
                        pendingSingBoxJson = generated;
                        saveSingBoxProfile(generated, profile);
                        singBoxConnectButton.setEnabled(true);
                        setStatus("لینک " + profile.getProtocol() + " بررسی و ذخیره شد: "
                                + profile.getOutboundTag());
                    } catch (Exception e) {
                        setStatus("خطا در لینک: " + safeMessage(e));
                        Toast.makeText(this, safeMessage(e), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void showVlessImportDialog() {
        EditText input = new EditText(this);
        input.setHint("vless://...");
        input.setTextSize(13);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("افزودن لینک VLESS")
                .setMessage("لینک اشتراک VLESS را وارد کنید. فعلاً لینک‌های TCP، WebSocket و gRPC پشتیبانی می‌شوند.")
                .setView(input)
                .setPositiveButton("بررسی و ذخیره", (dialog, which) -> {
                    try {
                        String generated = SingBoxProfile.fromShareLink(
                                input.getText().toString().trim());
                        SingBoxProfile profile = SingBoxProfile.parse(generated);
                        pendingSingBoxJson = generated;
                        saveSingBoxProfile(generated, profile);
                        singBoxConnectButton.setEnabled(true);
                        setStatus("لینک VLESS بررسی و ذخیره شد: " + profile.getOutboundTag());
                    } catch (Exception e) {
                        setStatus("خطا در لینک VLESS: " + safeMessage(e));
                        Toast.makeText(this, safeMessage(e), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private void requestSingBoxConnection() {
        if (pendingSingBoxJson == null) {
            setStatus("ابتدا فایل JSON سرور را وارد کنید");
            return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission != null) {
            startActivityForResult(permission, REQUEST_SINGBOX_VPN_PERMISSION);
        } else {
            startSingBox();
        }
    }

    private void startSingBox() {
        if (pendingSingBoxJson == null) return;
        try {
            setStatus("درخواست راه‌اندازی موتور sing-box ارسال شد…");
            SingBoxVpnService.start(getApplicationContext(), pendingSingBoxJson);
        } catch (Exception e) {
            setStatus("شروع sing-box ناموفق بود: " + safeMessage(e));
        }
    }

    private void startWireGuard() {
        if (pendingWireGuardConfig == null) return;
        setStatus("در حال راه‌اندازی تونل WireGuard…");
        connectButton.setEnabled(false);
        worker.execute(() -> {
            try {
                Tunnel.State actualState = wireGuardBackend.setState(
                        wireGuardTunnel, Tunnel.State.UP, pendingWireGuardConfig);
                if (actualState != Tunnel.State.UP) {
                    throw new IllegalStateException("موتور WireGuard تونل را روشن اعلام نکرد");
                }
                runOnUiThread(() -> {
                    setStatus("تونل WireGuard روشن است؛ عبور واقعی اینترنت هنوز تأیید نشده است");
                    disconnectButton.setEnabled(true);
                    connectButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("اتصال ناموفق: " + safeMessage(e));
                    connectButton.setEnabled(true);
                });
            }
        });
    }

    private void disconnectWireGuard() {
        setStatus("در حال قطع اتصال…");
        SingBoxVpnService.stop(getApplicationContext());
        worker.execute(() -> {
            try {
                wireGuardBackend.setState(wireGuardTunnel, Tunnel.State.DOWN, null);
                runOnUiThread(() -> disconnectButton.setEnabled(false));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("خطا در قطع اتصال: " + safeMessage(e)));
            }
        });
    }

    private void startSmartOpenVpnSearch() {
        if (discoveryButton == null || !discoveryButton.isEnabled()) return;
        discoveryButton.setEnabled(false);
        setStatus("در حال جستجوی سرورهای رایگان و آماده‌سازی آزمون اتصال…");
        worker.execute(() -> {
            try {
                VpnGateProvider.Result result = VpnGateProvider.fetch();
                ArrayList<String> profiles = new ArrayList<>();
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                for (VpnGateProvider.Server server : result.servers) {
                    if (profiles.size() >= 30) break;
                    if (server.openVpnProfile != null && !server.openVpnProfile.isEmpty()
                            && seen.add(server.endpointKey())) {
                        profiles.add(server.openVpnProfile);
                    }
                }
                if (profiles.isEmpty()) throw new IllegalStateException("پروفایل قابل استفاده‌ای پیدا نشد");
                runOnUiThread(() -> {
                    discoveryButton.setEnabled(true);
                    pendingOpenVpnProfiles = profiles;
                    setStatus(profiles.size() + " پروفایل دریافت شد؛ برای اجازه VPN آماده‌اید");
                    Intent permission = VpnService.prepare(this);
                    if (permission != null) {
                        startActivityForResult(permission, REQUEST_OPENVPN_VPN_PERMISSION);
                    } else {
                        startOpenVpnService();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    discoveryButton.setEnabled(true);
                    if (!isFinishing() && !isDestroyed())
                        setStatus("جستجوی هوشمند ناموفق بود: " + safeMessage(error));
                });
            }
        });
    }

    private void startOpenVpnService() {
        if (pendingOpenVpnProfiles == null || pendingOpenVpnProfiles.isEmpty()) {
            setStatus("فهرست سرورها آماده نیست؛ دوباره اتصال هوشمند را بزنید");
            return;
        }
        setStatus("در حال آماده‌سازی OpenVPN 3 و آزمون سرورها؛ اتصال تا تأیید اینترنت قطعی اعلام نمی‌شود…");
        discoveryButton.setEnabled(false);
        disconnectButton.setEnabled(true);
        // Android allows only one active VpnService tunnel. Stop the other engines
        // before starting OpenVPN, and serialize WireGuard teardown on the worker.
        SingBoxVpnService.stop(getApplicationContext());
        worker.execute(() -> {
            try {
                wireGuardBackend.setState(wireGuardTunnel, Tunnel.State.DOWN, null);
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Intent service = new Intent(this, RahYabVpnService.class);
                service.setAction(RahYabVpnService.ACTION_START);
                service.putStringArrayListExtra(RahYabVpnService.EXTRA_PROFILES,
                        new ArrayList<>(pendingOpenVpnProfiles));
                try {
                    // Start a fresh diagnostic attempt so old service/JNI events
                    // cannot be mistaken for the current launch.
                    android.content.SharedPreferences diagnostics =
                            getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
                    diagnostics.edit()
                            .putString("service_launch_requested_at",
                                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                            java.util.Locale.US).format(new java.util.Date()))
                            .remove("service_start_command_entered")
                            .remove("service_start_received")
                            .remove("native_bind_result")
                            .remove("foreground_service_type")
                            .remove("service_start_error")
                            .remove("session_start_result")
                            .remove("native_last_event")
                            .remove("native_event_history")
                            .remove("service_status_message")
                            .commit();
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                    else startService(service);
                    setStatus("درخواست اجرای سرویس ارسال شد؛ منتظر ثبت شروع موتور هستیم…");
                } catch (RuntimeException error) {
                    discoveryButton.setEnabled(true);
                    disconnectButton.setEnabled(false);
                    setStatus("شروع سرویس OpenVPN ناموفق بود: " + safeMessage(error));
                }
            });
        });
    }

    private void disconnectAllEngines() {
        setStatus("در حال قطع همه موتورهای VPN…");
        SingBoxVpnService.stop(getApplicationContext());
        Intent stop = new Intent(this, RahYabVpnService.class);
        stop.setAction(RahYabVpnService.ACTION_STOP);
        try { startService(stop); } catch (RuntimeException ignored) { }
        worker.execute(() -> {
            try { wireGuardBackend.setState(wireGuardTunnel, Tunnel.State.DOWN, null); }
            catch (Exception ignored) { }
            runOnUiThread(() -> {
                disconnectButton.setEnabled(false);
                discoveryButton.setEnabled(true);
                setStatus("درخواست قطع اتصال به همه موتورها ارسال شد");
            });
        });
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    @Override protected void onResume() {
        super.onResume();
        if (status == null) return;
        android.content.SharedPreferences diagnostics =
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE);
        long updatedAt = diagnostics.getLong("service_status_updated_at", 0L);
        String savedStatus = diagnostics.getString("service_status_message", null);
        String lamp = diagnostics.getString("connection_lamp_state", "disconnected");
        boolean recent = updatedAt > 0L
                && System.currentTimeMillis() - updatedAt < 24L * 60L * 60L * 1000L;
        if (recent && savedStatus != null && !savedStatus.trim().isEmpty()) {
            setStatus(savedStatus);
            boolean active = "connected".equals(lamp) || "searching".equals(lamp);
            if (disconnectButton != null) disconnectButton.setEnabled(active);
            if (discoveryButton != null) discoveryButton.setEnabled(!"searching".equals(lamp));
        }
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
