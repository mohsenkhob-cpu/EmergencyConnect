package ir.emergencyconnect.engine;

/**
 * Fail-closed policy for the public-relay OpenVPN path.
 *
 * Android blocks an address family only while the VPN has not implicitly
 * enabled it through an address, route, or DNS server. Therefore every
 * enabled family must have an explicit default route. DNS must be supplied
 * by the profile so Android does not fall back to the underlying network.
 * Excluded routes are rejected in this full-tunnel mode.
 */
final class FullTunnelPolicy {
    private FullTunnelPolicy() {}

    static void validate(String[] addresses, String[] dnsServers,
                         String[] routeAddresses, int[] routePrefixes,
                         String[] excludedRouteAddresses) {
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("TUN address is required");
        }
        if (dnsServers == null || dnsServers.length == 0) {
            throw new IllegalArgumentException(
                    "Profile has no VPN DNS server; refusing possible DNS fallback outside the tunnel");
        }
        if (routeAddresses == null || routePrefixes == null
                || routeAddresses.length != routePrefixes.length) {
            throw new IllegalArgumentException("VPN routes are missing or mismatched");
        }
        if (excludedRouteAddresses != null && excludedRouteAddresses.length > 0) {
            throw new IllegalArgumentException(
                    "Excluded routes are not allowed in full-tunnel mode");
        }

        boolean ipv4Enabled = false;
        boolean ipv6Enabled = false;
        boolean ipv4Default = false;
        boolean ipv6Default = false;

        for (String address : addresses) {
            if (isIpv6(address)) ipv6Enabled = true;
            else ipv4Enabled = true;
        }
        for (String dns : dnsServers) {
            if (isIpv6(dns)) ipv6Enabled = true;
            else ipv4Enabled = true;
        }
        for (int i = 0; i < routeAddresses.length; i++) {
            boolean v6 = isIpv6(routeAddresses[i]);
            if (v6) ipv6Enabled = true;
            else ipv4Enabled = true;
            if (routePrefixes[i] == 0 && isZeroAddress(routeAddresses[i], v6)) {
                if (v6) ipv6Default = true;
                else ipv4Default = true;
            }
        }

        if (ipv4Enabled && !ipv4Default) {
            throw new IllegalArgumentException(
                    "IPv4 is enabled but the VPN has no 0.0.0.0/0 default route");
        }
        if (ipv6Enabled && !ipv6Default) {
            throw new IllegalArgumentException(
                    "IPv6 is enabled but the VPN has no ::/0 default route");
        }
    }

    private static boolean isIpv6(String value) {
        if (value == null || value.indexOf(':') < 0) return false;
        try {
            return java.net.InetAddress.getByName(value).getAddress().length == 16;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid IPv6 address in VPN route policy");
        }
    }

    private static boolean isZeroAddress(String value, boolean ipv6) {
        if (value == null) return false;
        if (!ipv6) return "0.0.0.0".equals(value);
        try {
            byte[] bytes = java.net.InetAddress.getByName(value).getAddress();
            if (bytes.length != 16) return false;
            for (byte b : bytes) if (b != 0) return false;
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }
}
