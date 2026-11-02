# Android native dependency build

This directory contains the vcpkg manifest used to build the OpenVPN 3 core dependencies for Android arm64-v8a.

- The vcpkg baseline is pinned to the same baseline declared by OpenVPN 3 commit 0abc8313b8fe5f63dbebc10d53094952957845d9.
- The Asio overlay port is taken from that exact OpenVPN 3 source checkout, preserving its six upstream patches.
- The Android triplet links dependencies statically.
- Windows-only tap-windows6 and host-only gtest are deliberately excluded.

This stage builds dependencies only. It does not yet compile OpenVPN 3 core, JNI, or a VPN engine shared library.
