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
    private static final String BACKUP_IP_CHECK_URL = "https://icanhazip.com/";
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

    /**
     * Confirm an actual public HTTPS egress IP on Android's currently active VPN.
     * A single blocked echo service must not make a working VPN look disconnected.
     * Crucially, no direct/underlying-network fallback is ever treated as success.
     * Must be called on a worker thread.
     */
    public static Result verify(Context context) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return Result.failure("مدیر اتصال اندروید در دسترس نیست");

        Network vpn = awaitActiveVpn(manager);
        if (vpn == null) {
            return Result.failure("مسیر VPN تا پایان مهلت ۸ ثانیه‌ای در اندروید فعال نشد");
        }

        String lastFailure = "";
        boolean dnsFailed = false;
        String[] endpoints = {IP_CHECK_URL, BACKUP_IP_CHECK_URL};
        for (int index = 0; index < endpoints.length; index++) {
            if (Thread.currentThread().isInterrupted())
                return Result.failure("آزمون اتصال با درخواست کاربر متوقف شد");
            if (!vpn.equals(manager.getActiveNetwork()))
                return Result.failure("مسیر فعال شبکه هنگام بررسی تغییر کرد");

            HttpURLConnection connection = null;
            try {
                // Pin each test to VPN and do not inherit an unrelated system proxy.
                connection = (HttpURLConnection) vpn.openConnection(
                        new URL(endpoints[index]), java.net.Proxy.NO_PROXY);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", index == 0
                        ? "application/json" : "text/plain");
                connection.setRequestProperty("User-Agent", "FilterMohsen/1.0");
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    lastFailure = "سرویس بررسی " + (index + 1) + " پاسخ HTTP " + code + " داد";
                    continue;
                }
                byte[] body;
                try (InputStream input = connection.getInputStream()) {
                    body = readLimited(input, MAX_RESPONSE_BYTES);
                }
                String ip = decodePublicIp(new String(body, StandardCharsets.UTF_8), index == 0);
                if (ip == null) {
                    lastFailure = "سرویس بررسی " + (index + 1) + " نشانی IP عمومی معتبر برنگرداند";
                    continue;
                }
                if (!vpn.equals(manager.getActiveNetwork()))
                    return Result.failure("مسیر فعال شبکه هنگام بررسی تغییر کرد");
                return Result.success(ip);
            } catch (java.net.UnknownHostException e) {
                dnsFailed = true;
                lastFailure = "DNS سرویس بررسی " + (index + 1) + " پاسخ نداد";
            } catch (java.net.SocketTimeoutException e) {
                lastFailure = "مهلت پاسخ سرویس بررسی " + (index + 1) + " تمام شد";
            } catch (Exception e) {
                lastFailure = "آزمون HTTPS سرویس بررسی " + (index + 1)
                        + " ناموفق بود (" + e.getClass().getSimpleName() + ")";
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        if (Thread.currentThread().isInterrupted())
            return Result.failure("آزمون اتصال متوقف شد");
        if (!vpn.equals(manager.getActiveNetwork()))
            return Result.failure("مسیر فعال شبکه هنگام بررسی تغییر کرد");

        // This is diagnostic information only: IP-literal HTTPS cannot
        // validate full VPN DNS and MUST NOT mark the tunnel as connected.
        String diagnostic = dnsFailed ? "؛ " + probeWithoutDns(manager, vpn) : "";
        return Result.failure("هیچ‌کدام از دو سرویس مستقل بررسی IP از مسیر VPN تأیید نشدند: "
                + lastFailure + diagnostic);
    }

    /** Unit-testable decoder: never accept a hostname, HTML, or private address. */
    static String decodePublicIp(String body, boolean json) {
        if (body == null || body.length() > MAX_RESPONSE_BYTES) return null;
        try {
            String candidate = json
                    ? new JSONObject(body).optString("ip", "").trim()
                    : body.trim();
            return isPublicAddressLiteral(candidate) ? candidate : null;
        } catch (Exception error) {
            return null;
        }
    }

    private static volatile String lastXrayProbeStage = "not_checked";
    static String lastXrayProbeStage() { return lastXrayProbeStage; }

    /** Xray can confirm HTTPS even if the IP-reporting endpoint is unavailable. */
    static boolean verifyXray(Context context) {
        Result initial = verify(context);
        if (initial.verified) {
            lastXrayProbeStage = "ip_https_verified";
            return true;
        }
        String reason = initial.message;
        lastXrayProbeStage = reason.contains("DNS") ? "dns_failed"
                : reason.contains("Timeout") || reason.contains("timed out") ? "timeout"
                : reason.contains("مسیر VPN") ? "vpn_route_missing"
                : reason.contains("HTTP ") ? "unexpected_http" : "https_path_failed";
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
            boolean verified = connection.getResponseCode() == HttpURLConnection.HTTP_NO_CONTENT
                    && vpn.equals(manager.getActiveNetwork());
            if (verified) lastXrayProbeStage = "cloudflare_204_verified";
            return verified;
        } catch (java.net.UnknownHostException error) {
            lastXrayProbeStage = "dns_failed";
            return false;
        } catch (java.net.SocketTimeoutException error) {
            lastXrayProbeStage = "https_timeout";
            return false;
        } catch (IOException | RuntimeException ignored) {
            lastXrayProbeStage = "https_io_failed";
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



    /** Verify real HTML, following no more than three HTTPS redirects within the same site. */
    private static String probeMediaPage(Network vpn, String endpoint) {
        try {
            URL origin = new URL(endpoint);
            URL current = origin;
            for (int hop = 0; hop < 4 && !Thread.currentThread().isInterrupted(); hop++) {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) vpn.openConnection(current, java.net.Proxy.NO_PROXY);
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("Connection", "close");
                    connection.setRequestProperty("Accept", "text/html");
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    connection.setConnectTimeout(6000);
                    connection.setReadTimeout(6000);
                    connection.setUseCaches(false);
                    connection.setInstanceFollowRedirects(false);
                    int code = connection.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.isEmpty()) return "redirect_missing_location";
                        URL next = new URL(current, location);
                        if (!isAllowedMediaRedirect(origin, next)) return "redirect_blocked";
                        if (hop == 3) return "redirect_limit";
                        current = next;
                        continue;
                    }
                    if (code != HttpURLConnection.HTTP_OK) return "page_http_" + code;
                    int bytes = readHtmlPreview(connection.getInputStream());
                    return isUsableHtmlResponse(code, connection.getContentType(), bytes)
                            ? "page_verified" : "page_content_failed";
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
            return "redirect_limit";
        } catch (java.net.UnknownHostException error) {
            return "dns_failed";
        } catch (java.net.SocketTimeoutException error) {
            return "timeout";
        } catch (javax.net.ssl.SSLException error) {
            return "tls_failed";
        } catch (IOException | RuntimeException error) {
            return "io_failed";
        }
    }

    static boolean isAllowedMediaRedirect(URL origin, URL destination) {
        if (origin == null || destination == null || !"https".equalsIgnoreCase(destination.getProtocol())
                || destination.getUserInfo() != null
                || (destination.getPort() != -1 && destination.getPort() != 443)) return false;
        String source = origin.getHost().toLowerCase(java.util.Locale.ROOT);
        String target = destination.getHost().toLowerCase(java.util.Locale.ROOT);
        String family = source.equals("youtube.com") || source.endsWith(".youtube.com")
                ? "youtube.com" : source.equals("instagram.com") || source.endsWith(".instagram.com")
                ? "instagram.com" : "";
        return !family.isEmpty() && (target.equals(family) || target.endsWith("." + family));
    }

    private static boolean probeMediaHost(Network vpn, String endpoint) {
        return "page_verified".equals(probeMediaPage(vpn, endpoint));
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



    /** Verify a real YouTube HTML response using the VPN network. */
    static String probeYouTube(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network vpn = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = vpn == null ? null : manager.getNetworkCapabilities(vpn);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            return "youtube_network_changed";
        String result = probeMediaPage(vpn, "https://www.youtube.com/");
        return vpn.equals(manager.getActiveNetwork()) ? "youtube_" + result : "youtube_network_changed";
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