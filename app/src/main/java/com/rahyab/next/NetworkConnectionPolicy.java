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

    static List<String> quickOrder(boolean onMci, boolean validWireGuardProfiles) {
        ArrayList<String> order = new ArrayList<>();
        if (onMci) {
            order.add("SSTP");
            order.add("OpenVPN");
        } else {
            order.add("OpenVPN");
            order.add("SSTP");
        }
        if (validWireGuardProfiles) order.add("WireGuard");
        return order;
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

    static boolean isValidSstpHost(String endpoint) {
        return endpoint != null &&
                endpoint.matches("(?i)[a-z0-9-]+\\.opengw\\.net:[1-9][0-9]{0,4}")
                && Integer.parseInt(endpoint.substring(endpoint.lastIndexOf(':') + 1)) <= 65535;
    }
}
