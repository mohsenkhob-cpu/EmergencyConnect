package com.rahyab.next;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.time.Instant;

public class PublicProxyProviderTest {
    private JSONObject relay() throws Exception {
        return new JSONObject().put("type", "vless").put("server", "203.0.113.7")
                .put("server_port", 443).put("uuid", "00000000-0000-4000-8000-000000000001")
                .put("tls", new JSONObject().put("enabled", true).put("server_name", "example.org"));
    }
    @Test public void untrustedRootAndDialControlsNeverImported() throws Exception {
        JSONObject node = relay().put("detour", "direct").put("bind_interface", "lo")
                .put("tls_fragment", true).put("domain_resolver", "evil");
        node.getJSONObject("tls").put("certificate_path", "/private/file");
        JSONObject root = new JSONObject().put("outbounds", new JSONArray().put(node))
                .put("experimental", new JSONObject().put("clash_api", new JSONObject()));
        JSONArray clean = PublicProxyProvider.sanitize(root);
        assertFalse(clean.getJSONObject(0).has("detour"));
        assertFalse(clean.getJSONObject(0).has("bind_interface"));
        assertFalse(clean.getJSONObject(0).getJSONObject("tls").has("certificate_path"));
        JSONObject generated = new JSONObject(PublicProxyProvider.buildProfile(clean));
        assertFalse(generated.has("experimental"));
        assertEquals("rahyab-auto", generated.getJSONObject("route").getString("final"));
        assertEquals("hijack-dns", generated.getJSONObject("route").getJSONArray("rules")
                .getJSONObject(0).getString("action"));
        JSONArray out = generated.getJSONArray("outbounds");
        for (int i=0; i<out.length(); i++) assertNotEquals("direct", out.getJSONObject(i).getString("type"));
        assertEquals("rahyab-auto", generated.getJSONObject("dns").getJSONArray("servers")
                .getJSONObject(0).getString("detour"));
    }
    @Test public void rejectsInsecureAndPrivateAndUnsupportedNodes() throws Exception {
        JSONObject insecure = relay(); insecure.getJSONObject("tls").put("insecure", true);
        JSONArray out = new JSONArray().put(insecure).put(relay().put("server", "127.0.0.1"))
                .put(relay().put("server", "10.0.0.1"))
                .put(relay().put("type", "direct")).put(relay());
        assertEquals(1, PublicProxyProvider.sanitize(new JSONObject().put("outbounds", out)).length());
    }
    @Test public void capsAndDeduplicatesPool() throws Exception {
        JSONArray out = new JSONArray();
        for (int i=0; i<80; i++) out.put(relay().put("server", "203.0.113."+(i+1)));
        assertEquals(24, PublicProxyProvider.sanitize(new JSONObject().put("outbounds", out)).length());
        assertEquals(1, PublicProxyProvider.sanitize(new JSONObject().put("outbounds",
                new JSONArray().put(relay()).put(relay()))).length());
    }
    @Test public void enforcesFreshness() throws Exception {
        long now = Instant.parse("2026-11-09T02:00:00Z").toEpochMilli();
        PublicProxyProvider.validateFreshness("2026-11-09T01:59:00Z", now);
        try { PublicProxyProvider.validateFreshness("2026-11-08T01:00:00Z", now); fail(); }
        catch (IOException expected) { }
        try { PublicProxyProvider.validateFreshness("2026-11-10T01:00:00Z", now); fail(); }
        catch (IOException expected) { }
    }
    @Test public void cancelledSearchNeverOpensSource() throws Exception {
        try { PublicProxyProvider.fetch(url -> { fail("Cancelled fetch opened a connection"); return null; }, () -> true); fail(); }
        catch (InterruptedException expected) { }
    }
}
