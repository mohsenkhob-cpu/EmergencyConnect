package com.rahyab.next;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Base64;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

final class XrayProfile {
    private XrayProfile() {}

    static final class ImportError extends Exception {
        ImportError(String message) { super(message); }
    }

    static java.util.List<String> fromPublicFeed(String profile) throws Exception {
        return fromPublicFeed(profile, java.util.Set.of(), 5);
    }

    static java.util.List<String> fromPublicFeed(String profile, java.util.Set<String> excluded,
                                                 int limit) throws Exception {
        JSONArray nodes = new JSONObject(profile).getJSONArray("outbounds");
        java.util.List<String> candidates = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < nodes.length() && candidates.size() < limit; i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (!"vless".equals(node.optString("type")) || node.has("transport") ||
                    !"xtls-rprx-vision".equals(node.optString("flow"))) continue;
            JSONObject tls = node.optJSONObject("tls");
            JSONObject reality = tls == null ? null : tls.optJSONObject("reality");
            if (reality == null || !tls.optBoolean("enabled") || tls.optBoolean("insecure")
                    || !reality.optBoolean("enabled")) continue;
            Map<String, String> options = new HashMap<>();
            options.put("flow", node.optString("flow"));
            options.put("security", "reality");
            options.put("sni", tls.optString("server_name"));
            options.put("pbk", reality.optString("public_key"));
            options.put("sid", reality.optString("short_id"));
            try {
                String host = node.getString("server");
                int port = node.getInt("server_port");
                PublicProxyProvider.validateRelayHost(host);
                String key = relayKey(host, port);
                if (port >= 1 && port <= 65535 && !excluded.contains(key) && !seen.contains(key)) {
                    candidates.add(buildConfig(host, port, node.getString("uuid"), options).toString());
                    seen.add(key);
                }
            } catch (ImportError | java.io.IOException | JSONException ignored) {
                // This relay cannot be used by the Xray engine.
            }
        }
        return candidates;
    }

    static java.util.List<String> fromPublicLinks(String body, Set<String> excluded,
                                                  java.util.function.BooleanSupplier cancelled)
            throws InterruptedException {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        Set<String> hosts = new java.util.HashSet<>();
        for (String line : body.split("\\R")) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new InterruptedException("Cancelled");
            String link = line.trim();
            if (!link.startsWith("vless://") || link.length() > 8192) continue;
            try {
                String config = fromLink(link);
                String key = relayKey(config);
                String host = key.substring(0, key.lastIndexOf(':'));
                PublicProxyProvider.validateRelayHost(host);
                if (excluded.contains(key) || !hosts.add(host)) continue;
                candidates.add(config);
                if (candidates.size() == 32) break;
            } catch (Exception ignored) {
                // Ignore malformed or unsupported public links without logging their contents.
            }
        }
        // Preserve the upstream ordering within the preferred and alternate ports.
        candidates.sort(java.util.Comparator.comparing(config -> !configUsesPort443(config)));
        return candidates;
    }

    private static boolean configUsesPort443(String config) {
        try {
            return relayKey(config).endsWith(":443");
        } catch (JSONException impossible) {
            return false;
        }
    }

    static String relayKey(String config) throws JSONException {
        JSONObject server = new JSONObject(config).getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0);
        return relayKey(server.getString("address"), server.getInt("port"));
    }

    private static String relayKey(String host, int port) {
        return host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    static String fromInput(String input) throws Exception {
        String trimmed = input.trim();
        if (trimmed.startsWith("\uFEFF")) trimmed = trimmed.substring(1).trim();
        if (!trimmed.startsWith("{")) return fromLink(trimmed);
        if (trimmed.length() > 128 * 1024) {
            throw new ImportError("حجم تنظیمات JSON بیش از حد مجاز است");
        }

        JSONObject exported;
        try {
            exported = new JSONObject(trimmed);
        } catch (org.json.JSONException | StackOverflowError e) {
            throw new ImportError("متن JSON ناقص است یا ساختار نامعتبر دارد");
        }
        JSONArray outbounds = exported.optJSONArray("outbounds");
        JSONObject selected = null;
        for (int index = 0; outbounds != null && index < outbounds.length(); index++) {
            JSONObject candidate = outbounds.optJSONObject(index);
            if (candidate == null || !"vless".equals(candidate.optString("protocol"))) continue;
            if (selected != null) throw new ImportError("فایل JSON باید فقط یک خروجی VLESS داشته باشد");
            selected = candidate;
        }
        if (selected == null) throw new ImportError("در فایل JSON خروجی VLESS پیدا نشد");

        JSONObject settings = selected.optJSONObject("settings");
        JSONArray servers = settings == null ? null : settings.optJSONArray("vnext");
        JSONObject server = servers != null && servers.length() == 1 ? servers.optJSONObject(0) : null;
        JSONArray users = server == null ? null : server.optJSONArray("users");
        JSONObject user = users != null && users.length() == 1 ? users.optJSONObject(0) : null;
        if (user == null) throw new ImportError("خروجی VLESS باید یک سرور و یک کاربر داشته باشد");

        JSONObject stream = selected.optJSONObject("streamSettings");
        JSONObject reality = stream == null ? null : stream.optJSONObject("realitySettings");
        if (reality == null) throw new ImportError("تنظیمات REALITY در خروجی VLESS پیدا نشد");
        String network = stream.optString("network", "tcp");
        if (!Set.of("tcp", "raw").contains(network))
            throw new ImportError("نوع انتقال باید TCP یا RAW باشد");
        if (selected.has("proxySettings") || stream.has("finalmask") ||
                stream.has("finalMask")) {
            throw new ImportError("خروجی VLESS تنظیمات انتقال اضافی دارد");
        }
        for (String transportKey : Set.of("tcpSettings", "rawSettings")) {
            JSONObject transport = stream.optJSONObject(transportKey);
            JSONObject header = transport == null ? null : transport.optJSONObject("header");
            if (header != null && !Set.of("", "none").contains(header.optString("type"))) {
                throw new ImportError("نوع انتقال باید TCP یا RAW بدون سربرگ باشد");
            }
        }

        String address = server.optString("address");
        int port = server.optInt("port", -1);
        URI endpoint;
        try {
            endpoint = new URI("vless", null, address, port, null, null, null);
        } catch (java.net.URISyntaxException e) {
            throw new ImportError("نشانی سرور در JSON نامعتبر است");
        }
        if (endpoint.getHost() == null || address.length() > 253 || port < 1 || port > 65535)
            throw new ImportError("نشانی یا پورت سرور در JSON نامعتبر است");

        Map<String, String> options = new HashMap<>();
        options.put("type", network);
        options.put("security", stream.optString("security"));
        options.put("flow", user.optString("flow"));
        options.put("encryption", user.optString("encryption", "none"));
        options.put("sni", reality.optString("serverName"));
        options.put("fp", reality.optString("fingerprint"));
        String key = reality.optString("password");
        options.put("pbk", key.isEmpty() ? reality.optString("publicKey") : key);
        options.put("sid", reality.optString("shortId"));
        options.put("spx", reality.optString("spiderX"));
        options.put("pqv", reality.optString("mldsa65Verify"));
        JSONObject config = buildConfig(endpoint.getHost(), port, user.optString("id"), options);
        JSONObject socketOptions = readSocketOptions(stream);
        if (socketOptions != null) {
            config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
                    .put("sockopt", socketOptions);
        }
        JSONObject dns = exported.optJSONObject("dns");
        JSONObject hosts = dns == null ? null : dns.optJSONObject("hosts");
        if (hosts != null && hosts.has(address)) {
            Object pinnedIp = hosts.opt(address);
            JSONArray addresses = pinnedIp instanceof JSONArray ? (JSONArray) pinnedIp
                    : new JSONArray().put(pinnedIp);
            if (addresses.length() == 0 || addresses.length() > 16)
                throw new ImportError("تعداد نشانی‌های ثبت‌شده برای سرور نامعتبر است");
            for (int index = 0; index < addresses.length(); index++) {
                if (!isIpLiteral(addresses.optString(index))) {
                    throw new ImportError("نشانی ثبت‌شده برای سرور در DNS نامعتبر است");
                }
            }
            if (socketOptions == null) {
                socketOptions = new JSONObject();
                config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
                        .put("sockopt", socketOptions);
            }
            if ("asis".equalsIgnoreCase(socketOptions.optString("domainStrategy", "AsIs"))) {
                socketOptions.put("domainStrategy", "UseIP");
            }
            config.getJSONObject("dns").put("hosts", new JSONObject().put(address, pinnedIp));
        }
        return config.toString();
    }

    private static JSONObject readSocketOptions(JSONObject stream) throws Exception {
        if (!stream.has("sockopt")) return null;
        JSONObject source = stream.optJSONObject("sockopt");
        if (source == null) throw new ImportError("ساختار تنظیمات سوکت نامعتبر است");

        JSONObject result = new JSONObject();
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("domainStrategy".equals(key)) {
                String strategy = source.optString(key);
                if (!Set.of("asis", "useip", "useipv4", "useipv6", "useipv4v6", "useipv6v4",
                        "forceip", "forceipv4", "forceipv6", "forceipv4v6", "forceipv6v4")
                        .contains(strategy.toLowerCase(Locale.ROOT))) {
                    throw new ImportError("روش یافتن IP سرور در تنظیمات سوکت پشتیبانی نمی‌شود");
                }
                result.put(key, strategy);
            } else if ("happyEyeballs".equals(key)) {
                JSONObject happy = source.optJSONObject(key);
                if (happy == null) throw new ImportError("ساختار Happy Eyeballs نامعتبر است");
                JSONObject checked = new JSONObject();
                java.util.Iterator<String> happyKeys = happy.keys();
                while (happyKeys.hasNext()) {
                    String setting = happyKeys.next();
                    Object value = happy.opt(setting);
                    boolean valid = switch (setting) {
                        case "prioritizeIPv6" -> value instanceof Boolean;
                        case "interleave", "maxConcurrentTry" ->
                                value instanceof Integer && (Integer) value >= 0 && (Integer) value <= 16;
                        case "tryDelayMs" ->
                                value instanceof Integer && (Integer) value >= 0 && (Integer) value <= 10_000;
                        default -> false;
                    };
                    if (!valid) throw new ImportError("یکی از گزینه‌های Happy Eyeballs نامعتبر است");
                    checked.put(setting, value);
                }
                result.put(key, checked);
            } else {
                throw new ImportError("یکی از گزینه‌های سوکت در این نسخه پشتیبانی نمی‌شود");
            }
        }
        return result;
    }

    private static boolean isIpLiteral(String ip) {
        if (ip.indexOf(':') >= 0 && ip.matches("[0-9A-Fa-f:.]{2,45}")) {
            try {
                return new URI("vless", null, ip, 1, null, null, null).getHost() != null;
            } catch (java.net.URISyntaxException e) {
                return false;
            }
        }
        if (!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        for (String octet : ip.split("\\.")) {
            if (Integer.parseInt(octet) > 255 || (octet.length() > 1 && octet.startsWith("0"))) return false;
        }
        return true;
    }

    static String fromLink(String link) throws Exception {
        String trimmed = link.trim();
        if (!trimmed.startsWith("vless://")) {
            throw new ImportError("لینک کپی‌شده باید با vless:// شروع شود");
        }
        if (trimmed.length() > 4096 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            throw new ImportError("فقط یک لینک سرور را در یک خط وارد کنید");
        }

        URI uri;
        try {
            // The fragment is a display label and may contain unescaped spaces or emoji.
            int labelStart = trimmed.indexOf('#');
            uri = new URI(labelStart < 0 ? trimmed : trimmed.substring(0, labelStart));
        } catch (java.net.URISyntaxException e) {
            throw new ImportError("ساختار لینک نامعتبر است");
        }
        if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535 ||
                (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new ImportError("نشانی یا پورت سرور در لینک نامعتبر است");
        }
        Map<String, String> options = new HashMap<>();
        Set<String> supported = Set.of("security", "type", "flow", "encryption", "sni", "fp",
                "pbk", "sid", "spx", "headerType", "pqv");
        Set<String> emptyExportFields = Set.of("host", "path", "alpn", "mode", "serviceName");
        if (uri.getRawQuery() == null) throw new ImportError("لینک بخش تنظیمات اتصال ندارد");
        for (String parameter : uri.getRawQuery().split("&")) {
            String[] part = parameter.split("=", 2);
            if (part.length != 2) throw new ImportError("یکی از گزینه‌های لینک ساختار نامعتبر دارد");
            String value = URLDecoder.decode(part[1], "UTF-8");
            if (value.isEmpty() && emptyExportFields.contains(part[0])) continue;
            if (!supported.contains(part[0])) {
                throw new ImportError("لینک گزینه‌ای دارد که این نسخه از Xray پشتیبانی نمی‌کند");
            }
            if (options.put(part[0], value) != null) {
                throw new ImportError("یکی از گزینه‌های لینک تکراری است");
            }
        }
        return buildConfig(uri.getHost(), uri.getPort(), uri.getUserInfo(), options).toString();
    }

    private static JSONObject buildConfig(String host, int port, String userId, Map<String, String> options)
            throws Exception {
        String id;
        try {
            if (userId == null) throw new IllegalArgumentException();
            id = UUID.fromString(userId).toString();
            if (!id.equalsIgnoreCase(userId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ImportError("شناسهٔ کاربر در تنظیمات وجود ندارد یا نامعتبر است");
        }
        String flow = options.get("flow");
        String name = options.getOrDefault("sni", "");
        String key = options.getOrDefault("pbk", "");
        String shortId = options.getOrDefault("sid", "");
        if (!"reality".equals(options.get("security")))
            throw new ImportError("امنیت لینک باید REALITY باشد");
        if (!Set.of("tcp", "raw").contains(options.getOrDefault("type", "tcp")) ||
                !Set.of("", "none").contains(options.getOrDefault("headerType", "none")))
            throw new ImportError("نوع انتقال لینک باید TCP یا RAW بدون سربرگ باشد");
        if (!"xtls-rprx-vision".equals(flow))
            throw new ImportError("گزینهٔ Flow باید xtls-rprx-vision باشد");
        if (!"none".equals(options.getOrDefault("encryption", "none")))
            throw new ImportError("روش رمزگذاری VLESS در این نسخه پشتیبانی نمی‌شود");
        if (!name.matches("[A-Za-z0-9.-]{1,253}"))
            throw new ImportError("مقدار SNI در لینک وجود ندارد یا نامعتبر است");
        if (!key.matches("[A-Za-z0-9_-]{43}"))
            throw new ImportError("کلید عمومی REALITY در لینک وجود ندارد یا نامعتبر است");
        if (!shortId.matches("(?:[0-9a-fA-F]{2}){0,8}"))
            throw new ImportError("مقدار Short ID نامعتبر است");

        String verificationKey = options.getOrDefault("pqv", "");
        if (!verificationKey.isEmpty()) {
            try {
                if (verificationKey.length() != 2603 ||
                        Base64.getUrlDecoder().decode(verificationKey).length != 1952)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new ImportError("کلید بررسی ML-DSA در لینک نامعتبر است");
            }
        }
        String fingerprint = options.getOrDefault("fp", "");
        if (!fingerprint.matches("[A-Za-z0-9_-]{0,64}"))
            throw new ImportError("مقدار Fingerprint نامعتبر است");
        String spiderX = options.getOrDefault("spx", "");
        if (spiderX.length() > 4096)
            throw new ImportError("مقدار SpiderX بیش از حد طولانی است");
        JSONObject reality = new JSONObject().put("serverName", name).put("password", key)
                .put("shortId", shortId).put("fingerprint", fingerprint.isEmpty() ? "chrome" : fingerprint)
                .put("spiderX", spiderX);
        if (!verificationKey.isEmpty()) reality.put("mldsa65Verify", verificationKey);
        JSONObject stream = new JSONObject().put("network", "raw")
                .put("security", "reality").put("realitySettings", reality);
        JSONObject user = new JSONObject().put("id", id).put("flow", flow).put("encryption", "none");
        JSONObject server = new JSONObject().put("address", host).put("port", port)
                .put("users", new JSONArray().put(user));
        JSONObject outbound = new JSONObject().put("protocol", "vless").put("streamSettings", stream)
                .put("settings", new JSONObject().put("vnext", new JSONArray().put(server)));
        // Android VpnService already owns the interface. A name here keeps
        // Xray from enumerating system interfaces while it reads the config.
        JSONObject inbound = new JSONObject().put("protocol", "tun").put("tag", "android-tun")
                .put("settings", new JSONObject().put("name", "mohsen0").put("mtu", 1400))
                .put("sniffing", new JSONObject().put("enabled", true).put("routeOnly", false)
                        .put("destOverride", new JSONArray().put("http").put("tls").put("quic")));
        // Bootstrap only the relay hostname with the protected physical resolver.
        // All ordinary A/AAAA queries use DoH via the primary VLESS outbound.
        JSONArray resolvers = new JSONArray();
        if (!isIpLiteral(host)) {
            resolvers.put(new JSONObject().put("address", "localhost").put("skipFallback", true)
                    .put("domains", new JSONArray().put("full:" + host)));
        }
        resolvers.put("https://1.1.1.1/dns-query").put("https://8.8.8.8/dns-query");
        JSONObject dns = new JSONObject().put("servers", resolvers).put("disableFallbackIfMatch", true);
        JSONObject dnsOutbound = new JSONObject().put("protocol", "dns").put("tag", "tunnel-dns");
        JSONObject dnsRule = new JSONObject().put("type", "field")
                .put("inboundTag", new JSONArray().put("android-tun"))
                .put("port", "53").put("network", "tcp,udp").put("outboundTag", "tunnel-dns");
        return new JSONObject().put("log", new JSONObject().put("loglevel", "none")
                        .put("access", "none").put("error", "none"))
                .put("dns", dns)
                .put("inbounds", new JSONArray().put(inbound))
                .put("outbounds", new JSONArray().put(outbound).put(dnsOutbound))
                .put("routing", new JSONObject().put("rules", new JSONArray().put(dnsRule)));
    }
}
