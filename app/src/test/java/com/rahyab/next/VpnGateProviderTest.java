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
        String csv = "#HostName,IP,Score,Ping,Speed,Country,...\\n"
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
}
