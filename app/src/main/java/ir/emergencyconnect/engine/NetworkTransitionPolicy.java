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
    /**
     * Ignore recently unreachable OpenVPN endpoints only on the same
     * underlying network. The cooldown is short and reversible: a network
     * switch or 30-minute expiry permits retesting.
     */
    public static boolean isRelayOnNoRouteCooldown(
            String candidate, String failedEndpoint, long nowMs, long failureAtMs,
            long networkHandle, long failureNetworkHandle, int transport, int failureTransport) {
        return candidate != null && !candidate.isEmpty()
                && candidate.equals(failedEndpoint)
                && networkHandle > 0L && networkHandle == failureNetworkHandle
                && transport >= 0 && transport == failureTransport
                && failureAtMs > 0L && nowMs >= failureAtMs
                && nowMs - failureAtMs < 30L * 60L * 1000L;
    }
}
