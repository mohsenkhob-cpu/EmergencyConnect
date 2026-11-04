package com.rahyab.next.engine;

import java.util.Locale;

/** Minimal immutable profile descriptor. Secrets/config payloads are kept out of UI state. */
public final class VpnProfile {
    private final String uri;
    private final String displayName;

    public VpnProfile(String uri, String displayName) {
        if (uri == null || uri.trim().isEmpty()) {
            throw new IllegalArgumentException("VPN profile URI is empty");
        }
        this.uri = uri.trim();
        this.displayName = displayName == null || displayName.trim().isEmpty()
                ? "سرور بدون نام" : displayName.trim();
    }

    public String getUri() { return uri; }
    public String getDisplayName() { return displayName; }

    public String getScheme() {
        int colon = uri.indexOf(':');
        return colon <= 0 ? "" : uri.substring(0, colon).toLowerCase(Locale.ROOT);
    }
}
