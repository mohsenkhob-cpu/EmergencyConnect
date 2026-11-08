package ir.emergencyconnect.engine;

/** Time budgets for one attempt; neither transport response nor TLS alone means success. */
public final class OpenVpnAttemptPolicy {
    private OpenVpnAttemptPolicy() { }

    public static long timeoutMillis(boolean receivedPeerPacket) {
        return receivedPeerPacket ? 30_000L : 10_000L;
    }

    public static boolean isPeerResponseEvent(String name, String lowerInfo) {
        // Verified against the pinned core 0abc8313, cliproto.hpp::transport_recv.
        // This is the first incoming transport packet, before TLS validation.
        return "CONNECTING".equals(name) || "GET_CONFIG".equals(name)
                || ("CORE_LOG".equals(name) && lowerInfo != null
                    && lowerInfo.startsWith("tls handshake: peer certificate:"));
    }
}
