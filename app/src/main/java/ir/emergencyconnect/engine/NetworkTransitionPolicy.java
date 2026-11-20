package ir.emergencyconnect.engine;

/**
 * Conservative guards for reacting to physical network changes.
 * Never start a VPN as a consequence of an unrelated network callback.
 */
public final class NetworkTransitionPolicy {
    private NetworkTransitionPolicy() { }

    public static boolean shouldRecheckLostTransport(
            long originalNetworkHandle, long lostNetworkHandle, boolean userInitiatedSessionActive) {
        return userInitiatedSessionActive && originalNetworkHandle > 0L
                && lostNetworkHandle == originalNetworkHandle;
    }

    /** Two independent VPN HTTPS checks must fail before recovery is attempted. */
    public static boolean shouldRecoverAfterNetworkLoss(
            boolean firstVpnProbeSucceeded, boolean secondVpnProbeSucceeded,
            boolean userInitiatedSessionActive) {
        return userInitiatedSessionActive
                && !firstVpnProbeSucceeded && !secondVpnProbeSucceeded;
    }
}
