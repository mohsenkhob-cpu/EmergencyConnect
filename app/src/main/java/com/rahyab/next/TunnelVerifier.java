package com.rahyab.next;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
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

        Network vpn = awaitActiveVpn(manager);
        if (vpn == null) {
            return Result.failure("مسیر VPN تا پایان مهلت ۸ ثانیه‌ای در اندروید فعال نشد");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) vpn.openConnection(new URL(IP_CHECK_URL));
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
            if (!vpn.equals(manager.getActiveNetwork())) {
                return Result.failure("مسیر فعال شبکه هنگام بررسی تغییر کرد");
            }
            return Result.success(ip);
        } catch (java.net.UnknownHostException e) {
            return Result.failure("DNS برای api.ipify.org پاسخ نداد (UnknownHostException)؛ "
                    + probeWithoutDns(manager, vpn));
        } catch (Exception e) {
            String message = e.getMessage();
            return Result.failure("آزمون عبور HTTPS ناموفق بود: "
                    + (message == null || message.trim().isEmpty()
                    ? e.getClass().getSimpleName() : message));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Xray can confirm HTTPS even if the IP-reporting endpoint is unavailable. */
    static boolean verifyXray(Context context) {
        if (verify(context).verified) return true;
        if (Thread.currentThread().isInterrupted()) return false;
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return false;
        Network vpn = manager.getActiveNetwork();
        NetworkCapabilities capabilities = vpn == null ? null : manager.getNetworkCapabilities(vpn);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) vpn.openConnection(
                    new URL("https://cp.cloudflare.com/generate_204"), java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(3500);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            return connection.getResponseCode() == HttpURLConnection.HTTP_NO_CONTENT
                    && vpn.equals(manager.getActiveNetwork());
        } catch (IOException | RuntimeException ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Short sample used to compare verified tunnels, in kilobits per second. */
    public static long sampleDownloadKbps(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network vpn = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = vpn == null ? null : manager.getNetworkCapabilities(vpn);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            return -1;

        int limit = 128 * 1024;
        long started = android.os.SystemClock.elapsedRealtime();
        long deadline = started + 4_000L;
        int bytes = 0;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) vpn.openConnection(
                    new URL("https://speed.cloudflare.com/__down?bytes=" + limit),
                    java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(1800);
            connection.setReadTimeout(1800);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return -1;
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                while (bytes < limit && android.os.SystemClock.elapsedRealtime() < deadline
                        && !Thread.currentThread().isInterrupted()) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, limit - bytes));
                    if (count < 0) break;
                    bytes += count;
                }
            }
        } catch (java.net.SocketTimeoutException ignored) {
            // A partial transfer can still indicate a slow connection.
        } catch (java.io.IOException | RuntimeException ignored) {
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
        if (bytes == 0 || !vpn.equals(manager.getActiveNetwork())
                || Thread.currentThread().isInterrupted()) return -1;
        long elapsed = Math.max(1, android.os.SystemClock.elapsedRealtime() - started);
        return Math.max(1, bytes * 8L / elapsed);
    }

    public static final long DOWNLOAD_TARGET_KBPS = 1500;

    /** Bitmask of lightweight HTTPS responses from the media services through the VPN. */
    static int probeXrayMedia(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network vpn = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = vpn == null ? null : manager.getNetworkCapabilities(vpn);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            return 0;

        int result = 0;
        if (probeMediaHost(vpn, "https://www.youtube.com/")) result |= 1;
        if (!Thread.currentThread().isInterrupted()
                && probeMediaHost(vpn, "https://www.instagram.com/")) result |= 2;
        return vpn.equals(manager.getActiveNetwork()) ? result : 0;
    }


    private static boolean probeMediaHost(Network vpn, String endpoint) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) vpn.openConnection(new URL(endpoint), java.net.Proxy.NO_PROXY);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Accept", "text/html");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) return false;
            int bytes = readHtmlPreview(connection.getInputStream());
            return isUsableHtmlResponse(code, connection.getContentType(), bytes);
        } catch (IOException | RuntimeException ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** A small robots.txt/204 response is NOT proof that a web page can load. */
    static boolean isUsableHtmlResponse(int code, String contentType, int bytes) {
        return code == HttpURLConnection.HTTP_OK && contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html")
                && bytes >= 512;
    }

    /** Read only a preview, so a large or endless HTML page cannot stall the VPN verifier. */
    private static int readHtmlPreview(InputStream input) throws IOException {
        try (InputStream stream = input) {
            byte[] bytes = new byte[1024];
            int total = 0;
            while (total < bytes.length && !Thread.currentThread().isInterrupted()) {
                int count = stream.read(bytes, total, bytes.length - total);
                if (count <= 0) break;
                total += count;
            }
            return total;
        }
    }


    /** Probe the actual YouTube homepage, rather than the easily fetched robots.txt. */
    static String probeYouTube(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network vpn = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = vpn == null ? null : manager.getNetworkCapabilities(vpn);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            return "youtube_network_changed";

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) vpn.openConnection(
                    new URL("https://www.youtube.com/"), java.net.Proxy.NO_PROXY);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/html");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (!vpn.equals(manager.getActiveNetwork())) return "youtube_network_changed";
            if (code != HttpURLConnection.HTTP_OK) return "youtube_page_http_" + code;
            int bytes = readHtmlPreview(connection.getInputStream());
            if (!vpn.equals(manager.getActiveNetwork())) return "youtube_network_changed";
            return isUsableHtmlResponse(code, connection.getContentType(), bytes)
                    ? "youtube_page_verified" : "youtube_page_content_failed";
        } catch (java.net.UnknownHostException error) {
            return "youtube_dns_failed";
        } catch (java.net.SocketTimeoutException error) {
            return "youtube_timeout";
        } catch (javax.net.ssl.SSLException error) {
            return "youtube_tls_failed";
        } catch (java.io.IOException | RuntimeException error) {
            return "youtube_io_failed";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Distinguish a DNS lookup failure from a failed HTTPS path without changing VPN status. */
    private static String probeWithoutDns(ConnectivityManager manager, Network vpn) {
        if (!vpn.equals(manager.getActiveNetwork())) {
            return "شبکه پیش از آزمون مستقیم IP تغییر کرد";
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) vpn.openConnection(
                    new URL(DNS_INDEPENDENT_PROBE_URL), java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(DNS_INDEPENDENT_TIMEOUT_MS);
            connection.setReadTimeout(DNS_INDEPENDENT_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (!vpn.equals(manager.getActiveNetwork())) {
                return "شبکه هنگام آزمون مستقیم IP تغییر کرد";
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return "مسیر IP مستقیم پاسخ HTTP " + code
                        + " داد؛ سلامت عبور HTTPS تأیید نشد";
            }
            byte[] response;
            try (InputStream input = connection.getInputStream()) {
                response = readLimited(input, MAX_RESPONSE_BYTES);
            }
            String trace = new String(response, StandardCharsets.UTF_8);
            if (!trace.contains("ip=") || !trace.contains("colo=")) {
                return "پاسخ IP مستقیم دریافت شد اما قالب پاسخ مورد انتظار نبود";
            }
            return "آزمون HTTPS مستقیم IP از مسیر VPN موفق بود؛ احتمال اختلال DNS وجود دارد";
        } catch (Exception error) {
            return "آزمون HTTPS بدون DNS هم ناموفق بود (" + error.getClass().getSimpleName() + ")";
        } finally {
            if (connection != null) connection.disconnect();
        }
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
        // Reject malformed IPv4 before InetAddress parsing; never resolve arbitrary hostnames.
        if (ipv4) {
            String[] octets = value.split("\\.");
            for (String octet : octets) {
                if (octet.length() > 1 && octet.charAt(0) == '0') return false;
                if (Integer.parseInt(octet) > 255) return false;
            }
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            int third = Integer.parseInt(octets[2]);
            if (first == 0 || first >= 224 || first == 10 || first == 127
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 192 && second == 0 && third == 2)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)) return false;
        }
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