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
        return tunInterface == null ? -1 : tunInterface.detachFd();
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
