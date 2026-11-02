package ir.emergencyconnect.engine;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

/**
 * Android TUN adapter scaffold for the embedded OpenVPN 3 engine.
 *
 * This class only owns Android's VPN interface. It does not implement the
 * OpenVPN protocol and must not be treated as a connected VPN by itself.
 */
public final class RahYabVpnService extends VpnService {
    private ParcelFileDescriptor tunInterface;

    /**
     * Establish an Android TUN interface from parameters supplied by the
     * future OpenVPN 3 TunBuilder JNI adapter.
     *
     * Ownership contract: the returned raw file descriptor is detached from
     * ParcelFileDescriptor and belongs to the native caller. Native code must
     * close it exactly once when the tunnel is stopped.
     */
    public synchronized int establishTun(
            String sessionName,
            String ipv4Address,
            int prefixLength,
            String dnsServer,
            String routeAddress,
            int routePrefix,
            int mtu) throws Exception {

        closeTun();

        if (ipv4Address == null || ipv4Address.isEmpty()
                || prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("Invalid IPv4 TUN address/prefix");
        }
        if (routeAddress != null && !routeAddress.isEmpty()
                && (routePrefix < 0 || routePrefix > 32)) {
            throw new IllegalArgumentException("Invalid IPv4 route prefix");
        }

        Builder builder = new Builder()
                .setSession(sessionName == null ? "RahYabVPN" : sessionName)
                .addAddress(ipv4Address, prefixLength);

        if (dnsServer != null && !dnsServer.isEmpty()) {
            builder.addDnsServer(dnsServer);
        }
        if (routeAddress != null && !routeAddress.isEmpty()) {
            builder.addRoute(routeAddress, routePrefix);
        }
        if (mtu >= 576) {
            builder.setMtu(mtu);
        }

        tunInterface = builder.establish();
        if (tunInterface == null) {
            return -1;
        }

        ParcelFileDescriptor established = tunInterface;
        int fd = established.detachFd();
        tunInterface = null;
        established.close();
        return fd;
    }

    /** Protect the OpenVPN transport socket from being routed into its own TUN. */
    public boolean protectTransportSocket(int socketFd) {
        return protect(socketFd);
    }

    public synchronized void closeTun() {
        if (tunInterface != null) {
            try {
                tunInterface.close();
            } catch (Exception ignored) {
            }
            tunInterface = null;
        }
    }

    @Override
    public void onDestroy() {
        closeTun();
        super.onDestroy();
    }
}
