package com.rahyab.next;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import android.net.Uri;
import android.util.Base64;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Validates and summarizes a sing-box JSON profile before it is handed to libbox.
 *
 * This class deliberately does not start a VPN. A profile being syntactically valid
 * is not evidence that its server is reachable or that a tunnel has been established.
 */
public final class SingBoxProfile {
    private static final Set<String> CONNECTABLE_TYPES = new HashSet<>(Arrays.asList(
            "vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic",
            "socks", "http", "wireguard"
    ));
    private static final Set<String> GROUP_TYPES = new HashSet<>(Arrays.asList(
            "selector", "urltest"
    ));

    private final String rawJson;
    private final String outboundTag;
    private final String protocol;

    private SingBoxProfile(String rawJson, String outboundTag, String protocol) {
        this.rawJson = rawJson;
        this.outboundTag = outboundTag;
        this.protocol = protocol;
    }

    public static SingBoxProfile parse(String rawJson) throws JSONException {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            throw new JSONException("فایل پیکربندی خالی است");
        }

        JSONObject root = new JSONObject(rawJson);
        JSONArray outbounds = root.optJSONArray("outbounds");
        if (outbounds == null || outbounds.length() == 0) {
            throw new JSONException("فهرست outbounds در پیکربندی پیدا نشد");
        }

        JSONObject route = root.optJSONObject("route");
        String finalTag = route == null ? "" : route.optString("final", "").trim();
        // Accept the legacy root-level field as a compatibility fallback.
        if (finalTag.isEmpty()) finalTag = root.optString("final", "").trim();
        JSONObject selected = findByTag(outbounds, finalTag);
        if (selected != null) {
            selected = resolveOutbound(outbounds, selected, new HashSet<String>());
        } else {
            selected = findFirstConnectable(outbounds);
        }
        if (selected == null) {
            throw new JSONException("خروجی نهایی به یک سرور پشتیبانی‌شده نمی‌رسد");
        }

        String type = selected.optString("type", "").trim().toLowerCase();
        String tag = selected.optString("tag", type).trim();
        if (tag.isEmpty()) tag = type;

        // Most remote outbound types require a host and port. These checks catch
        // incomplete profiles early; libbox remains responsible for full validation.
        if ("wireguard".equals(type)) {
            JSONArray peers = selected.optJSONArray("peers");
            if (peers == null || peers.length() == 0) {
                throw new JSONException("فهرست همتاهای WireGuard خالی یا نامعتبر است");
            }
            JSONObject peer = peers.optJSONObject(0);
            if (peer == null || peer.optString("server", "").trim().isEmpty()) {
                throw new JSONException("نشانی سرور WireGuard معتبر نیست");
            }
            int port = peer.optInt("server_port", 0);
            if (port < 1 || port > 65535) {
                throw new JSONException("پورت سرور WireGuard معتبر نیست");
            }
        } else {
            String server = selected.optString("server", "").trim();
            int port = selected.optInt("server_port", 0);
            if (server.isEmpty() || port < 1 || port > 65535) {
                throw new JSONException("نشانی سرور یا شماره پورت معتبر نیست");
            }
        }

        return new SingBoxProfile(rawJson, tag, type);
    }

    private static JSONObject resolveOutbound(JSONArray all, JSONObject candidate,
                                                Set<String> visited) {
        if (candidate == null) return null;
        String type = candidate.optString("type", "").trim().toLowerCase();
        if (CONNECTABLE_TYPES.contains(type)) return candidate;
        if (!GROUP_TYPES.contains(type)) return null;

        String tag = candidate.optString("tag", "").trim();
        if (!tag.isEmpty() && !visited.add(tag)) return null;

        JSONArray members = candidate.optJSONArray("outbounds");
        if (members == null) return null;

        // A selector may declare a default member. Prefer it over list order;
        // urltest has no measured winner at import time, so inspect its members.
        String defaultTag = candidate.optString("default", "").trim();
        if (!defaultTag.isEmpty()) {
            JSONObject defaultMember = findByTag(all, defaultTag);
            JSONObject resolvedDefault = resolveOutbound(all, defaultMember, new HashSet<>(visited));
            if (resolvedDefault != null) return resolvedDefault;
        }
        for (int i = 0; i < members.length(); i++) {
            String memberTag = members.optString(i, "").trim();
            if (memberTag.equals(defaultTag)) continue;
            JSONObject member = findByTag(all, memberTag);
            JSONObject resolved = resolveOutbound(all, member, new HashSet<>(visited));
            if (resolved != null) return resolved;
        }
        return null;
    }

    private static JSONObject findByTag(JSONArray outbounds, String tag) {
        if (tag == null || tag.trim().isEmpty()) return null;
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject item = outbounds.optJSONObject(i);
            if (item != null && tag.equals(item.optString("tag", ""))) return item;
        }
        return null;
    }

    private static JSONObject findFirstConnectable(JSONArray outbounds) {
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject item = outbounds.optJSONObject(i);
            if (item != null && CONNECTABLE_TYPES.contains(
                    item.optString("type", "").trim().toLowerCase())) return item;
        }
        return null;
    }


    /** Converts a common VLESS share URI into a minimal sing-box profile.
     * Supports TCP, WebSocket and gRPC transports, with TLS or REALITY.
     */
    public static String fromShareLink(String link) throws JSONException {
        if (link == null || !link.trim().toLowerCase().startsWith("vless://")) {
            throw new JSONException("در این مرحله فقط لینک VLESS پشتیبانی می‌شود");
        }
        Uri uri = Uri.parse(link.trim());
        String uuid = uri.getUserInfo();
        String host = uri.getHost();
        int port = uri.getPort();
        if (uuid == null || uuid.trim().isEmpty() || host == null ||
                port < 1 || port > 65535) {
            throw new JSONException("شناسه، آدرس یا پورت لینک VLESS ناقص است");
        }
        String name = uri.getFragment();
        if (name == null || name.trim().isEmpty()) name = host;
        String security = uri.getQueryParameter("security");
        String network = uri.getQueryParameter("type");
        if (network == null || network.isEmpty()) network = "tcp";
        if (!"tcp".equals(network) && !"ws".equals(network) && !"grpc".equals(network)) {
            throw new JSONException("نوع انتقال این لینک هنوز پشتیبانی نمی‌شود: " + network);
        }

        JSONObject server = new JSONObject();
        server.put("type", "vless");
        server.put("tag", name);
        server.put("server", host);
        server.put("server_port", port);
        server.put("uuid", uuid);
        String flow = uri.getQueryParameter("flow");
        if (flow != null && !flow.isEmpty()) server.put("flow", flow);

        if ("tls".equalsIgnoreCase(security) || "reality".equalsIgnoreCase(security)) {
            JSONObject tls = new JSONObject();
            tls.put("enabled", true);
            String sni = uri.getQueryParameter("sni");
            if (sni == null || sni.isEmpty()) sni = host;
            tls.put("server_name", sni);
            String fp = uri.getQueryParameter("fp");
            if (fp != null && !fp.isEmpty()) tls.put("utls", new JSONObject()
                    .put("enabled", true).put("fingerprint", fp));
            if ("reality".equalsIgnoreCase(security)) {
                String publicKey = uri.getQueryParameter("pbk");
                if (publicKey == null || publicKey.isEmpty()) {
                    throw new JSONException("کلید عمومی REALITY (pbk) در لینک وجود ندارد");
                }
                JSONObject reality = new JSONObject().put("enabled", true)
                        .put("public_key", publicKey);
                String shortId = uri.getQueryParameter("sid");
                if (shortId != null && !shortId.isEmpty()) reality.put("short_id", shortId);
                tls.put("reality", reality);
            }
            server.put("tls", tls);
        } else if (security != null && !"none".equalsIgnoreCase(security)) {
            throw new JSONException("روش امنیتی این لینک پشتیبانی نمی‌شود: " + security);
        }

        if ("ws".equals(network)) {
            JSONObject transport = new JSONObject().put("type", "ws");
            String path = uri.getQueryParameter("path");
            if (path != null && !path.isEmpty()) transport.put("path", path);
            String hostHeader = uri.getQueryParameter("host");
            if (hostHeader != null && !hostHeader.isEmpty()) {
                transport.put("headers", new JSONObject().put("Host", hostHeader));
            }
            server.put("transport", transport);
        } else if ("grpc".equals(network)) {
            String serviceName = uri.getQueryParameter("serviceName");
            if (serviceName == null) serviceName = uri.getQueryParameter("service_name");
            JSONObject transport = new JSONObject().put("type", "grpc");
            if (serviceName != null && !serviceName.isEmpty()) transport.put("service_name", serviceName);
            server.put("transport", transport);
        }

        JSONArray outbounds = new JSONArray();
        outbounds.put(server);
        outbounds.put(new JSONObject().put("type", "direct").put("tag", "direct"));
        JSONObject tun = new JSONObject().put("type", "tun").put("tag", "tun-in")
                .put("inet4_address", new JSONArray().put("172.19.0.1/30"))
                .put("mtu", 1400).put("auto_route", true).put("strict_route", true);
        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("level", "info"));
        root.put("inbounds", new JSONArray().put(tun));
        root.put("outbounds", outbounds);
        root.put("route", new JSONObject().put("final", name));
        return root.toString();
    }


    /** Imports common VMess (base64 JSON) and Trojan share links. */
    public static String fromOtherShareLink(String link) throws JSONException {
        if (link == null) throw new JSONException("لینک خالی است");
        String value = link.trim();
        if (value.regionMatches(true, 0, "vmess://", 0, 8)) {
            String payload = value.substring(8).trim();
            int hash = payload.indexOf('#');
            if (hash >= 0) payload = payload.substring(0, hash);
            try {
                byte[] decoded = Base64.decode(payload, Base64.DEFAULT);
                JSONObject source = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
                // In common VMess JSON, "add" is the actual server address while
                // "host" is frequently only the WebSocket Host header. Never use the
                // latter as a silent fallback for the server address.
                String host = source.optString("add", "").trim();
                int port = parsePort(source.optString("port", ""));
                String uuid = source.optString("id", "").trim();
                if (host.isEmpty() || port < 1 || port > 65535 || uuid.isEmpty()) {
                    throw new JSONException("آدرس اصلی (add)، شناسه یا پورت سرور VMess ناقص است");
                }
                String name = source.optString("ps", "").trim();
                if (name.isEmpty()) name = host;
                JSONObject outbound = new JSONObject().put("type", "vmess")
                        .put("tag", name).put("server", host).put("server_port", port)
                        .put("uuid", uuid).put("security", source.optString("scy", "auto"));
                String net = source.optString("net", "tcp").trim().toLowerCase();
                String hostHeader = source.optString("host", "").trim();
                if ("ws".equals(net)) {
                    JSONObject transport = new JSONObject().put("type", "ws");
                    String path = source.optString("path", "");
                    if (!path.isEmpty()) transport.put("path", path);
                    if (!hostHeader.isEmpty()) transport.put("headers",
                            new JSONObject().put("Host", hostHeader));
                    outbound.put("transport", transport);
                } else if (!"tcp".equals(net)) {
                    throw new JSONException("انتقال VMess از نوع " + net + " فعلاً پشتیبانی نمی‌شود");
                }
                if ("tls".equalsIgnoreCase(source.optString("tls", ""))) {
                    String sni = source.optString("sni", "").trim();
                    if (sni.isEmpty()) sni = hostHeader.isEmpty() ? host : hostHeader;
                    outbound.put("tls", new JSONObject().put("enabled", true).put("server_name", sni));
                }
                return buildSingleServerProfile(outbound, name);
            } catch (IllegalArgumentException e) {
                throw new JSONException("کدگذاری لینک VMess معتبر نیست");
            }
        }
        if (value.regionMatches(true, 0, "trojan://", 0, 9)) {
            Uri uri = Uri.parse(value);
            String password = uri.getUserInfo();
            String host = uri.getHost();
            int port = uri.getPort();
            if (password == null || password.isEmpty() || host == null || port < 1 || port > 65535) {
                throw new JSONException("رمز، نشانی یا پورت لینک Trojan ناقص است");
            }
            String name = uri.getFragment();
            if (name == null || name.isEmpty()) name = host;
            JSONObject outbound = new JSONObject().put("type", "trojan")
                    .put("tag", name).put("server", host).put("server_port", port)
                    .put("password", password);
            String security = uri.getQueryParameter("security");
            if (!"none".equalsIgnoreCase(security)) {
                String sni = uri.getQueryParameter("sni");
                if (sni == null || sni.isEmpty()) sni = host;
                outbound.put("tls", new JSONObject().put("enabled", true).put("server_name", sni));
            }
            String net = uri.getQueryParameter("type");
            if ("ws".equals(net)) {
                JSONObject transport = new JSONObject().put("type", "ws");
                String path = uri.getQueryParameter("path");
                if (path != null && !path.isEmpty()) transport.put("path", path);
                outbound.put("transport", transport);
            } else if (net != null && !net.isEmpty() && !"tcp".equals(net)) {
                throw new JSONException("نوع انتقال Trojan هنوز پشتیبانی نمی‌شود: " + net);
            }
            return buildSingleServerProfile(outbound, name);
        }
        throw new JSONException("این لینک VMess یا Trojan نیست");
    }

    private static int parsePort(String raw) {
        try { return Integer.parseInt(raw); } catch (Exception ignored) { return 0; }
    }

    private static String buildSingleServerProfile(JSONObject server, String name)
            throws JSONException {
        JSONArray outbounds = new JSONArray().put(server)
                .put(new JSONObject().put("type", "direct").put("tag", "direct"));
        JSONObject tun = new JSONObject().put("type", "tun").put("tag", "tun-in")
                .put("inet4_address", new JSONArray().put("172.19.0.1/30"))
                .put("mtu", 1400).put("auto_route", true).put("strict_route", true);
        return new JSONObject().put("log", new JSONObject().put("level", "info"))
                .put("inbounds", new JSONArray().put(tun)).put("outbounds", outbounds)
                .put("route", new JSONObject().put("final", name)).toString();
    }

    public String getRawJson() { return rawJson; }
    public String getOutboundTag() { return outboundTag; }
    public String getProtocol() { return protocol; }
}
