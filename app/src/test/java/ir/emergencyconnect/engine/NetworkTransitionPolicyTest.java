package ir.emergencyconnect.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NetworkTransitionPolicyTest {
    @Test public void onlyLossOfOriginalPhysicalTransportRechecksAnExplicitSession() {
        assertTrue(NetworkTransitionPolicy.shouldRecheckLostTransport(101L, 101L, true));
        assertFalse(NetworkTransitionPolicy.shouldRecheckLostTransport(101L, 202L, true));
        assertFalse(NetworkTransitionPolicy.shouldRecheckLostTransport(0L, 0L, true));
        assertFalse(NetworkTransitionPolicy.shouldRecheckLostTransport(101L, 101L, false));
    }

    @Test public void oneFailedProbeNeverForcesRecovery() {
        assertFalse(NetworkTransitionPolicy.shouldRecoverAfterNetworkLoss(false, true, true));
        assertFalse(NetworkTransitionPolicy.shouldRecoverAfterNetworkLoss(true, false, true));
        assertFalse(NetworkTransitionPolicy.shouldRecoverAfterNetworkLoss(true, true, true));
        assertFalse(NetworkTransitionPolicy.shouldRecoverAfterNetworkLoss(false, false, false));
        assertTrue(NetworkTransitionPolicy.shouldRecoverAfterNetworkLoss(false, false, true));
    }

    @Test public void unreachableOpenVpnIsSuppressedOnlyBrieflyOnSameNetwork() {
        long now = 1_800_000_000_000L;
        assertTrue(NetworkTransitionPolicy.isRelayOnNoRouteCooldown(
                "133.165.233.197:1830", "133.165.233.197:1830",
                now, now - 60_000L, 420L, 420L, 1, 1));
        assertFalse(NetworkTransitionPolicy.isRelayOnNoRouteCooldown(
                "133.165.233.197:1830", "133.165.233.197:1830",
                now, now - 31L * 60_000L, 420L, 420L, 1, 1));
        assertFalse(NetworkTransitionPolicy.isRelayOnNoRouteCooldown(
                "133.165.233.197:1830", "133.165.233.197:1830",
                now, now - 60_000L, 421L, 420L, 1, 1));
        assertFalse(NetworkTransitionPolicy.isRelayOnNoRouteCooldown(
                "133.165.233.197:1830", "133.165.233.197:1830",
                now, now - 60_000L, 420L, 420L, 2, 1));
        assertFalse(NetworkTransitionPolicy.isRelayOnNoRouteCooldown(
                "1.2.3.4:443", "133.165.233.197:1830",
                now, now - 60_000L, 420L, 420L, 1, 1));
    }
}
