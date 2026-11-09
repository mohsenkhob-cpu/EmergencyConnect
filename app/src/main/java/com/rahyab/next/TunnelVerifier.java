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
    private static final int DNS_INDEPENDENT_TIMEOUT_MS = 5000;
    private static final int CONNECT_TIMEOUT_MS = 7000;
    private static final int READ_TIMEOUT_MS = 7000;
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
        static Result success(String ip) { return new Result(true, ip, "HTTPS از مسیر VPN پاسخ داد"); }
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
        for (int i = 0; i < urls.length; i++) {
            if (Thread.currentThread().isInterrupted() || !vpn.equals(manager.getActiveNetwork()))
                return Result.failure("آزمون لغو شد یا شبکه تغییر کرد");
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) vpn.openConnection(new URL(urls[i]), java.net.Proxy.NO_PROXY);
                configure(c);
                int code = c.getResponseCode();
                if (i > 0 && code == 204 && vpn.equals(manager.getActiveNetwork()))
                    return Result.success("");
                if (i == 0 && code == 200) {
                    String ip;
                    try (InputStream in = c.getInputStream()) {
                        ip = new JSONObject(new String(readLimited(in, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8))
                                .optString("ip", "").trim();
                    }
                    if (isPublicAddressLiteral(ip) && vpn.equals(manager.getActiveNetwork())) return Result.success(ip);
                }
                errors.append("HTTPS-").append(i + 1).append(":HTTP_").append(code).append(" ");
            } catch (Exception e) {
                errors.append("HTTPS-").append(i + 1).append(":").append(e.getClass().getSimpleName()).append(" ");
            } finally { if (c != null) c.disconnect(); }
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
                    return Result.dnsFailure("HTTPS بدون DNS کار می‌کند؛ بازیابی DNS لازم است");
            }
        } catch (Exception ignored) { }
        finally { if (c != null) c.disconnect(); }
        return Result.failure("مقصدهای مستقل HTTPS پاسخ معتبر ندادند؛ " + errors);
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
