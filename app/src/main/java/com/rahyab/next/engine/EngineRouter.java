package com.rahyab.next.engine;

import java.util.Locale;

/**
 * Routes only to engine adapters that are actually integrated in the active :app module.
 * A protocol name is not evidence that an engine implementation is present.
 */
public final class EngineRouter {
    public EngineType route(VpnProfile profile) {
        if (profile == null) throw new IllegalArgumentException("VPN profile is null");
        String scheme = profile.getScheme().toLowerCase(Locale.ROOT);

        switch (scheme) {
            case "vless":
            case "vmess":
            case "ss":
            case "shadowsocks":
            case "trojan":
            case "hysteria":
            case "hysteria2":
            case "tuic":
                return EngineType.SING_BOX;
            case "wireguard":
            case "wg":
                return EngineType.WIREGUARD;
            case "ovpn":
            case "openvpn":
                throw new UnsupportedOperationException(
                        "موتور داخلی OpenVPN هنوز به ماژول اجرایی راه‌یاب متصل نشده است");
            default:
                throw new IllegalArgumentException("پروتکل این فایل هنوز پشتیبانی نمی‌شود: " + scheme);
        }
    }
}
