package com.rahyab.next;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class NetworkConnectionPolicyTest {
    @Test public void automaticStartsWithAetherThenTriesOtherCores() {
        assertEquals(List.of("Aether", "Fluxcore", "SSTP", "OpenVPN"),
                NetworkConnectionPolicy.quickOrder());
        assertTrue(NetworkConnectionPolicy.quickAttemptBudgetMs("Aether") <= 65_000L);
        assertTrue(NetworkConnectionPolicy.quickAttemptBudgetMs("Fluxcore") <= 55_000L);
    }

    @Test public void lastSstpHostFirstWithoutDuplicatesOrUntrustedHosts() {
        assertEquals(List.of("public-vpn-66.opengw.net:443", "public-vpn-58.opengw.net:443"),
                NetworkConnectionPolicy.prioritizeSstp(
                        List.of("public-vpn-58.opengw.net:443", "public-vpn-66.opengw.net:443",
                                "malicious.example:443", "public-vpn-58.opengw.net:443"),
                        "public-vpn-66.opengw.net:443"));
        assertFalse(NetworkConnectionPolicy.isValidSstpHost("public-vpn-3.opengw.net:99999"));
    }
    @Test public void sstpMemorySeparatesMciIrancellAndWifi() {
        assertEquals("wifi", NetworkConnectionPolicy.sstpMemoryKey(1, ""));
        assertEquals("cell_mci", NetworkConnectionPolicy.sstpMemoryKey(2, "IR-MCI"));
        assertEquals("cell_mci", NetworkConnectionPolicy.sstpMemoryKey(2, "همراه اول"));
        assertEquals("cell_irancell", NetworkConnectionPolicy.sstpMemoryKey(2, "MTN Irancell"));
        assertEquals("cell_irancell", NetworkConnectionPolicy.sstpMemoryKey(2, "ایرانسل"));
        assertNotEquals(NetworkConnectionPolicy.sstpMemoryKey(2, "IR-MCI"),
                NetworkConnectionPolicy.sstpMemoryKey(2, "MTN Irancell"));
    }

    @Test public void missingCarrierCannotReuseAnotherSimsLastRelay() {
        assertEquals("", NetworkConnectionPolicy.sstpMemoryKey(2, ""));
        assertEquals("", NetworkConnectionPolicy.sstpMemoryKey(-1, "IR-MCI"));
        assertEquals("", NetworkConnectionPolicy.sstpMemoryKey(0, "IR-MCI"));
        assertEquals("cell_other", NetworkConnectionPolicy.sstpMemoryKey(2, "Rightel"));
    }

    @Test public void acceptsExactSstpAddressCopiedFromVpnGate() {
        assertEquals("vpn979681105.opengw.net:1621",
                NetworkConnectionPolicy.parseVpnGateSstp("vpn979681105.opengw.net:1621"));
        assertEquals("vpn979681105.opengw.net:443",
                NetworkConnectionPolicy.parseVpnGateSstp("vpn979681105.opengw.net"));
        assertEquals("public-vpn-255.opengw.net:443",
                NetworkConnectionPolicy.parseVpnGateSstp(
                        "SSTP Hostname : public-vpn-255.opengw.net:443 By admin"));
    }

    @Test public void refusesInvalidOrNonVpnGateSstpHosts() {
        assertNull(NetworkConnectionPolicy.parseVpnGateSstp("1.2.3.4:443"));
        assertNull(NetworkConnectionPolicy.parseVpnGateSstp("https://vpn979681105.opengw.net"));
        assertNull(NetworkConnectionPolicy.parseVpnGateSstp("evil.example.com:443"));
        assertNull(NetworkConnectionPolicy.parseVpnGateSstp("vpn979681105.opengw.net:65536"));
        assertNull(NetworkConnectionPolicy.parseVpnGateSstp("vpn979681105.opengw.net:0"));
    }

}
