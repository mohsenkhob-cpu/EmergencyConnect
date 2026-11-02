# RahYabVPN v2 – implementation checkpoint

This branch is reserved for the independent Android VPN engine. The current starter still exports profiles to an external OpenVPN app; it is not yet a standalone VPN.

Required work: integrate OpenVPN 3 Core, Android NDK/CMake and JNI, implement Android VpnService/TUN using TunBuilderBase, protect the VPN transport socket, manage session lifecycle, and validate real tunnel connectivity before claiming completion.
