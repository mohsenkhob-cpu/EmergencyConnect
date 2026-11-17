package com.rahyab.next;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class XrayProfile {
    private XrayProfile() {}

    static String fromLink(String link) throws Exception {
        URI uri = new URI(link.trim());
        if (!"vless".equals(uri.getScheme()) || uri.getHost() == null ||
                uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() == null ||
                (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException();
        }
        String id = UUID.fromString(uri.getUserInfo()).toString();
        if (!id.equalsIgnoreCase(uri.getUserInfo())) throw new IllegalArgumentException();
        Map<String, String> options = new HashMap<>();
        Set<String> supported = Set.of("security", "type", "flow", "encryption", "sni", "fp",
                "pbk", "sid", "spx", "headerType");
        if (uri.getRawQuery() == null) throw new IllegalArgumentException();
        for (String parameter : uri.getRawQuery().split("&")) {
            String[] part = parameter.split("=", 2);
            if (part.length != 2 || !supported.contains(part[0]) ||
                    options.put(part[0], URLDecoder.decode(part[1], "UTF-8")) != null) {
                throw new IllegalArgumentException();
            }
        }
        String flow = options.get("flow");
        String name = options.getOrDefault("sni", "");
        String key = options.getOrDefault("pbk", "");
        String shortId = options.getOrDefault("sid", "");
        if (!"reality".equals(options.get("security")) ||
                !Set.of("tcp", "raw").contains(options.getOrDefault("type", "tcp")) ||
                !"xtls-rprx-vision".equals(flow) ||
                !"none".equals(options.getOrDefault("encryption", "none")) ||
                !"none".equals(options.getOrDefault("headerType", "none")) ||
                !name.matches("[A-Za-z0-9.-]{1,253}") || !key.matches("[A-Za-z0-9_-]{43}") ||
                !shortId.matches("(?:[0-9a-fA-F]{2}){0,8}")) {
            throw new IllegalArgumentException();
        }

        JSONObject reality = new JSONObject().put("serverName", name).put("password", key)
                .put("shortId", shortId).put("fingerprint", options.getOrDefault("fp", "chrome"))
                .put("spiderX", options.getOrDefault("spx", ""));
        JSONObject stream = new JSONObject().put("network", "raw")
                .put("security", "reality").put("realitySettings", reality);
        JSONObject user = new JSONObject().put("id", id).put("flow", flow).put("encryption", "none");
        JSONObject server = new JSONObject().put("address", uri.getHost()).put("port", uri.getPort())
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
