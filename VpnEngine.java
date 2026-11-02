package ir.emergencyconnect.engine;

/**
 * Contract for the embedded VPN engine.
 * The OpenVPN 3 JNI implementation is intentionally not stubbed as a fake
 * successful connection. A connection is available only after the native
 * OpenVPN 3 core and Android TunBuilder bridge are integrated.
 */
public interface VpnEngine {
    interface Listener {
        void onStateChanged(State state, String detail);
        void onError(String message);
    }

    enum State { IDLE, CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED, ERROR }

    boolean isAvailable();
    void connect(String inlineProfile, Listener listener);
    void disconnect();
}
