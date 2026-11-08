#!/usr/bin/env python3
"""Run the production quality probe methods against controlled network transitions."""
from pathlib import Path
import subprocess
import tempfile
import shutil
import sys

root = Path(__file__).resolve().parents[2]
source = (root / "app/src/main/java/ir/emergencyconnect/engine/RahYabVpnService.java").read_text()
methods = []
for name in ("private int measureTunnelStability()", "private long measureTunnelLatencyMs()", "private long measureTunnelDownloadKbps()"):
    start = source.index(name)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    methods.append(source[start:end])

harness = r"""
import java.net.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
public class QualityNetworkTest {
 Network active;
 boolean cancelled;
 Network activeVpnNetwork() { return active; }
 boolean isSessionCancelled() { return cancelled; }
 class Network {
   int calls;
   boolean dnsFailure;
   boolean replaceOnResponse;
   boolean replaceOnRead;
   URLConnection openConnection(URL url, Proxy proxy) throws IOException {
     if (proxy != Proxy.NO_PROXY) throw new AssertionError("proxy used");
     calls++;
     if (dnsFailure) throw new UnknownHostException("simulated VPN DNS failure");
     return new HttpURLConnection(url) {
       public void disconnect() {}
       public boolean usingProxy() { return false; }
       public void connect() {}
       public int getResponseCode() {
         if (replaceOnResponse) active = new Network();
         return url.getHost().equals("speed.cloudflare.com") ? 200 : 204;
       }
       public InputStream getInputStream() {
         if (replaceOnRead) active = new Network();
         return new ByteArrayInputStream(new byte[131072]);
       }
     };
   }
 }
""" + "\n".join(methods) + r"""
 static void check(boolean condition, String message) {
   if (!condition) throw new AssertionError(message);
 }
 public static void main(String[] args) {
   QualityNetworkTest t = new QualityNetworkTest();
   check(t.measureTunnelStability() == 0, "missing VPN stability");
   check(t.measureTunnelLatencyMs() == -1, "missing VPN latency");
   check(t.measureTunnelDownloadKbps() == -1, "missing VPN speed");
   t.active = t.new Network();
   Network vpn = t.active;
   check(t.measureTunnelStability() == 5, "stable VPN rejected");
   check(t.measureTunnelLatencyMs() >= 0, "healthy latency rejected");
   check(t.measureTunnelDownloadKbps() > 0, "healthy download rejected");
   check(vpn.calls == 9, "probes did not use captured VPN");
   t.active = t.new Network();
   t.active.dnsFailure = true;
   check(t.measureTunnelStability() == 0, "DNS failure accepted");
   check(t.measureTunnelLatencyMs() == -1, "DNS latency accepted");
   check(t.measureTunnelDownloadKbps() == -1, "DNS speed accepted");
   t.active = t.new Network(); t.active.replaceOnResponse = true;
   check(t.measureTunnelStability() == 0, "network switch accepted for stability");
   t.active = t.new Network(); t.active.replaceOnResponse = true;
   check(t.measureTunnelLatencyMs() == -1, "network switch accepted for latency");
   t.active = t.new Network(); t.active.replaceOnRead = true;
   check(t.measureTunnelDownloadKbps() == -1, "network switch accepted for download");
   t.active = t.new Network(); t.cancelled = true;
   check(t.measureTunnelStability() == 0 && t.measureTunnelLatencyMs() == -1
     && t.measureTunnelDownloadKbps() == -1 && t.active.calls == 0, "cancelled probes ran");
   System.out.println("PASS: VPN-only quality probes, absent VPN, DNS failure, network replacement, cancellation.");
 }
}
"""
with tempfile.TemporaryDirectory() as directory:
    directory = Path(directory)
    java = directory / "QualityNetworkTest.java"
    java.write_text(harness)
    compiler = shutil.which("javac")
    if compiler:
        command = [compiler]
    else:
        launcher = directory / "Compile.java"
        launcher.write_text("import javax.tools.ToolProvider; class Compile { public static void main(String[] a) { System.exit(ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,a));}}")
        command = ["java", str(launcher)]
    subprocess.run(command + ["-d", str(directory), str(java)], check=True)
    subprocess.run(["java", "-cp", str(directory), "QualityNetworkTest"], check=True)
