package com.rahyab.next.engine;

import java.util.Locale;

/**
 * Routes profiles to the matching engine family.
 * This class selects an adapter only; it does not claim that a tunnel is connected.
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
                return EngineType.OPENVPN;
            default:
                throw new IllegalArgumentException("پروتکل این فایل هنوز پشتیبانی نمی‌شود: " + scheme);
        }
    }
}
