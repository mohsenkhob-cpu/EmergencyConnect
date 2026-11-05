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
    private static final int CONNECT_TIMEOUT_MS = 7000;
    private static final int READ_TIMEOUT_MS = 7000;
    private static final int MAX_RESPONSE_BYTES = 4096;

    private TunnelVerifier() { }

    public static final class Result {
        public final boolean verified;
        public final String exitIp;
        public final String message;

        private Result(boolean verified, String exitIp, String message) {
            this.verified = verified;
            this.exitIp = exitIp;
            this.message = message;
        }

        static Result success(String ip) { return new Result(true, ip, "HTTPS از مسیر VPN پاسخ داد"); }
        static Result failure(String message) { return new Result(false, null, message); }
    }

    /** Must be called on a worker thread. */
    public static Result verify(Context context) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return Result.failure("مدیر اتصال اندروید در دسترس نیست");

        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return Result.failure("اندروید مسیر فعال VPN را برای این برنامه تأیید نکرد");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(IP_CHECK_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "RahYabAndroid/0.3");

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return Result.failure("آزمون HTTPS پاسخ HTTP " + code + " دریافت کرد");
            }

            byte[] body;
            try (InputStream input = connection.getInputStream()) {
                body = readLimited(input, MAX_RESPONSE_BYTES);
            }
            String ip = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("ip", "").trim();
            if (!isPublicAddressLiteral(ip)) {
                return Result.failure("سرویس بررسی، نشانی IP معتبر و عمومی برنگرداند");
            }
            return Result.success(ip);
        } catch (Exception e) {
            String message = e.getMessage();
            return Result.failure("آزمون عبور HTTPS ناموفق بود: "
                    + (message == null || message.trim().isEmpty()
                    ? e.getClass().getSimpleName() : message));
        } finally {
            if (connection != null) connection.disconnect();
        }
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