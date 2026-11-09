"""Run on a dedicated Android device: python3 scripts/check-smart-connect.py APK.

Requires adb, an unlocked device on the home screen, and a fresh app install.
Accept Android's VPN permission prompt if shown. The script taps Connect once.
"""
import argparse
import hashlib
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.rahyab.next"
DIAGNOSTICS = "shared_prefs/rah_yab_diagnostics.xml"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=90).strip()


def verified_events(xml):
    entries = {node.attrib.get("name"): node.text for node in ET.fromstring(xml)}
    if entries.get("connection_lamp_state") != "connected":
        return None
    events = (entries.get("native_event_history") or "").splitlines()
    expected = ("AUTO_TUNNEL_PROBE_OK", "AUTO_EXIT_IP_CHANGED", "AUTO_BEST_SELECTED")
    for index in range(len(events) - len(expected), -1, -1):
        candidate = events[index:index + len(expected)]
        names = [event.partition(" | ")[2].partition(" — ")[0] for event in candidate]
        if tuple(names) == expected:
            return candidate
    return None


def main(apk, authorize_emulator, alternative=False):
    button_text = "اتصال جایگزین • VLESS / Trojan" if alternative else "اتصال قبلی • OpenVPN"
    if adb("shell", "pm", "list", "packages", PACKAGE) == f"package:{PACKAGE}":
        raise SystemExit("Use a dedicated Android device without RahYab installed.")
    print("APK SHA-256:", hashlib.sha256(apk.read_bytes()).hexdigest(), flush=True)
    print("Device:", adb("shell", "getprop", "ro.product.model"),
          "Android", adb("shell", "getprop", "ro.build.version.release"), flush=True)
    if authorize_emulator and not adb("get-serialno").startswith("emulator-"):
        raise SystemExit("Automatic VPN consent is limited to the local test emulator.")
    adb("install", str(apk))
    if authorize_emulator:
        adb("shell", "cmd", "appops", "set", PACKAGE, "ACTIVATE_VPN", "allow")
    launch = adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    print(launch, flush=True)
    if "Status: ok" not in launch:
        print(adb("logcat", "-d", "-b", "crash", "-t", "80"), flush=True)
        raise SystemExit("Android did not report a successful activity launch.")
    for attempt in range(8):
        adb("shell", "uiautomator", "dump", "/data/local/tmp/rahyab-ui.xml")
        ui = ET.fromstring(adb("shell", "cat", "/data/local/tmp/rahyab-ui.xml"))
        buttons = [node for node in ui.iter("node")
                   if node.get("text") == button_text and node.get("enabled") == "true"
                   and node.get("package") == PACKAGE]
        if len(buttons) == 1:
            break
        scroll = next((node for node in ui.iter("node")
                       if node.get("package") == PACKAGE and node.get("scrollable") == "true"), None)
        if scroll is not None:
            left, top, right, bottom = map(int, re.findall(r"\d+", scroll.attrib["bounds"]))
            x = str((left + right) // 2)
            adb("shell", "input", "swipe", x, str(top + (bottom - top) * 4 // 5),
                x, str(top + (bottom - top) // 5), "350")
        time.sleep(1)
    else:
        packages = sorted({node.get("package", "") for node in ui.iter("node")})
        print(adb("logcat", "-d", "-b", "crash", "-t", "80"), flush=True)
        raise SystemExit(f"Expected one enabled Smart Connect button. Visible packages: {packages}")
    try:
        initial = ET.fromstring(adb("shell", "run-as", PACKAGE, "cat", DIAGNOSTICS))
        initial_values = {node.get("name"): node.text for node in initial}
    except subprocess.CalledProcessError:
        initial_values = {}
    if initial_values.get("service_launch_request_id"):
        raise SystemExit("FAIL: Opening the app started a VPN request without a tap.")
    left, top, right, bottom = map(int, re.findall(r"\d+", buttons[0].attrib["bounds"]))
    connect_x, connect_y = str((left + right) // 2), str((top + bottom) // 2)
    if authorize_emulator:
        # Regression: a discovery response arriving after Disconnect must not
        # start the VPN. Exercise the normal visible buttons on the emulator.
        adb("shell", "input", "tap", connect_x, connect_y)
        # UI input is asynchronous. Wait briefly for the one tap to be
        # rendered; never re-tap Connect or skip the cancellation assertion.
        stop_buttons = []
        for _ in range(5):
            adb("shell", "uiautomator", "dump", "/data/local/tmp/rahyab-ui.xml")
            cancel_xml = adb("shell", "cat", "/data/local/tmp/rahyab-ui.xml")
            cancel_ui = ET.fromstring(cancel_xml)
            stop_buttons = [node for node in cancel_ui.iter("node")
                            if node.get("text") == "قطع اتصال" and node.get("enabled") == "true"
                            and node.get("package") == PACKAGE]
            if len(stop_buttons) == 1:
                break
            time.sleep(1)
        if len(stop_buttons) != 1:
            print(cancel_xml, flush=True)
            raise SystemExit("FAIL: Disconnect is unavailable during discovery.")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", stop_buttons[0].attrib["bounds"]))
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(35)
        stopped = ET.fromstring(adb("shell", "run-as", PACKAGE, "cat", DIAGNOSTICS))
        stopped_values = {node.get("name"): node.text for node in stopped}
        if stopped_values.get("connection_lamp_state") != "disconnected":
            raise SystemExit("FAIL: A cancelled discovery restarted the VPN.")
        print("PASS: Disconnect cancels discovery and delayed results.", flush=True)
        adb("shell", "uiautomator", "dump", "/data/local/tmp/rahyab-ui.xml")
        restart_ui = ET.fromstring(adb("shell", "cat", "/data/local/tmp/rahyab-ui.xml"))
        restart_buttons = [node for node in restart_ui.iter("node")
                           if node.get("text") == button_text and node.get("enabled") == "true"
                           and node.get("package") == PACKAGE]
        if len(restart_buttons) != 1:
            raise SystemExit("FAIL: Smart Connect is unavailable after cancellation.")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", restart_buttons[0].attrib["bounds"]))
        connect_x, connect_y = str((x1 + x2) // 2), str((y1 + y2) // 2)
    adb("shell", "input", "tap", connect_x, connect_y)
    print("Tapped Smart Connect once. Accept the Android VPN prompt if shown.", flush=True)
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        try:
            if alternative:
                import json
                state = json.loads(adb("shell", "run-as", PACKAGE, "cat", "files/singbox-status.json"))
                evidence = [state["last_detail"]] if state.get("last_event") == "AUTO_CONNECTED" else None
                if state.get("last_event") == "AUTO_FAILED":
                    runtime_log = adb("shell", "run-as", PACKAGE, "tail", "-n", "100", "files/singbox-runtime.log")
                    runtime_log = re.sub(r"[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}", "[redacted]", runtime_log)
                    print(runtime_log, flush=True)
                    print(adb("logcat", "-d", "-s", "RahYabSingBox:I", "*:S"), flush=True)
                    raise SystemExit("FAIL: Alternative engine: " + state.get("last_detail", ""))
            else:
                evidence = verified_events(adb("shell", "run-as", PACKAGE, "cat", DIAGNOSTICS))
        except (subprocess.CalledProcessError, ET.ParseError):
            evidence = None
        if evidence:
            if alternative:
                transport_log = adb("logcat", "-d", "-s", "RahYabSingBox:I", "*:S")
                if "Physical transport socket protected and bound" not in transport_log:
                    raise SystemExit("FAIL: No protected physical relay socket was observed.")
                print("PASS: Physical relay socket protection and binding were invoked.", flush=True)
            print(*evidence, sep="\n")
            print("PASS: sing-box URLTest and VPN-bound HTTPS egress." if alternative else "PASS: Smart Connect, HTTPS 204, and changed VPN exit IP.")
            if alternative and authorize_emulator:
                import json
                cache = json.loads(adb("shell", "run-as", PACKAGE, "cat", "files/singbox-success-pool.json"))
                assert cache.get("saved_at", 0) > 0 and cache.get("network_key")
                print("PASS: Successful pool saved privately with freshness and network scope.", flush=True)
                previous = state.get("last_event_time", 0)
                print("Testing automatic recovery after a controlled physical network outage…", flush=True)
                try:
                    adb("shell", "svc", "wifi", "disable")
                    adb("shell", "svc", "data", "disable")
                    time.sleep(70)
                finally:
                    adb("shell", "svc", "data", "enable")
                    adb("shell", "svc", "wifi", "enable")
                recovered = False
                recovery_deadline = time.monotonic() + 240
                while time.monotonic() < recovery_deadline:
                    current = json.loads(adb("shell", "run-as", PACKAGE, "cat", "files/singbox-status.json"))
                    if current.get("last_event") == "AUTO_FAILED":
                        raise SystemExit("FAIL: Automatic recovery stopped: " + current.get("last_detail", ""))
                    if current.get("last_event") == "AUTO_CONNECTED" and current.get("last_event_time", 0) > previous:
                        recovered = True
                        break
                    time.sleep(3)
                if not recovered:
                    raise SystemExit("FAIL: VPN did not recover automatically after restoring the network.")
                print("PASS: VPN HTTPS recovered after network loss without another Connect tap.", flush=True)
            return
        time.sleep(3)
    raise SystemExit("FAIL: No verified HTTPS tunnel and changed exit IP within 10 minutes.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--authorize-emulator", action="store_true")
    parser.add_argument("--alternative", action="store_true")
    args = parser.parse_args()
    main(args.apk, args.authorize_emulator, args.alternative)


