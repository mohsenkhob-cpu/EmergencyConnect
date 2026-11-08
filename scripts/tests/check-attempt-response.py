#!/usr/bin/env python3
"""Regression: a reply at 1s with CONNECTED at 11s must survive the silent deadline."""
from pathlib import Path
import subprocess
import tempfile
import shutil
root = Path(__file__).resolve().parents[2]
service = (root / "app/src/main/java/ir/emergencyconnect/engine/RahYabVpnService.java").read_text()
start = service.index("private boolean awaitAttemptSignal(")
opening = service.index("{", start)
depth, end = 1, opening + 1
while depth:
    depth += (service[end] == "{") - (service[end] == "}")
    end += 1
method = service[start:end]
harness = r"""
package ir.emergencyconnect.engine;
import java.util.concurrent.*;
public class AttemptWaitTest {
 volatile boolean attemptPeerPacketReceived;
""" + method + r"""
 static void check(boolean c, String message) { if (!c) throw new AssertionError(message); }
 public static void main(String[] args) throws Exception {
   check(OpenVpnAttemptPolicy.timeoutMillis(false) == 10000, "silent budget changed");
   check(OpenVpnAttemptPolicy.timeoutMillis(true) == 30000, "responding budget not bounded");
   check(OpenVpnAttemptPolicy.isPeerResponseEvent("CONNECTING", ""), "first received packet ignored");
   check(OpenVpnAttemptPolicy.isPeerResponseEvent("GET_CONFIG", ""), "configuration ignored");
   check(OpenVpnAttemptPolicy.isPeerResponseEvent("CORE_LOG", "tls handshake: peer certificate: cn=test"), "TLS ignored");
   for (String event : new String[]{"WAIT", "RESOLVE", "RECONNECTING", "NATIVE_CONNECT_BEGIN"}) {
     check(!OpenVpnAttemptPolicy.isPeerResponseEvent(event, ""), "local activity extends wait");
   }
   check(!OpenVpnAttemptPolicy.isPeerResponseEvent("CORE_LOG", "connecting to 192.0.2.1 via tcp"), "local log extends wait");
   check(!OpenVpnAttemptPolicy.isPeerResponseEvent("CORE_LOG", "sending peer info: test"), "peer info extends wait");
   AttemptWaitTest t = new AttemptWaitTest();
   CountDownLatch late = new CountDownLatch(1);
   Thread peer = new Thread(() -> {
     try {
       Thread.sleep(1000);
       t.attemptPeerPacketReceived = OpenVpnAttemptPolicy.isPeerResponseEvent("CONNECTING", "");
       Thread.sleep(10000);
       late.countDown();
     } catch (InterruptedException e) { throw new RuntimeException(e); }
   });
   peer.start();
   boolean connected = t.awaitAttemptSignal(late);
   peer.join();
   check(connected, "responding peer stopped before late CONNECTED at 11s");
   t.attemptPeerPacketReceived = false;
   long start = System.nanoTime();
   check(!t.awaitAttemptSignal(new CountDownLatch(1)), "silent peer accepted");
   long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
   check(millis >= 9900 && millis < 13000, "silent peer took responding budget: " + millis);
   CountDownLatch stop = new CountDownLatch(1);
   t.attemptPeerPacketReceived = true;
   Thread stopper = new Thread(() -> {try {Thread.sleep(200);stop.countDown();}catch(InterruptedException e){throw new RuntimeException(e);}});
   stopper.start();
   start = System.nanoTime();
   check(t.awaitAttemptSignal(stop), "stop did not release latch");
   check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000, "stop waited for handshake budget");
   stopper.join();
   System.out.println("PASS: first incoming packet extends deadline, late CONNECTED retained, silent timeout, prompt stop.");
 }
}
"""
with tempfile.TemporaryDirectory() as d:
    d = Path(d)
    target = d / "AttemptWaitTest.java"
    target.write_text(harness)
    policy = root / "app/src/main/java/ir/emergencyconnect/engine/OpenVpnAttemptPolicy.java"
    compiler = shutil.which("javac")
    if compiler:
        command = [compiler]
    else:
        launcher = d / "Compile.java"
        launcher.write_text("import javax.tools.ToolProvider; class Compile { public static void main(String[] a) { System.exit(ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,a));}}")
        command = ["java", str(launcher)]
    subprocess.run(command + ["-d", str(d), str(policy), str(target)], check=True)
    subprocess.run(["java", "-cp", str(d), "ir.emergencyconnect.engine.AttemptWaitTest"], check=True)
