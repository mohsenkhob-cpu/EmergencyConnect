package com.rahyab.next;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Public third-party relay discovery. Never execute a downloaded root configuration. */
public final class PublicProxyProvider {
    private static final String[] BASES = {
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/",
        "https://cdn.jsdelivr.net/gh/0xRadikal/Free-v2ray-Configs@main/"
    };
    public interface Connections { URLConnection open(URL url) throws IOException; }
    public static final class Result {
        public final String profile;
        public final int count;
        public final String source;
        Result(String profile, int count, String source) {
            this.profile = profile; this.count = count; this.source = source;
        }
    }
    public static Result fetch(Connections connections, BooleanSupplier cancelled) throws Exception {
        List<String> failures = new ArrayList<>();
        for (String base : BASES) {
            check(cancelled);
            try {
                JSONObject index = new JSONObject(read(connections, base + "index.json", 65536, cancelled));
                validateFreshness(index.getString("updated_at"), System.currentTimeMillis());
                // Fixed path, never a URL supplied by the subscription.
                JSONArray nodes = sanitize(new JSONObject(read(connections,
                        base + "secure/singbox.json", 4 * 1024 * 1024, cancelled)));
                check(cancelled);
                return new Result(buildProfile(nodes), nodes.length(), new URL(base).getHost());
            } catch (InterruptedException stop) { throw stop; }
            catch (Exception error) { failures.add(new URL(base).getHost() + ": " + error.getClass().getSimpleName()); }
        }
        throw new IOException("دریافت فهرست جایگزین ناموفق بود؛ " + String.join("؛ ", failures));
    }
    static void validateFreshness(String timestamp, long now) throws IOException {
        try {
            long age = now - Instant.parse(timestamp).toEpochMilli();
            if (age < -600000L || age > 6 * 60 * 60 * 1000L) throw new IOException("Stale subscription");
        } catch (java.time.DateTimeException error) { throw new IOException("Invalid subscription timestamp"); }
    }
    private static void check(BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelled");
    }
    private static String read(Connections factory, String address, int limit, BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        HttpURLConnection connection = (HttpURLConnection) factory.open(new URL(address));
        try {
            connection.setConnectTimeout(6000); connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "RahYab/alternative-discovery");
            if (connection.getResponseCode() != 200) throw new IOException("HTTP failure");
            long deadline = System.nanoTime() + 15000000000L;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) {
                    check(cancelled);
                    if (System.nanoTime() > deadline || out.size() + n > limit) throw new IOException("Feed limit");
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
    static JSONArray sanitize(JSONObject root) throws Exception {
        JSONArray input = root.getJSONArray("outbounds");
        List<JSONObject> accepted = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            JSONObject node = input.optJSONObject(i);
            if (node == null) continue;
            try {
                JSONObject clean = sanitizeNode(node);
                String key = clean.getString("server") + ":" + clean.getInt("server_port");
                if (seen.add(key)) accepted.add(clean);
            } catch (Exception ignored) { /* Unsupported or unsafe nodes are not imported. */ }
        }
        if (accepted.isEmpty()) throw new IOException("No compatible TLS relay");
        // Rotate the bounded pool instead of always retesting the same first rows.
        Collections.shuffle(accepted);
        JSONArray result = new JSONArray();
        for (JSONObject node : accepted) {
            if (result.length() == 40) break;
            node.put("tag", "relay-" + (result.length() + 1)); result.put(node);
        }
        return result;
    }
    private static JSONObject sanitizeNode(JSONObject node) throws Exception {
        String type = node.getString("type");
        if (!"vless".equals(type) && !"trojan".equals(type)) throw new IOException("Unsupported protocol");
        String host = node.getString("server");
        if (host.length() > 253 || !host.matches("[A-Za-z0-9.-]+") || host.equalsIgnoreCase("localhost")
                || host.endsWith(".local") || host.endsWith(".internal")) throw new IOException("Invalid host");
        if (host.matches("[0-9.]+")) {
            InetAddress ip = InetAddress.getByName(host);
            byte[] b = ip.getAddress();
            if (b.length != 4 || ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                    || ip.isSiteLocalAddress() || ip.isMulticastAddress()
                    || (b[0] & 255) == 0 || (b[0] & 255) >= 224
                    || ((b[0] & 255) == 100 && (b[1] & 255) >= 64 && (b[1] & 255) <= 127))
                throw new IOException("Non-public relay");
        }
        int port = node.getInt("server_port");
        if (port < 1 || port > 65535) throw new IOException("Invalid port");
        JSONObject tls = node.getJSONObject("tls");
        if (!tls.optBoolean("enabled") || tls.optBoolean("insecure")) throw new IOException("TLS required");
        JSONObject cleanTls = new JSONObject().put("enabled", true);
        textFields(tls, cleanTls, "server_name");
        JSONArray alpn = tls.optJSONArray("alpn");
        if (alpn != null && alpn.length() <= 8) {
            JSONArray values = new JSONArray();
            for (int i = 0; i < alpn.length(); i++) values.put(alpn.getString(i));
            cleanTls.put("alpn", values);
        }
        JSONObject utls = tls.optJSONObject("utls");
        if (utls != null && utls.optBoolean("enabled"))
            cleanTls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", "chrome"));
        JSONObject reality = tls.optJSONObject("reality");
        if (reality != null && reality.optBoolean("enabled")) {
            if (!"vless".equals(type)) throw new IOException("Unsupported Reality");
            JSONObject r = new JSONObject().put("enabled", true);
            textFields(reality, r, "public_key", "short_id");
            if (r.optString("public_key").isEmpty()) throw new IOException("Missing key");
            cleanTls.put("reality", r);
            cleanTls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", "chrome"));
        }
        JSONObject clean = new JSONObject().put("type", type).put("server", host)
                .put("server_port", port).put("tls", cleanTls).put("connect_timeout", "6s");
        if ("vless".equals(type)) {
            clean.put("uuid", UUID.fromString(node.getString("uuid")).toString());
            String flow = node.optString("flow");
            if (!flow.isEmpty() && !"xtls-rprx-vision".equals(flow)) throw new IOException("Unsupported flow");
            if (!flow.isEmpty()) clean.put("flow", flow);
        } else {
            String password = node.getString("password");
            if (password.isEmpty() || password.length() > 4096) throw new IOException("Invalid credential");
            clean.put("password", password);
        }
        JSONObject transport = node.optJSONObject("transport");
        if (transport != null) {
            String t = transport.getString("type");
            if (!Arrays.asList("ws", "grpc", "httpupgrade").contains(t)) throw new IOException("Unsupported transport");
            JSONObject tr = new JSONObject().put("type", t);
            if ("grpc".equals(t)) textFields(transport, tr, "service_name");
            else {
                textFields(transport, tr, "path");
                if ("httpupgrade".equals(t)) textFields(transport, tr, "host");
                JSONObject headers = transport.optJSONObject("headers");
                if (headers != null && headers.has("Host")) tr.put("headers", new JSONObject().put("Host", headers.getString("Host")));
            }
            clean.put("transport", tr);
        }
        return clean;
    }
    private static void textFields(JSONObject source, JSONObject dest, String... fields) throws Exception {
        for (String field : fields) if (source.has(field)) {
            String value = source.getString(field);
            if (value.length() > 4096 || value.contains("\n") || value.contains("\r")) throw new IOException("Invalid field");
            dest.put(field, value);
        }
    }
    /** Switch resolver without accepting any DNS controls from the downloaded feed. */
    static String withResolver(String profile, int index) throws Exception {
        JSONObject root = new JSONObject(profile);
        String[] ips = {"1.1.1.1", "8.8.8.8", "9.9.9.9"};
        String[] names = {"cloudflare-dns.com", "dns.google", "dns.quad9.net"};
        int selected = Math.floorMod(index, ips.length);
        JSONArray servers = root.getJSONObject("dns").getJSONArray("servers");
        for (int i = 0; i < servers.length(); i++) {
            JSONObject dns = servers.getJSONObject(i);
            dns.put("server", ips[selected]);
            dns.getJSONObject("tls").put("server_name", names[selected]);
        }
        return root.toString();
    }

    static String buildProfile(JSONArray nodes) throws Exception {
        JSONArray tags = new JSONArray();
        for (int i = 0; i < nodes.length(); i++) tags.put(nodes.getJSONObject(i).getString("tag"));
        JSONObject group = new JSONObject().put("type", "urltest").put("tag", "rahyab-auto")
                .put("outbounds", tags).put("url", "https://www.gstatic.com/generate_204")
                .put("interval", "1m").put("tolerance", 100).put("interrupt_exist_connections", false);
        JSONArray outbounds = new JSONArray().put(group);
        for (int i = 0; i < nodes.length(); i++) outbounds.put(nodes.getJSONObject(i));
        JSONObject tun = new JSONObject().put("type", "tun").put("tag", "tun-in")
                .put("address", new JSONArray().put("172.19.0.1/30")).put("mtu", 1400)
                .put("auto_route", true).put("strict_route", true).put("stack", "mixed");
        JSONObject root = new JSONObject().put("log", new JSONObject().put("level", "warn"))
                .put("inbounds", new JSONArray().put(tun)).put("outbounds", outbounds)
                .put("route", new JSONObject().put("final", "rahyab-auto").put("rules", new JSONArray()
                        .put(new JSONObject().put("port", 53).put("action", "hijack-dns"))));
        SingBoxProfile.addGeneratedProfileDns(root, "rahyab-auto");
        return root.toString();
    }
}

