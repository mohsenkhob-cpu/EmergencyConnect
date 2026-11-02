# Android TUN adapter scaffold

The extracted Android project has now been inspected. This overlay adds a real Android `VpnService` declaration and a small TUN adapter class.

## Implemented in this checkpoint
- Android recognizes RahYabVpnService as a VPN service.
- The service can establish an Android TUN interface using Builder.
- A transport-socket protection entry point delegates to VpnService.protect().
- TUN descriptor ownership and close behavior are explicit.

## Not implemented yet
- OpenVPN 3 Core C++ source/build and JNI session lifecycle.
- TunBuilder callback mapping for every route, DNS, IPv4 and IPv6 option.
- TUN packet read/write loop and native transport integration.
- Foreground notification/service lifecycle, reconnect and failover.
- Verified end-to-end tunnel tests and IP/DNS leak tests.

This scaffold must not report CONNECTED and is not a standalone VPN by itself.
