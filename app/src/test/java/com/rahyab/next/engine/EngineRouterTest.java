package com.rahyab.next.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EngineRouterTest {
    private final EngineRouter router = new EngineRouter();

    @Test
    public void routesSingBoxProtocolsToIntegratedEngine() {
        String[] schemes = {
                "vless", "vmess", "trojan", "ss", "shadowsocks",
                "hysteria", "hysteria2", "tuic"
        };
        for (String scheme : schemes) {
            assertEquals(scheme, EngineType.SING_BOX,
                    router.route(new VpnProfile(scheme + "://example", "test")));
        }
    }

    @Test
    public void routesWireGuardAliasesToWireGuardEngine() {
        assertEquals(EngineType.WIREGUARD,
                router.route(new VpnProfile("wireguard://example", "test")));
        assertEquals(EngineType.WIREGUARD,
                router.route(new VpnProfile("wg://example", "test")));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void doesNotPretendOpenVpnIsIntegrated() {
        router.route(new VpnProfile("openvpn://example", "test"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void rejectsOvpnUntilNativeAdapterIsIntegrated() {
        router.route(new VpnProfile("ovpn://example", "test"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownProtocol() {
        router.route(new VpnProfile("unknown://example", "test"));
    }
}
