package com.rahyab.next;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class SingBoxGeneratedProfileTest {
    @Test public void generatedDnsBootstrapUsesDirectPath() throws Exception {
        JSONObject node = new JSONObject().put("type","vless").put("tag","relay-1")
                .put("server","203.0.113.10").put("server_port",443);
        JSONObject root = new JSONObject(PublicProxyProvider.buildProfile(new JSONArray().put(node)));
        JSONArray outbounds = root.getJSONArray("outbounds");
        assertEquals("direct",outbounds.getJSONObject(outbounds.length()-1).getString("type"));
        assertEquals("direct",root.getJSONObject("dns").getJSONArray("servers")
                .getJSONObject(1).getString("detour"));
        ArrayList<String> batches = PublicProxyProvider.buildBatches(root.toString());
        assertEquals(2,batches.size());
        for (String item : batches) {
            JSONObject batch = new JSONObject(item);
            JSONArray children = batch.getJSONArray("outbounds");
            assertEquals("direct",children.getJSONObject(children.length()-1).getString("type"));
            assertNotEquals("direct",batch.getJSONObject("route").getString("final"));
        }
    }
}