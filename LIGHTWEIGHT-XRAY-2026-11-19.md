# Filter Mohsen 1.0.22 — lightweight build and Xray audit

2026-11-19

* User tests: OpenVPN and SSTP each opened real YouTube through Mokhaberat Wi-Fi; SSTP additionally worked on IR-MCI. Xray repeatedly verified HTTP and sometimes HTML but video did not play, and download samples were 31–151 kbps. sing-box opened its Android TUN but HTTPS failed on both networks, even for some relays with a reachable TCP port. WireGuard still needs a valid key-bearing .conf.
* Xray design defect: the automatic five-relay search stopped at its first HTTP/HTML-only result and kept a low-bandwidth relay. Now it proceeds to the next candidate if the current relay only has limited HTTPS or measures below 500 kbps while other candidates remain. The last candidate is kept as LIMITED rather than falsely treated as confirmed video playback. Further real-device verification remains necessary.
* sing-box: remove the libbox 1.14.0 native dependency and remove the Android service entry and public UI entry points. Legacy in-app references temporarily retain a tiny no-op Kotlin compatibility class, which cannot start a VPN. This is an actual removal of the libbox engine, not merely hiding its button.
* WireGuard: add direct browser links in advanced settings to https://www.vpnbook.com/freevpn/wireguard-vpn (no-account provider-generated .conf with 7-day expiry) and https://protonvpn.com/support/wireguard-configurations (official account-based provider configuration instructions). The app does not automatically harvest private keys from websites; users generate/download their own config and import it.
* Keep proven OpenVPN/SSTP service behavior unchanged. Measure APK byte-size saving only after successful CI build.
* No guarantee that public volunteer servers or WireGuard UDP work on any particular Iranian provider.

Sources:
https://www.vpnbook.com/freevpn/wireguard-vpn
https://protonvpn.com/support/wireguard-configurations
https://xtls.github.io/en/config/inbounds/tun.html
