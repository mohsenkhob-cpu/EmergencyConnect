package com.rahyab.next;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
    private static final Set<String> NON_PROXY_TYPES = new HashSet<>(Arrays.asList(
            "direct", "block", "dns", "selector", "urltest"
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

        String selectedTag = root.optString("final", "");
        JSONObject selected = findByTag(outbounds, selectedTag);
        if (selected == null) {
            selected = findConnectableOutbound(outbounds);
        }
        if (selected == null) {
            throw new JSONException("هیچ خروجی پشتیبانی‌شده‌ای در پیکربندی پیدا نشد");
        }

        String type = selected.optString("type", "").trim().toLowerCase();
        if (!CONNECTABLE_TYPES.contains(type)) {
            throw new JSONException("نوع اتصال در این نسخه پشتیبانی نمی‌شود: " + type);
        }

        String tag = selected.optString("tag", type).trim();
        if (tag.isEmpty()) tag = type;

        // Most remote outbound types require a host and port. These checks catch
        // incomplete profiles early; libbox remains responsible for full validation.
        if (!"wireguard".equals(type)) {
            String server = selected.optString("server", "").trim();
            int port = selected.optInt("server_port", 0);
            if (server.isEmpty() || port < 1 || port > 65535) {
                throw new JSONException("نشانی سرور یا شماره پورت معتبر نیست");
            }
        }

        return new SingBoxProfile(rawJson, tag, type);
    }

    private static JSONObject findByTag(JSONArray outbounds, String tag) {
        if (tag == null || tag.trim().isEmpty()) return null;
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject item = outbounds.optJSONObject(i);
            if (item != null && tag.equals(item.optString("tag", ""))) return item;
        }
        return null;
    }

    private static JSONObject findConnectableOutbound(JSONArray outbounds) {
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject item = outbounds.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "").trim().toLowerCase();
            if (CONNECTABLE_TYPES.contains(type) && !NON_PROXY_TYPES.contains(type)) return item;
        }
        return null;
    }

    public String getRawJson() { return rawJson; }
    public String getOutboundTag() { return outboundTag; }
    public String getProtocol() { return protocol; }
}
