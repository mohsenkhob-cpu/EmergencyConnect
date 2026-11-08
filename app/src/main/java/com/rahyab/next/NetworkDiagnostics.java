package com.rahyab.next;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Local diagnostics only: no SSID, subscriber identifier, credentials or extra probes. */
public final class NetworkDiagnostics {
    private NetworkDiagnostics() { }
    public static String describe(Context context, Network network) {
        try { return describeUnsafe(context, network); }
        catch (RuntimeException error) { return "network_snapshot_unavailable:" + error.getClass().getSimpleName(); }
    }
    private static String describeUnsafe(Context context, Network network) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null || network == null) return "network=none";
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        if (caps == null) return "network=" + network.getNetworkHandle() + " unavailable";
        String transport = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WIFI"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "CELLULAR"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ETHERNET" : "OTHER";
        StringBuilder result = new StringBuilder("network=").append(network.getNetworkHandle())
                .append(" transport=").append(transport)
                .append(" validated=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        LinkProperties links = manager.getLinkProperties(network);
        if (links != null) {
            result.append(" dns=");
            for (java.net.InetAddress dns : links.getDnsServers()) result.append(dns.getHostAddress()).append(' ');
            if (android.os.Build.VERSION.SDK_INT >= 28) result.append(" privateDns=").append(links.isPrivateDnsActive());
        }
        return result.toString();
    }

    public static String snapshot(Context context) {
        try { return snapshotUnsafe(context); }
        catch (RuntimeException error) { return "network_snapshot_unavailable:" + error.getClass().getSimpleName(); }
    }
    private static String snapshotUnsafe(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return "connectivity_manager=unavailable";
        StringBuilder text = new StringBuilder("active: ").append(describe(context, manager.getActiveNetwork()));
        for (Network network : manager.getAllNetworks()) {
            text.append("\n").append(describe(context, network));
        }
        return text.toString();
    }
}
