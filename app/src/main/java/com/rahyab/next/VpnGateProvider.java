package com.rahyab.next;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and parses the official VPN Gate public CSV feed.
 *
 * This class only discovers OpenVPN profiles. It does not test VPN connectivity,
 * does not claim that a relay is private, and does not start a tunnel.
 */
public final class VpnGateProvider {
    private static final String OFFICIAL_API = "https://www.vpngate.net/api/iphone/";
    private static final int CONNECT_TIMEOUT_MS = 9000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PROFILE_BYTES = 256 * 1024;
    private static final Pattern REMOTE_LINE =
            Pattern.compile("(?m)^\\s*remote\\s+(\\S+)\\s+(\\d{1,5})(?:\\s|$)");

    private VpnGateProvider() { }

    public static final class Server {
        public final String hostName;
        public final String ip;
        public final String country;
        public final long advertisedSpeedBytesPerSecond;
        public final int advertisedPingMs;
        public final int port;
        public final String openVpnProfile;
        public final String source;

        private Server(String hostName, String ip, String country, long speed,
                       int ping, int port, String profile) {
            this.hostName = hostName;
            this.ip = ip;
            this.country = country;
            this.advertisedSpeedBytesPerSecond = speed;
            this.advertisedPingMs = ping;
            this.port = port;
            this.openVpnProfile = profile;
            this.source = "VPN Gate رسمی";
        }

        public String endpointKey() {
            return ip + ":" + port;
        }
    }

    public static final class Result {
        public final List<Server> servers;
        public final String source;
        public final int responseBytes;

        private Result(List<Server> servers, int responseBytes) {
            this.servers = Collections.unmodifiableList(servers);
            this.source = OFFICIAL_API;
            this.responseBytes = responseBytes;
        }
    }

    /** Must be called from a worker thread, never from Android's main thread. */
    public static Result fetch() throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(OFFICIAL_API).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "RahYabAndroid/0.3");
            connection.setRequestProperty("Accept", "text/plain,*/*");

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("VPN Gate پاسخ HTTP " + code + " برگرداند");
            }

            byte[] body;
            try (InputStream input = connection.getInputStream()) {
                body = readLimited(input, MAX_RESPONSE_BYTES);
            }
            String csv = new String(body, StandardCharsets.UTF_8);
            List<Server> parsed = parse(csv);
            if (parsed.isEmpty()) {
                throw new IOException("منبع رسمی پاسخ داد، اما پروفایل OpenVPN معتبری در پاسخ پیدا نشد");
            }
            return new Result(parsed, body.length);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static List<Server> parse(String csv) throws IOException {
        List<Server> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean headerFound = false;

        for (String line : csv.split("\\r?\\n")) {
            if (line.startsWith("#HostName")) {
                headerFound = true;
                continue;
            }
            if (!headerFound || line.isEmpty() || line.startsWith("#")) continue;

            List<String> fields = parseCsvLine(line);
            if (fields.size() < 15) continue;

            try {
                String hostName = fields.get(0).trim();
                String ip = fields.get(1).trim();
                if (!isPlausibleIpv4(ip)) continue;

                long speed = parseLong(fields.get(4));
                int ping = parseInt(fields.get(3));
                String country = fields.get(5).trim();

                byte[] decoded = Base64.decode(fields.get(14).trim(), Base64.DEFAULT);
                if (decoded.length == 0 || decoded.length > MAX_PROFILE_BYTES) continue;
                String profile = new String(decoded, StandardCharsets.UTF_8);
                if (!looksLikeOpenVpnProfile(profile)) continue;

                Matcher matcher = REMOTE_LINE.matcher(profile);
                if (!matcher.find()) continue;
                String remoteHost = matcher.group(1);
                int port = Integer.parseInt(matcher.group(2));
                if (port < 1 || port > 65535) continue;

                // Reject malformed or unexpected endpoints rather than trusting CSV text.
                if (!remoteHost.equals(ip) && !remoteHost.matches("[A-Za-z0-9.-]{1,253}")) continue;

                String key = ip + ":" + port;
                if (seen.add(key)) {
                    result.add(new Server(hostName, ip, country, speed, ping, port, profile));
                }
            } catch (IllegalArgumentException | NumberFormatException ignored) {
                // A malformed row must not invalidate otherwise usable rows.
            }
        }

        result.sort(Comparator
                .comparingLong((Server s) -> s.advertisedSpeedBytesPerSecond).reversed()
                .thenComparingInt(s -> s.advertisedPingMs));
        return result;
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("حجم پاسخ منبع از حد مجاز بیشتر است");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static boolean looksLikeOpenVpnProfile(String profile) {
        String lower = profile.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("client") && REMOTE_LINE.matcher(profile).find();
    }

    private static boolean isPlausibleIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value.trim()); }
        catch (RuntimeException ignored) { return Integer.MAX_VALUE; }
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.trim()); }
        catch (RuntimeException ignored) { return 0L; }
    }

    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        fields.add(field.toString());
        return fields;
    }
}
