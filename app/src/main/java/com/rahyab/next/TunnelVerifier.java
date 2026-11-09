package com.rahyab.next;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Verifies actual HTTPS egress through Android's active VPN network.
 * This is a point-in-time check, not a privacy, anonymity, or long-term stability guarantee.
 */
public final class TunnelVerifier {
    private static final String IP_CHECK_URL = "https://api.ipify.org?format=json";
    private static final String DNS_INDEPENDENT_PROBE_URL = "https://1.1.1.1/cdn-cgi/trace";
    private static final int DOWNLOAD_BYTES = 65536;
    private static final long MIN_DOWNLOAD_KBPS = 128;
    private static final long DOWNLOAD_DEADLINE_MS = 8000;
    private static final int MAX_RESPONSE_BYTES = 4096;

    private TunnelVerifier() { }

    public static final class Result {
        public final boolean verified;
        public final String exitIp;
        public final String message;
        public final boolean dnsOnlyFailure;

        private Result(boolean verified, String exitIp, String message) {
            this.verified = verified;
            this.exitIp = exitIp;
            this.message = message;
            this.dnsOnlyFailure = false;
        }

        private Result(String message) {
            this.verified = false; this.exitIp = null;
            this.message = message; this.dnsOnlyFailure = true;
        }
        static Result dnsFailure(String message) { return new Result(message); }
        static Result success(String ip, long kbps) {
            return new Result(true, ip, "دو مقصد HTTPS و دریافت ۶۴ کیلوبایت از مسیر VPN تأیید شد؛ سرعت آزمون " + kbps + " کیلوبیت‌برثانیه");
        }
        static Result failure(String message) { return new Result(false, null, message); }
    }

    /** Check independent HTTPS destinations, always on the same captured VPN Network. */
    public static Result verify(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return Result.failure("مدیر اتصال اندروید در دسترس نیست");
        Network vpn = awaitActiveVpn(manager);
        if (vpn == null) return Result.failure("مسیر VPN در اندروید فعال نشد");
        String[] urls = {IP_CHECK_URL, "https://www.gstatic.com/generate_204",
                "https://cp.cloudflare.com/generate_204"};
        StringBuilder errors = new StringBuilder();
        boolean lookupFailure = false;
        int successes = 0;
        String exitIp = "";
        for (int i = 0; i < urls.length; i++) {
            if (Thread.currentThread().isInterrupted() || !vpn.equals(manager.getActiveNetwork()))
                return Result.failure("آزمون لغو شد یا شبکه تغییر کرد");
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) vpn.openConnection(new URL(urls[i]), java.net.Proxy.NO_PROXY);
                configure(c);
                int code = c.getResponseCode();
                if (i > 0 && code == 204 && vpn.equals(manager.getActiveNetwork())) successes++;
                if (i == 0 && code == 200) {
                    String ip;
                    try (InputStream in = c.getInputStream()) {
                        ip = new JSONObject(new String(readLimited(in, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8))
                                .optString("ip", "").trim();
                    }
                    if (isPublicAddressLiteral(ip) && vpn.equals(manager.getActiveNetwork())) {
                        successes++; exitIp = ip;
                    }
                }
                errors.append("HTTPS-").append(i + 1).append(":HTTP_").append(code).append(" ");
            } catch (Exception e) {
                if (e instanceof java.net.UnknownHostException) lookupFailure = true;
                errors.append("HTTPS-").append(i + 1).append(":").append(e.getClass().getSimpleName()).append(" ");
            } finally { if (c != null) c.disconnect(); }
            if (successes >= 2) {
                if (Thread.currentThread().isInterrupted() || !vpn.equals(manager.getActiveNetwork()))
                    return Result.failure("آزمون لغو شد یا شبکه تغییر کرد");
                return verifyDownload(vpn, manager, exitIp);
            }
        }
        // A working numeric HTTPS path is DNS degradation, not proof of normal app connectivity.
        HttpURLConnection c = null;
        try {
            if (Thread.currentThread().isInterrupted() || !vpn.equals(manager.getActiveNetwork()))
                return Result.failure("آزمون لغو شد یا شبکه تغییر کرد");
            c = (HttpURLConnection) vpn.openConnection(new URL(DNS_INDEPENDENT_PROBE_URL), java.net.Proxy.NO_PROXY);
            configure(c);
            if (c.getResponseCode() == 200) {
                String body;
                try (InputStream in = c.getInputStream()) {
                    body = new String(readLimited(in, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
                }
                if (traceHasPublicIp(body) && vpn.equals(manager.getActiveNetwork()))
                    return lookupFailure
                            ? Result.dnsFailure("HTTPS بدون DNS کار می‌کند؛ بازیابی DNS لازم است")
                            : Result.failure("HTTPS عددی پاسخ داد؛ مقصدهای دامنه‌ای آزمون ناموفق بودند؛ " + errors);
            }
        } catch (Exception ignored) { }
        finally { if (c != null) c.disconnect(); }
        return Result.failure("اتصال قابل استفاده تأیید نشد؛ " + successes + " از ۲ مقصد مستقل پاسخ داد؛ " + errors);
    }

    private static Result verifyDownload(Network vpn, ConnectivityManager manager, String exitIp) {
        HttpURLConnection c = null;
        long started = android.os.SystemClock.elapsedRealtime();
        int total = 0;
        try {
            c = (HttpURLConnection) vpn.openConnection(
                    new URL("https://speed.cloudflare.com/__down?bytes=" + DOWNLOAD_BYTES), java.net.Proxy.NO_PROXY);
            configure(c);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (c.getResponseCode() != 200) return Result.failure("مقصد سنجش دریافت پاسخ معتبر نداد");
            try (InputStream input = c.getInputStream()) {
                byte[] buffer = new byte[8192];
                while (total < DOWNLOAD_BYTES) {
                    if (Thread.currentThread().isInterrupted() || !vpn.equals(manager.getActiveNetwork()))
                        return Result.failure("آزمون سرعت لغو شد یا شبکه تغییر کرد");
                    if (android.os.SystemClock.elapsedRealtime() - started > DOWNLOAD_DEADLINE_MS)
                        return Result.failure("مسیر بیش از حد کند است؛ دریافت ۶۴ کیلوبایت در مهلت آزمون تمام نشد");
                    int count = input.read(buffer, 0, Math.min(buffer.length, DOWNLOAD_BYTES - total));
                    if (count < 0) break;
                    total += count;
                }
            }
            if (Thread.currentThread().isInterrupted() || !vpn.equals(manager.getActiveNetwork()))
                return Result.failure("آزمون سرعت لغو شد یا شبکه تغییر کرد");
            long elapsed = Math.max(1L, android.os.SystemClock.elapsedRealtime() - started);
            long kbps = total * 8L / elapsed;
            if (total != DOWNLOAD_BYTES || elapsed > DOWNLOAD_DEADLINE_MS || kbps < MIN_DOWNLOAD_KBPS)
                return Result.failure("مسیر ضعیف یا دریافت ناقص؛ " + total + " بایت؛ سرعت آزمون " + kbps + " کیلوبیت‌برثانیه");
            return Result.success(exitIp, kbps);
        } catch (Exception error) {
            return Result.failure("آزمون دریافت واقعی ناموفق بود: " + error.getClass().getSimpleName());
        } finally { if (c != null) c.disconnect(); }
    }

    private static void configure(HttpURLConnection c) {
        c.setConnectTimeout(3000); c.setReadTimeout(3000);
        c.setUseCaches(false); c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "RahYab/verified-egress");
    }

    static boolean traceHasPublicIp(String body) {
        for (String line : body.split("\\r?\\n"))
            if (line.startsWith("ip=") && isPublicAddressLiteral(line.substring(3).trim())) return true;
        return false;
    }

    private static Network awaitActiveVpn(ConnectivityManager manager) {
        // The VPN interface can exist before Android publishes it as the app's active network.
        long deadline = android.os.SystemClock.elapsedRealtime() + 8_000L;
        while (!Thread.currentThread().isInterrupted()) {
            Network active = manager.getActiveNetwork();
            NetworkCapabilities capabilities =
                    active == null ? null : manager.getNetworkCapabilities(active);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return active;
            }
            long remaining = deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0) break;
            try {
                Thread.sleep(Math.min(remaining, 200L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    static boolean isPublicAddressLiteral(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) return false;
        boolean ipv4 = value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        boolean ipv6 = value.indexOf(':') >= 0 && value.matches("[0-9a-fA-F:.%]+");
        if (!ipv4 && !ipv6) return false;
        try {
            InetAddress address = InetAddress.getByName(value);
            return !address.isAnyLocalAddress() && !address.isLoopbackAddress()
                    && !address.isLinkLocalAddress() && !address.isSiteLocalAddress()
                    && !address.isMulticastAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new java.io.IOException("پاسخ آزمون بیش از حد بزرگ است");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}

