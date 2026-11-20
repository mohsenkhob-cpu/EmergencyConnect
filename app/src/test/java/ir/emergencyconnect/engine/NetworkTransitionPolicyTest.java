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
}
