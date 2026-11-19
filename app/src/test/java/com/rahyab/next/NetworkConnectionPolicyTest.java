package com.rahyab.next;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class NetworkConnectionPolicyTest {
    @Test public void onMciTryVerifiedSstpBeforeOpenvpn() {
        assertTrue(NetworkConnectionPolicy.isMciOperator("IR-MCI"));
        assertTrue(NetworkConnectionPolicy.isMciOperator("همراه اول"));
        assertFalse(NetworkConnectionPolicy.isMciOperator("Irancell"));
        assertEquals(List.of("SSTP", "OpenVPN", "sing-box"),
                NetworkConnectionPolicy.quickOrder(true, false));
    }

    @Test public void otherNetworksPreserveVerifiedOpenvpnFirst() {
        assertEquals(List.of("OpenVPN", "SSTP", "sing-box", "WireGuard"),
                NetworkConnectionPolicy.quickOrder(false, true));
    }

    @Test public void lastSstpHostFirstWithoutDuplicatesOrUntrustedHosts() {
        assertEquals(List.of("public-vpn-66.opengw.net:443", "public-vpn-58.opengw.net:443"),
                NetworkConnectionPolicy.prioritizeSstp(
                        List.of("public-vpn-58.opengw.net:443", "public-vpn-66.opengw.net:443",
                                "malicious.example:443", "public-vpn-58.opengw.net:443"),
                        "public-vpn-66.opengw.net:443"));
        assertFalse(NetworkConnectionPolicy.isValidSstpHost("public-vpn-3.opengw.net:99999"));
    }
}
