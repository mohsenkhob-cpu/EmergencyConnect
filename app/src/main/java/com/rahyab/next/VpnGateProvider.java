package com.rahyab.next;

import java.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    // Independent HTTPS mirror of the official VPN Gate CSV response.
    // It is not operated by the University of Tsukuba; each snapshot is pinned
    // by a full commit SHA and checked against its published size and SHA-256.
    private static final String MIRROR_INDEX_RAW =
            "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/latest.json";
    private static final String MIRROR_INDEX_CDN =
            "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@main/latest.json";
    private static final String MIRROR_CDN_BASE =
            "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@";
    private static final String MIRROR_RAW_BASE =
            "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/";
    private static final String MIRROR_LABEL = "آینه HTTPS ثالث از فهرست عمومی VPN Gate";
    private static final int MAX_INDEX_BYTES = 64 * 1024;
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
                       int ping, int port, String profile, String source) {
            this.hostName = hostName;
            this.ip = ip;
            this.country = country;
            this.advertisedSpeedBytesPerSecond = speed;
            this.advertisedPingMs = ping;
            this.port = port;
            this.openVpnProfile = profile;
            this.source = source;
        }

        public String endpointKey() {
            return ip + ":" + port;
        }
    }

    public static final class Result {
        public final List<Server> servers;
        public final String source;
        public final int responseBytes;

        private Result(List<Server> servers, int responseBytes, String source) {
            this.servers = Collections.unmodifiableList(servers);
            this.source = source;
            this.responseBytes = responseBytes;
        }
    }

    /** Must be called from a worker thread, never from Android's main thread. */
    public static Result fetch() throws IOException {
        IOException officialFailure;
        try {
            byte[] body = fetchBytes(OFFICIAL_API, MAX_RESPONSE_BYTES, CONNECT_TIMEOUT_MS,
                    READ_TIMEOUT_MS, true);
            List<Server> parsed = parse(new String(body, StandardCharsets.UTF_8),
                    "VPN Gate رسمی");
            if (parsed.isEmpty()) {
                throw new IOException("منبع رسمی پاسخ داد، اما پروفایل OpenVPN معتبری پیدا نشد");
            }
            return new Result(parsed, body.length, OFFICIAL_API);
        } catch (IOException error) {
            officialFailure = error;
        }

        try {
            return fetchMirror();
        } catch (IOException mirrorFailure) {
            IOException combined = new IOException(
                    "دریافت فهرست از VPN Gate رسمی و آینه HTTPS جایگزین ناموفق بود؛ "
                    + "خطای منبع رسمی: " + safeMessage(officialFailure)
                    + "؛ خطای آینه: " + safeMessage(mirrorFailure));
            combined.addSuppressed(officialFailure);
            combined.addSuppressed(mirrorFailure);
            throw combined;
        }
    }

    private static Result fetchMirror() throws IOException {
        byte[] indexBytes;
        try {
            indexBytes = fetchBytes(MIRROR_INDEX_RAW, MAX_INDEX_BYTES,
                    CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, false);
        } catch (IOException rawError) {
            try {
                indexBytes = fetchBytes(MIRROR_INDEX_CDN, MAX_INDEX_BYTES,
                        CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, false);
            } catch (IOException cdnError) {
                IOException combined = new IOException("شاخص آینه VPN Gate در دسترس نیست: "
                        + safeMessage(rawError) + "؛ " + safeMessage(cdnError));
                combined.addSuppressed(rawError);
                combined.addSuppressed(cdnError);
                throw combined;
            }
        }

        MirrorManifest manifest = parseMirrorManifest(
                new String(indexBytes, StandardCharsets.UTF_8));
        String cdnUrl = MIRROR_CDN_BASE + manifest.commit + "/data/vpngate.csv";
        String rawUrl = MIRROR_RAW_BASE + manifest.commit + "/data/vpngate.csv";
        byte[] csvBytes;
        try {
            csvBytes = fetchBytes(cdnUrl, manifest.bytes, CONNECT_TIMEOUT_MS,
                    READ_TIMEOUT_MS, false);
            verifyMirrorBytes(csvBytes, manifest);
        } catch (IOException cdnError) {
            try {
                csvBytes = fetchBytes(rawUrl, manifest.bytes, CONNECT_TIMEOUT_MS,
                        READ_TIMEOUT_MS, false);
                verifyMirrorBytes(csvBytes, manifest);
            } catch (IOException rawError) {
                IOException combined = new IOException("دریافت یا بررسی فایل آینه ناموفق بود: "
                        + safeMessage(cdnError) + "؛ " + safeMessage(rawError));
                combined.addSuppressed(cdnError);
                combined.addSuppressed(rawError);
                throw combined;
            }
        }

        List<Server> parsed = parse(new String(csvBytes, StandardCharsets.UTF_8), MIRROR_LABEL);
        if (parsed.isEmpty() || parsed.size() > manifest.serverCount) {
            throw new IOException("تعداد پروفایل‌های معتبر آینه با شاخص منتشرشده سازگار نیست");
        }
        return new Result(parsed, csvBytes.length, MIRROR_LABEL
                + "؛ شناسه داده: " + manifest.commit.substring(0, 8));
    }

    static final class MirrorManifest {
        final String commit;
        final String sha256;
        final int bytes;
        final int serverCount;

        MirrorManifest(String commit, String sha256, int bytes, int serverCount) {
            this.commit = commit;
            this.sha256 = sha256;
            this.bytes = bytes;
            this.serverCount = serverCount;
        }
    }

    static MirrorManifest parseMirrorManifest(String json) throws IOException {
        if (json == null || json.length() > MAX_INDEX_BYTES) {
            throw new IOException("حجم شاخص آینه نامعتبر است");
        }
        if (!Pattern.compile("\"schema_version\"\\s*:\\s*1").matcher(json).find()
                || !Pattern.compile("\"source_url\"\\s*:\\s*\"https://www\\.vpngate\\.net/api/iphone/\"")
                        .matcher(json).find()) {
            throw new IOException("نسخه یا منبع ثبت‌شده در شاخص آینه معتبر نیست");
        }
        String commit = capture(json, "\"data_commit\"\\s*:\\s*\"([0-9a-f]{40})\"");
        Matcher file = Pattern.compile("\"data/vpngate\\.csv\"\\s*:\\s*\\{([^}]*)\\}")
                .matcher(json);
        if (!file.find()) throw new IOException("مشخصات فایل CSV در شاخص آینه وجود ندارد");
        String descriptor = file.group(1);
        String bytesText = capture(descriptor, "\"bytes\"\\s*:\\s*(\\d+)");
        String countText = capture(descriptor, "\"server_count\"\\s*:\\s*(\\d+)");
        String sha = capture(descriptor, "\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"");
        try {
            int bytes = Integer.parseInt(bytesText);
            int count = Integer.parseInt(countText);
            if (bytes < 1 || bytes > MAX_RESPONSE_BYTES || count < 1 || count > 5000) {
                throw new IOException("اندازه یا تعداد سرورهای شاخص آینه خارج از محدوده است");
            }
            return new MirrorManifest(commit, sha, bytes, count);
        } catch (NumberFormatException error) {
            throw new IOException("اعداد شاخص آینه معتبر نیستند", error);
        }
    }

    private static String capture(String value, String regex) throws IOException {
        Matcher matcher = Pattern.compile(regex).matcher(value);
        if (!matcher.find()) throw new IOException("فیلد ضروری شاخص آینه نامعتبر یا مفقود است");
        return matcher.group(1);
    }

    static void verifyMirrorBytes(byte[] body, MirrorManifest manifest) throws IOException {
        if (body == null || body.length != manifest.bytes) {
            throw new IOException("اندازه فایل آینه با شاخص منتشرشده مطابقت ندارد");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            if (!hex.toString().equals(manifest.sha256)) {
                throw new IOException("SHA-256 فایل آینه با شاخص منتشرشده مطابقت ندارد");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("الگوریتم SHA-256 در دستگاه موجود نیست", impossible);
        }
    }

    private static byte[] fetchBytes(String address, int maxBytes, int connectTimeout,
                                     int readTimeout, boolean followRedirects) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setInstanceFollowRedirects(followRedirects);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "RahYabAndroid/0.4");
            connection.setRequestProperty("Accept", "text/plain,application/json,*/*");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("پاسخ HTTP " + code + " از " + new URL(address).getHost());
            }
            try (InputStream input = connection.getInputStream()) {
                return readLimited(input, maxBytes);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String safeMessage(IOException error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    static List<Server> parse(String csv) throws IOException {
        return parse(csv, "VPN Gate رسمی");
    }

    static List<Server> parse(String csv, String sourceLabel) throws IOException {
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

                byte[] decoded = Base64.getMimeDecoder().decode(fields.get(14).trim());
                if (decoded.length == 0 || decoded.length > MAX_PROFILE_BYTES) continue;
                String profile = new String(decoded, StandardCharsets.UTF_8);
                if (!looksLikeOpenVpnProfile(profile)) continue;

                Matcher matcher = REMOTE_LINE.matcher(profile);
                if (!matcher.find()) continue;
                String remoteHost = matcher.group(1);
                int port = Integer.parseInt(matcher.group(2));
                if (port < 1 || port > 65535) continue;

                // Accept exactly one remote directive. OpenVPN can rotate across
                // multiple "remote" entries; allowing a second unvalidated endpoint
                // would bypass the CSV-to-profile endpoint binding below.
                if (matcher.find()) continue;

                // Reject malformed or unexpected endpoints rather than trusting CSV text.
                // The profile must connect to the exact IP advertised by this CSV row.
                // Accepting an arbitrary hostname here would let a malformed or altered
                // profile silently redirect the tunnel to an unrelated endpoint.
                if (!remoteHost.equals(ip)) continue;

                String key = ip + ":" + port;
                if (seen.add(key)) {
                    result.add(new Server(hostName, ip, country, speed, ping, port, profile, sourceLabel));
                }
            } catch (IllegalArgumentException ignored) {
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
