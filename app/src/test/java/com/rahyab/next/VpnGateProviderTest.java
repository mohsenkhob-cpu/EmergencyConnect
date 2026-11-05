package com.rahyab.next;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VpnGateProviderTest {
    private static String row(String host, String ip, String country, String speed,
                              String ping, String profile) {
        List<String> fields = new ArrayList<>();
        fields.add(host);                 // 0 HostName
        fields.add(ip);                   // 1 IP
        fields.add("0");                  // 2 Score
        fields.add(ping);                 // 3 Ping
        fields.add(speed);                // 4 Speed
        fields.add(country);              // 5 Country
        fields.add(country);              // 6 CountryLong
        fields.add("1");                  // 7 NumVpnSessions
        fields.add("1000");               // 8 Uptime
        fields.add("10");                 // 9 TotalUsers
        fields.add("100");                // 10 TotalTraffic
        fields.add("0");                  // 11 LogType
        fields.add("test");               // 12 Operator
        fields.add("");                   // 13 Message
        fields.add(Base64.getEncoder().encodeToString(
                profile.getBytes(StandardCharsets.UTF_8))); // 14 profile
        return String.join(",", fields);
    }

    @Test public void parsesValidOpenVpnRowsAndExtractsPort() throws Exception {
        String profile = "client\nremote 203.0.113.10 1194\n";
        String csv = "#HostName,IP,Score,Ping,Speed,Country,...\n"
                + row("relay-a", "203.0.113.10", "US", "5000000", "42", profile);
        List<VpnGateProvider.Server> servers = VpnGateProvider.parse(csv);

        assertEquals(1, servers.size());
        assertEquals("203.0.113.10", servers.get(0).ip);
        assertEquals(1194, servers.get(0).port);
        assertEquals("US", servers.get(0).country);
        assertEquals("VPN Gate رسمی", servers.get(0).source);
    }

    @Test public void rejectsProfileWhoseRemoteDoesNotMatchAdvertisedIp() throws Exception {
        String profile = "client\nremote attacker.example 1194\n";
        String csv = "#HostName,IP,Score,Ping,Speed,Country,...\n"
                + row("relay-a", "203.0.113.10", "US", "1000", "30", profile);

        assertTrue(VpnGateProvider.parse(csv).isEmpty());
    }

    @Test public void rejectsProfilesWithMultipleRemoteDirectives() throws Exception {
        String profile = "client\nremote 203.0.113.10 1194\n"
                + "remote 198.51.100.20 443\n";
        String csv = "#HostName,IP,Score,Ping,Speed,Country,...\n"
                + row("relay-a", "203.0.113.10", "US", "1000", "30", profile);

        assertTrue(VpnGateProvider.parse(csv).isEmpty());
    }

    @Test public void removesDuplicateEndpoints() throws Exception {
        String profile = "client\nremote 203.0.113.10 1194\n";
        String first = row("relay-a", "203.0.113.10", "US", "1000", "30", profile);
        String duplicate = row("relay-b", "203.0.113.10", "US", "2000", "20", profile);
        String csv = "#HostName,IP,Score,Ping,Speed,Country,...\n" + first + "\n" + duplicate;

        assertEquals(1, VpnGateProvider.parse(csv).size());
    }

    @Test public void ignoresRowsWithInvalidIpOrMissingRemoteDirective() throws Exception {
        String validIpButBadProfile = "client\nproto udp\n";
        String csv = "#HostName,IP,Score,Ping,Speed,Country,...\n"
                + row("bad-ip", "999.1.1.1", "US", "1000", "30",
                        "client\nremote 999.1.1.1 1194\n") + "\n"
                + row("bad-profile", "203.0.113.11", "US", "1000", "30",
                        validIpButBadProfile);

        assertTrue(VpnGateProvider.parse(csv).isEmpty());
    }

    @Test public void csvParserKeepsCommasInsideQuotedFields() {
        List<String> fields = VpnGateProvider.parseCsvLine("relay,203.0.113.1,0,12,1000,\"United, States\",x");
        assertEquals(7, fields.size());
        assertEquals("United, States", fields.get(5));
    }

    @Test public void parsesPinnedMirrorManifestForOfficialVpnGateSource() throws Exception {
        String json = "{"
                + "\"schema_version\":1,"
                + "\"source_url\":\"https://www.vpngate.net/api/iphone/\","
                + "\"data_commit\":\"523c8da909cb7fe8d42ab511392ebb32b625e04a\","
                + "\"files\":{\"data/vpngate.csv\":{\"bytes\":1333898,"
                + "\"server_count\":99,"
                + "\"sha256\":\"bd7202d6cfe5809ef50777b07cc7d1d56d804304700adfc6947aa86267ca19b9\"}}}";
        VpnGateProvider.MirrorManifest manifest =
                VpnGateProvider.parseMirrorManifest(json);

        assertEquals("523c8da909cb7fe8d42ab511392ebb32b625e04a", manifest.commit);
        assertEquals(1333898, manifest.bytes);
        assertEquals(99, manifest.serverCount);
        assertEquals("bd7202d6cfe5809ef50777b07cc7d1d56d804304700adfc6947aa86267ca19b9",
                manifest.sha256);
    }

    @Test public void rejectsMirrorManifestWithUnexpectedSourceOrSchema() throws Exception {
        String json = "{\"schema_version\":2,"
                + "\"source_url\":\"https://example.com/\","
                + "\"data_commit\":\"523c8da909cb7fe8d42ab511392ebb32b625e04a\","
                + "\"files\":{\"data/vpngate.csv\":{\"bytes\":10,\"server_count\":1,"
                + "\"sha256\":\"bd7202d6cfe5809ef50777b07cc7d1d56d804304700adfc6947aa86267ca19b9\"}}}";
        try {
            VpnGateProvider.parseMirrorManifest(json);
            throw new AssertionError("invalid mirror manifest must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("شاخص"));
        }
    }

    @Test public void verifiesMirrorCsvExactByteCountAndSha256() throws Exception {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        VpnGateProvider.MirrorManifest valid = new VpnGateProvider.MirrorManifest(
                "523c8da909cb7fe8d42ab511392ebb32b625e04a",
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                5, 1);
        VpnGateProvider.verifyMirrorBytes(body, valid);

        VpnGateProvider.MirrorManifest wrongHash = new VpnGateProvider.MirrorManifest(
                valid.commit, "0000000000000000000000000000000000000000000000000000000000000000",
                5, 1);
        try {
            VpnGateProvider.verifyMirrorBytes(body, wrongHash);
            throw new AssertionError("wrong mirror hash must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("SHA-256"));
        }
    }

    @Test public void parserLabelsServersWithTheActualSource() throws Exception {
        String profile = "client\nremote 203.0.113.10 1194\n";
        String csv = "#HostName,IP,Score,Ping,Speed,Country,...\n"
                + row("relay-a", "203.0.113.10", "US", "5000000", "42", profile);
        List<VpnGateProvider.Server> servers =
                VpnGateProvider.parse(csv, "آینه HTTPS ثالث");
        assertEquals("آینه HTTPS ثالث", servers.get(0).source);
    }

}
