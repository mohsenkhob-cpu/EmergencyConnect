package ir.emergencyconnect.engine;

import android.net.VpnService;
import android.net.IpPrefix;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.net.InetAddress;

/**
 * Android TUN adapter scaffold for the embedded OpenVPN 3 engine.
 *
 * This class owns Android's VPN interface. The OpenVPN protocol/session is
 * managed by the native OpenVPNClient adapter; this class alone is not a VPN.
 */
public final class RahYabVpnService extends VpnService {
    private ParcelFileDescriptor tunInterface;

    /**
     * Compatibility entry point retained for the first single-IPv4 prototype.
     */
    public synchronized int establishTun(
            String sessionName,
            String ipv4Address,
            int prefixLength,
            String dnsServer,
            String routeAddress,
            int routePrefix,
            int mtu) throws Exception {
        String[] addresses = new String[]{ipv4Address};
        int[] prefixes = new int[]{prefixLength};
        String[] dns = dnsServer == null || dnsServer.isEmpty()
                ? new String[0] : new String[]{dnsServer};
        String[] routes = routeAddress == null || routeAddress.isEmpty()
                ? new String[0] : new String[]{routeAddress};
        int[] routePrefixes = routes.length == 0
                ? new int[0] : new int[]{routePrefix};
        return establishTun(sessionName, addresses, prefixes, dns,
                routes, routePrefixes, mtu);
    }

    /**
     * Establish a TUN interface with all addresses, DNS servers and routes
     * collected by the future OpenVPN 3 TunBuilder JNI adapter.
     *
     * The returned detached descriptor is owned by native code and must be
     * closed exactly once by that session during teardown.
     */
    public synchronized int establishTun(
            String sessionName,
            String[] addresses,
            int[] prefixes,
            String[] dnsServers,
            String[] routeAddresses,
            int[] routePrefixes,
            int mtu) throws Exception {
        return establishTun(sessionName, addresses, prefixes, dnsServers,
                routeAddresses, routePrefixes, new String[0], new int[0], mtu);
    }

    /**
     * Full route form used by the embedded engine. Excluded routes are
     * supported only on Android 13/API 33 and newer; older versions fail
     * explicitly rather than silently routing traffic differently.
     */
    public synchronized int establishTun(
            String sessionName,
            String[] addresses,
            int[] prefixes,
            String[] dnsServers,
            String[] routeAddresses,
            int[] routePrefixes,
            String[] excludedRouteAddresses,
            int[] excludedRoutePrefixes,
            int mtu) throws Exception {

        closeTun();

        if (addresses == null || prefixes == null
                || addresses.length == 0 || addresses.length != prefixes.length) {
            throw new IllegalArgumentException("TUN addresses/prefixes are missing or mismatched");
        }
        if (dnsServers == null || routeAddresses == null || routePrefixes == null
                || routeAddresses.length != routePrefixes.length
                || excludedRouteAddresses == null || excludedRoutePrefixes == null
                || excludedRouteAddresses.length != excludedRoutePrefixes.length) {
            throw new IllegalArgumentException("TUN DNS/routes are invalid");
        }
        if (excludedRouteAddresses.length > 0 && Build.VERSION.SDK_INT < 33) {
            throw new UnsupportedOperationException(
                    "Excluded VPN routes require Android 13 or newer");
        }

        Builder builder = new Builder()
                .setSession(sessionName == null || sessionName.isEmpty()
                        ? "RahYabVPN" : sessionName);

        for (int i = 0; i < addresses.length; i++) {
            InetAddress address = parseNumericAddress(addresses[i], "TUN address");
            int maxPrefix = address.getAddress().length * 8;
            if (prefixes[i] < 0 || prefixes[i] > maxPrefix) {
                throw new IllegalArgumentException("Invalid TUN address prefix");
            }
            builder.addAddress(address, prefixes[i]);
        }

        for (String dns : dnsServers) {
            builder.addDnsServer(parseNumericAddress(dns, "DNS server"));
        }

        for (int i = 0; i < routeAddresses.length; i++) {
            InetAddress route = parseNumericAddress(routeAddresses[i], "route");
            int maxPrefix = route.getAddress().length * 8;
            if (routePrefixes[i] < 0 || routePrefixes[i] > maxPrefix) {
                throw new IllegalArgumentException("Invalid route prefix");
            }
            builder.addRoute(route, routePrefixes[i]);
        }

        for (int i = 0; i < excludedRouteAddresses.length; i++) {
            InetAddress route = parseNumericAddress(
                    excludedRouteAddresses[i], "excluded route");
            int maxPrefix = route.getAddress().length * 8;
            if (excludedRoutePrefixes[i] < 0 || excludedRoutePrefixes[i] > maxPrefix) {
                throw new IllegalArgumentException("Invalid excluded route prefix");
            }
            builder.excludeRoute(new IpPrefix(route, excludedRoutePrefixes[i]));
        }

        if (mtu >= 576 && mtu <= 65535) {
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

    /**
     * Reject hostnames here: OpenVPN supplies numeric addresses to the
     * TunBuilder callbacks and DNS resolution must not occur on this path.
     */
    private static InetAddress parseNumericAddress(String value, String field)
            throws Exception {
        if (value == null || value.isEmpty()
                || !(value.indexOf(':') >= 0 || value.matches("[0-9.]+"))) {
            throw new IllegalArgumentException("Invalid numeric " + field);
        }
        InetAddress parsed = InetAddress.getByName(value);
        if (parsed.getAddress().length != 4 && parsed.getAddress().length != 16) {
            throw new IllegalArgumentException("Unsupported " + field + " family");
        }
        return parsed;
    }

    /** Protect transport sockets from being routed into their own TUN. */
    public boolean protectTransportSocket(int socketFd) {
        return protect(socketFd);
    }

    public synchronized void closeTun() {
        if (tunInterface != null) {
            try {
                tunInterface.close();
            } catch (Exception ignored) {
                // Teardown is best-effort; native owns detached descriptors.
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
