package com.rahyab.next;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;

/**
 * Connection order is chosen from real confirmed on-device outcomes,
 * not from advertisement speed or the existence of a TUN alone.
 * No carrier-specific behavior is asserted for networks not tested.
 */
final class NetworkConnectionPolicy {
    private NetworkConnectionPolicy() { }

    static boolean isMciOperator(String operator) {
        if (operator == null) return false;
        String name = operator.trim().toLowerCase(Locale.ROOT);
        return name.contains("ir-mci") || name.equals("mci")
                || name.contains("hamrah aval") || name.contains("همراه اول");
    }

    /**
     * Remember verified SSTP relays independently for Wi-Fi, MCI and Irancell.
     * Unknown cellular operators never inherit a different SIM's old relay.
     * The key is intentionally non-identifying: no phone number, IMSI or SSID.
     */
    static String sstpMemoryKey(int physicalTransport, String operator) {
        if (physicalTransport == 1) return "wifi";
        if (physicalTransport != 2) return "";
        if (operator == null || operator.trim().isEmpty()) return "";
        if (isMciOperator(operator)) return "cell_mci";
        String name = operator.trim().toLowerCase(Locale.ROOT);
        if (name.contains("irancell") || name.contains("ایرانسل")
                || name.contains("mtn")) return "cell_irancell";
        return "cell_other";
    }

    static List<String> quickOrder() {
        return List.of("Aether", "Fluxcore", "SSTP", "OpenVPN");
    }

    static String nativeSpeedAlternative(String engine) {
        return "Aether".equals(engine) ? "Fluxcore" : "Aether";
    }

    static long quickAttemptBudgetMs(String engine) {
        return switch (engine) {
            case "Aether" -> 65_000L;
            case "Fluxcore" -> 55_000L;
            case "SSTP" -> 65_000L;
            default -> 175_000L;
        };
    }

    static ArrayList<String> prioritizeSstp(List<String> endpoints, String lastWorkingHost) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (isValidSstpHost(lastWorkingHost)) ordered.add(lastWorkingHost.toLowerCase(Locale.ROOT));
        for (String endpoint : endpoints) {
            if (isValidSstpHost(endpoint)) ordered.add(endpoint.toLowerCase(Locale.ROOT));
            if (ordered.size() >= 4) break;
        }
        ArrayList<String> result = new ArrayList<>(ordered);
        if (result.size() > 4) result.subList(4, result.size()).clear();
        return result;
    }

    /**
     * Read the same DDNS hostname/port users copy from VPN Gate into
     * Open SSTP Client. Reject IPs, URLs and arbitrary hosts so we never
     * silently send VPN credentials to a non-VPN-Gate endpoint.
     */
    static String parseVpnGateSstp(String input) {
        if (input == null) return null;
        String value = input.trim().toLowerCase(Locale.ROOT);
        int marker = value.indexOf("sstp hostname");
        if (marker >= 0) {
            int separator = value.indexOf(':', marker);
            if (separator < 0) return null;
            value = value.substring(separator + 1).trim().split("\\s+")[0];
        }
        if (!value.matches("[a-z0-9-]+\\.opengw\\.net(?::[0-9]{1,5})?"))
            return null;
        if (!value.contains(":")) value += ":443";
        return isValidSstpHost(value) ? value : null;
    }

    static boolean isValidSstpHost(String endpoint) {
        return endpoint != null &&
                endpoint.matches("(?i)[a-z0-9-]+\\.opengw\\.net:[1-9][0-9]{0,4}")
                && Integer.parseInt(endpoint.substring(endpoint.lastIndexOf(':') + 1)) <= 65535;
    }
}
