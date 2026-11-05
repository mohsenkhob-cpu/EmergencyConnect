package ir.emergencyconnect.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class FullTunnelPolicyTest {
    @Test public void acceptsIpv4OnlyFullTunnelWithDns() {
        FullTunnelPolicy.validate(
                new String[]{"10.8.0.2"},
                new String[]{"10.8.0.1"},
                new String[]{"0.0.0.0"},
                new int[]{0},
                new String[0]);
    }

    @Test public void acceptsIpv4RedirectGatewayDef1SplitDefaultRoutes() {
        FullTunnelPolicy.validate(
                new String[]{"10.8.0.2"},
                new String[]{"10.8.0.1"},
                new String[]{"0.0.0.0", "128.0.0.0"},
                new int[]{1, 1},
                new String[0]);
    }

    @Test public void rejectsIncompleteSplitDefaultRoutes() {
        try {
            FullTunnelPolicy.validate(
                    new String[]{"10.8.0.2"},
                    new String[]{"10.8.0.1"},
                    new String[]{"0.0.0.0"},
                    new int[]{1},
                    new String[0]);
            fail("Expected incomplete split default routes to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("IPv4"));
        }
    }

    @Test public void acceptsIpv6SplitDefaultRoutes() {
        FullTunnelPolicy.validate(
                new String[]{"fd00::2"},
                new String[]{"fd00::1"},
                new String[]{"::", "8000::"},
                new int[]{1, 1},
                new String[0]);
    }

    @Test public void rejectsIpv4WithoutDefaultRoute() {
        try {
            FullTunnelPolicy.validate(
                    new String[]{"10.8.0.2"},
                    new String[]{"10.8.0.1"},
                    new String[]{"10.0.0.0"},
                    new int[]{8},
                    new String[0]);
            fail("Expected missing IPv4 default route to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("IPv4"));
        }
    }

    @Test public void rejectsEnabledIpv6WithoutDefaultRoute() {
        try {
            FullTunnelPolicy.validate(
                    new String[]{"10.8.0.2", "fd00::2"},
                    new String[]{"10.8.0.1"},
                    new String[]{"0.0.0.0"},
                    new int[]{0},
                    new String[0]);
            fail("Expected missing IPv6 default route to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("IPv6"));
        }
    }

    @Test public void acceptsDualStackOnlyWhenBothFamiliesHaveDefaultRoutes() {
        FullTunnelPolicy.validate(
                new String[]{"10.8.0.2", "fd00::2"},
                new String[]{"10.8.0.1", "fd00::1"},
                new String[]{"0.0.0.0", "::"},
                new int[]{0, 0},
                new String[0]);
    }

    @Test public void rejectsMissingDnsToPreventUnderlyingDnsFallback() {
        try {
            FullTunnelPolicy.validate(
                    new String[]{"10.8.0.2"},
                    new String[0],
                    new String[]{"0.0.0.0"},
                    new int[]{0},
                    new String[0]);
            fail("Expected missing VPN DNS to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("DNS"));
        }
    }

    @Test public void rejectsExcludedRoutesInFullTunnelMode() {
        try {
            FullTunnelPolicy.validate(
                    new String[]{"10.8.0.2"},
                    new String[]{"10.8.0.1"},
                    new String[]{"0.0.0.0"},
                    new int[]{0},
                    new String[]{"203.0.113.0"});
            fail("Expected excluded route to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Excluded"));
        }
    }
}
