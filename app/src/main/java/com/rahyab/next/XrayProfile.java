package com.rahyab.next;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class XrayProfile {
    private XrayProfile() {}

    static final class ImportError extends Exception {
        ImportError(String message) { super(message); }
    }

    static String fromInput(String input) throws Exception {
        String trimmed = input.trim();
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
                stream.has("finalMask") || stream.has("sockopt")) {
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
        return buildConfig(endpoint.getHost(), port, user.optString("id"), options);
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
        return buildConfig(uri.getHost(), uri.getPort(), uri.getUserInfo(), options);
    }

    private static String buildConfig(String host, int port, String userId, Map<String, String> options)
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
        JSONObject inbound = new JSONObject().put("protocol", "tun")
                .put("settings", new JSONObject().put("mtu", 1400));
        return new JSONObject().put("log", new JSONObject().put("loglevel", "none")
                        .put("access", "none").put("error", "none"))
                .put("inbounds", new JSONArray().put(inbound))
                .put("outbounds", new JSONArray().put(outbound)).toString();
    }
}
