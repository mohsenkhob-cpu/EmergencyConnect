package ir.emergencyconnect.engine;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Fail-closed policy for the public-relay OpenVPN path.
 *
 * Every enabled address family must be completely covered by VPN routes.
 * This accepts both a single default route and equivalent split defaults
 * such as OpenVPN redirect-gateway def1 (0/1 plus 128/1).
 * DNS must be supplied by the profile and excluded routes are rejected.
 */
final class FullTunnelPolicy {
    private static final BigInteger ONE = BigInteger.ONE;

    private FullTunnelPolicy() {}

    static void validate(String[] addresses, String[] dnsServers,
                         String[] routeAddresses, int[] routePrefixes,
                         String[] excludedRouteAddresses) {
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("TUN address is required");
        }
        if (dnsServers == null || dnsServers.length == 0 || dnsServers.length > 8) {
            throw new IllegalArgumentException(
                    "Profile must supply between 1 and 8 VPN DNS servers; refusing possible DNS fallback");
        }
        validateDnsServers(dnsServers);
        if (routeAddresses == null || routePrefixes == null
                || routeAddresses.length == 0
                || routeAddresses.length != routePrefixes.length) {
            throw new IllegalArgumentException("VPN routes are missing or mismatched");
        }
        if (excludedRouteAddresses != null && excludedRouteAddresses.length > 0) {
            throw new IllegalArgumentException(
                    "Excluded routes are not allowed in full-tunnel mode");
        }

        boolean ipv4Enabled = false;
        boolean ipv6Enabled = false;
        for (String address : addresses) {
            if (isIpv6(address)) ipv6Enabled = true;
            else ipv4Enabled = true;
        }
        for (String dns : dnsServers) {
            if (isIpv6(dns)) ipv6Enabled = true;
            else ipv4Enabled = true;
        }
        for (String route : routeAddresses) {
            if (isIpv6(route)) ipv6Enabled = true;
            else ipv4Enabled = true;
        }

        if (ipv4Enabled && !coversEntireFamily(routeAddresses, routePrefixes, false)) {
            throw new IllegalArgumentException(
                    "IPv4 is enabled but VPN routes do not cover all IPv4 destinations");
        }
        if (ipv6Enabled && !coversEntireFamily(routeAddresses, routePrefixes, true)) {
            throw new IllegalArgumentException(
                    "IPv6 is enabled but VPN routes do not cover all IPv6 destinations");
        }
    }

    /**
     * DNS endpoints must be numeric unicast IP literals. Hostnames would cause
     * resolution before the VPN DNS path is established; duplicates add no
     * resilience and can hide malformed profile data.
     */
    private static void validateDnsServers(String[] dnsServers) {
        java.util.Set<String> unique = new java.util.HashSet<>();
        for (String dns : dnsServers) {
            if (dns == null || dns.isEmpty() || dns.length() > 64
                    || !dns.matches("[0-9a-fA-F:.]+")) {
                throw new IllegalArgumentException("VPN DNS server must be a numeric IP address");
            }
            try {
                InetAddress address = InetAddress.getByName(dns);
                byte[] raw = address.getAddress();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException(
                            "VPN DNS server must be a unicast, non-loopback address");
                }
                String normalized = java.util.Arrays.toString(raw);
                if (!unique.add(normalized)) {
                    throw new IllegalArgumentException("Duplicate VPN DNS server");
                }
            } catch (IllegalArgumentException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalArgumentException("Invalid VPN DNS server address", error);
            }
        }
    }

    private static boolean coversEntireFamily(
            String[] routeAddresses, int[] routePrefixes, boolean ipv6) {
        final int bits = ipv6 ? 128 : 32;
        final BigInteger max = ONE.shiftLeft(bits).subtract(ONE);
        List<Range> ranges = new ArrayList<>();

        for (int i = 0; i < routeAddresses.length; i++) {
            String address = routeAddresses[i];
            if (isIpv6(address) != ipv6) continue;
            int prefix = routePrefixes[i];
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Invalid VPN route prefix length");
            }
            BigInteger value = unsignedAddress(address, ipv6);
            int hostBits = bits - prefix;
            BigInteger start = value.shiftRight(hostBits).shiftLeft(hostBits);
            BigInteger end = start.add(ONE.shiftLeft(hostBits).subtract(ONE));
            ranges.add(new Range(start, end));
        }

        if (ranges.isEmpty()) return false;
        Collections.sort(ranges, Comparator.comparing(range -> range.start));
        BigInteger coveredThrough = BigInteger.valueOf(-1);
        for (Range range : ranges) {
            if (range.start.compareTo(coveredThrough.add(ONE)) > 0) return false;
            if (range.end.compareTo(coveredThrough) > 0) {
                coveredThrough = range.end;
            }
            if (coveredThrough.compareTo(max) >= 0) return true;
        }
        return false;
    }

    private static BigInteger unsignedAddress(String value, boolean ipv6) {
        try {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("Empty VPN route address");
            }
            if (ipv6) {
                if (!value.matches("[0-9a-fA-F:.]+")) {
                    throw new IllegalArgumentException("Invalid IPv6 VPN route address");
                }
            } else {
                String[] octets = value.split("\\.", -1);
                if (octets.length != 4) {
                    throw new IllegalArgumentException("Invalid IPv4 VPN route address");
                }
                for (String octet : octets) {
                    if (octet.isEmpty() || octet.length() > 3
                            || !octet.matches("[0-9]+")
                            || Integer.parseInt(octet) > 255) {
                        throw new IllegalArgumentException("Invalid IPv4 VPN route address");
                    }
                }
            }
            byte[] bytes = InetAddress.getByName(value).getAddress();
            if (bytes.length != (ipv6 ? 16 : 4)) {
                throw new IllegalArgumentException("VPN route address family mismatch");
            }
            return new BigInteger(1, bytes);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid VPN route address", error);
        }
    }

    private static boolean isIpv6(String value) {
        if (value == null || value.indexOf(':') < 0) return false;
        if (!value.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalArgumentException("Invalid IPv6 address in VPN route policy");
        }
        try {
            return InetAddress.getByName(value).getAddress().length == 16;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid IPv6 address in VPN route policy");
        }
    }

    private static final class Range {
        final BigInteger start;
        final BigInteger end;
        Range(BigInteger start, BigInteger end) {
            this.start = start;
            this.end = end;
        }
    }
}
