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


def main(apk, authorize_emulator):
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
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    for attempt in range(8):
        adb("shell", "uiautomator", "dump", "/data/local/tmp/rahyab-ui.xml")
        ui = ET.fromstring(adb("shell", "cat", "/data/local/tmp/rahyab-ui.xml"))
        buttons = [node for node in ui.iter("node")
                   if node.get("text") == "اتصال هوشمند" and node.get("enabled") == "true"
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
        raise SystemExit(f"Expected one enabled Smart Connect button. Visible packages: {packages}")
    left, top, right, bottom = map(int, re.findall(r"\d+", buttons[0].attrib["bounds"]))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    print("Tapped Smart Connect once. Accept the Android VPN prompt if shown.", flush=True)
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        try:
            evidence = verified_events(adb("shell", "run-as", PACKAGE, "cat", DIAGNOSTICS))
        except (subprocess.CalledProcessError, ET.ParseError):
            evidence = None
        if evidence:
            print(*evidence, sep="\n")
            print("PASS: Smart Connect, HTTPS 204, and changed VPN exit IP.")
            return
        time.sleep(3)
    raise SystemExit("FAIL: No verified HTTPS tunnel and changed exit IP within 10 minutes.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--authorize-emulator", action="store_true")
    args = parser.parse_args()
    main(args.apk, args.authorize_emulator)
