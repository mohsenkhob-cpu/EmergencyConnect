package com.rahyab.next;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_CONFIG = 4101;
    private static final int REQUEST_VPN_PERMISSION = 4102;
    private static final int REQUEST_SINGBOX_PROFILE = 4103;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private GoBackend wireGuardBackend;
    private Config pendingWireGuardConfig;
    private TextView status;
    private Button connectButton;
    private Button disconnectButton;

    private final Tunnel wireGuardTunnel = new Tunnel() {
        @Override public String getName() { return "RahYab-WireGuard"; }
        @Override public void onStateChange(State state) {
            runOnUiThread(() -> setStatus(state == State.UP
                    ? "تونل WireGuard روشن شد؛ بررسی عبور اینترنت لازم است"
                    : "تونل WireGuard خاموش شد"));
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

        TextView helper = label("برای اتصال، فایل پیکربندی WireGuard را وارد کنید", 14,
                Color.rgb(164, 181, 205), false);
        page.addView(helper);

        connectButton = new Button(this);
        connectButton.setText("انتخاب فایل WireGuard");
        connectButton.setTextSize(17);
        connectButton.setTextColor(Color.WHITE);
        connectButton.setAllCaps(false);
        connectButton.setBackground(shape(Color.rgb(31, 151, 137), 18));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(58));
        buttonParams.topMargin = dp(24);
        page.addView(connectButton, buttonParams);
        connectButton.setOnClickListener(v -> chooseWireGuardFile());

        disconnectButton = new Button(this);
        disconnectButton.setText("قطع اتصال");
        disconnectButton.setAllCaps(false);
        disconnectButton.setEnabled(false);
        LinearLayout.LayoutParams disconnectParams = new LinearLayout.LayoutParams(-1, dp(52));
        disconnectParams.topMargin = dp(10);
        page.addView(disconnectButton, disconnectParams);
        disconnectButton.setOnClickListener(v -> disconnectWireGuard());

        Button singBoxButton = new Button(this);
        singBoxButton.setText("وارد کردن فایل sing-box (JSON)");
        singBoxButton.setAllCaps(false);
        singBoxButton.setTextColor(Color.WHITE);
        singBoxButton.setBackground(shape(Color.rgb(54, 83, 125), 18));
        LinearLayout.LayoutParams singBoxParams = new LinearLayout.LayoutParams(-1, dp(54));
        singBoxParams.topMargin = dp(12);
        page.addView(singBoxButton, singBoxParams);
        singBoxButton.setOnClickListener(v -> chooseSingBoxFile());

        TextView note = label("در این نسخه، اتصال آزمایشی WireGuard پیاده‌سازی شده است. سایر پروتکل‌ها هنوز فعال نیستند.",
                12, Color.rgb(235, 190, 115), false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(24);
        page.addView(note, noteParams);
        setContentView(scroll);
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
        if (resultCode != RESULT_OK || data == null) return;
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
                SingBoxProfile profile = SingBoxProfile.parse(
                        new String(out.toByteArray(), StandardCharsets.UTF_8));
                setStatus("پروفایل شناسایی شد: " + profile.getProtocol()
                        + " | " + profile.getOutboundTag()
                        + " — موتور اتصال هنوز در حال پیاده‌سازی است");
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
        } else if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) startWireGuard();
            else setStatus("مجوز ایجاد VPN داده نشد");
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
        worker.execute(() -> {
            try {
                wireGuardBackend.setState(wireGuardTunnel, Tunnel.State.DOWN, null);
                runOnUiThread(() -> disconnectButton.setEnabled(false));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("خطا در قطع اتصال: " + safeMessage(e)));
            }
        });
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
