package com.rahyab.next;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class XrayProfileTest {
    @Test public void dnsUsesTunnelAndOnlyRelayBootstrapsLocally() throws Exception {
        String link = "vless://00000000-0000-4000-8000-000000000000@relay.example.com:443"
                + "?security=reality&flow=xtls-rprx-vision&sni=relay.example.com"
                + "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        JSONObject config = new JSONObject(XrayProfile.fromInput(link));
        JSONObject dns = config.getJSONObject("dns");
        assertEquals("full:relay.example.com",
                dns.getJSONArray("servers").getJSONObject(0).getJSONArray("domains").getString(0));
        assertEquals("https://1.1.1.1/dns-query", dns.getJSONArray("servers").getString(1));
        assertEquals("https://8.8.8.8/dns-query", dns.getJSONArray("servers").getString(2));
        assertEquals("vless", config.getJSONArray("outbounds").getJSONObject(0).getString("protocol"));
        JSONObject rule = config.getJSONObject("routing").getJSONArray("rules").getJSONObject(0);
        assertEquals("android-tun", rule.getJSONArray("inboundTag").getString(0));
        assertEquals("53", rule.getString("port"));
        assertEquals(config.getJSONArray("outbounds").getJSONObject(1).getString("tag"),
                rule.getString("outboundTag"));
    }
}
