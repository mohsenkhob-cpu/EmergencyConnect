package com.rahyab.next.engine;

public enum TunnelState {
    IDLE,
    VALIDATING_PROFILE,
    WAITING_FOR_PERMISSION,
    CONNECTING,
    TUNNEL_UP,
    VERIFYING_TRAFFIC,
    CONNECTED,
    RECONNECTING,
    DISCONNECTING,
    DISCONNECTED,
    FAILED
}
