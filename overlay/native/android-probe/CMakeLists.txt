cmake_minimum_required(VERSION 3.24)
project(RahYabOpenVPN3Probe LANGUAGES CXX)

if(NOT DEFINED OPENVPN3_SOURCE_DIR OR NOT EXISTS "${OPENVPN3_SOURCE_DIR}/client/ovpncli.cpp")
  message(FATAL_ERROR "OPENVPN3_SOURCE_DIR must point to the pinned OpenVPN 3 checkout")
endif()
if(NOT DEFINED VCPKG_INSTALLED_DIR OR VCPKG_INSTALLED_DIR STREQUAL "")
  message(FATAL_ERROR "VCPKG_INSTALLED_DIR must point to the vcpkg install root")
endif()
set(VCPKG_TRIPLET_ROOT "${VCPKG_INSTALLED_DIR}/arm64-android")
if(NOT EXISTS "${VCPKG_TRIPLET_ROOT}/include")
  set(VCPKG_TRIPLET_ROOT "${VCPKG_INSTALLED_DIR}")
endif()

add_library(asio_headers INTERFACE)
target_include_directories(asio_headers INTERFACE "${VCPKG_TRIPLET_ROOT}/include")
add_library(asio::asio ALIAS asio_headers)
add_library(fmt_headers INTERFACE)
target_include_directories(fmt_headers INTERFACE "${VCPKG_TRIPLET_ROOT}/include")
add_library(fmt::fmt ALIAS fmt_headers)

add_library(OpenSSL::SSL STATIC IMPORTED GLOBAL)
set_target_properties(OpenSSL::SSL PROPERTIES
  IMPORTED_LOCATION "${VCPKG_TRIPLET_ROOT}/lib/libssl.a"
  INTERFACE_INCLUDE_DIRECTORIES "${VCPKG_TRIPLET_ROOT}/include")
add_library(OpenSSL::Crypto STATIC IMPORTED GLOBAL)
set_target_properties(OpenSSL::Crypto PROPERTIES
  IMPORTED_LOCATION "${VCPKG_TRIPLET_ROOT}/lib/libcrypto.a"
  INTERFACE_INCLUDE_DIRECTORIES "${VCPKG_TRIPLET_ROOT}/include")
if(NOT EXISTS "${VCPKG_TRIPLET_ROOT}/lib/libssl.a" OR NOT EXISTS "${VCPKG_TRIPLET_ROOT}/lib/libcrypto.a")
  message(FATAL_ERROR "Pinned vcpkg OpenSSL static libraries were not found under ${VCPKG_TRIPLET_ROOT}/lib")
endif()

# Import the verified vcpkg Android LZ4 artifacts without requiring a package config.
set(LZ4_INCLUDE_DIR "${VCPKG_TRIPLET_ROOT}/include")
set(LZ4_LIBRARY "${VCPKG_TRIPLET_ROOT}/lib/liblz4.a")
if(NOT EXISTS "${LZ4_INCLUDE_DIR}/lz4.h" OR NOT EXISTS "${LZ4_LIBRARY}")
  message(FATAL_ERROR "Pinned vcpkg LZ4 artifacts missing: expected ${LZ4_INCLUDE_DIR}/lz4.h and ${LZ4_LIBRARY}")
endif()
add_library(unofficial-lz4::lz4 STATIC IMPORTED GLOBAL)
set_target_properties(unofficial-lz4::lz4 PROPERTIES
  IMPORTED_LOCATION "${LZ4_LIBRARY}"
  INTERFACE_INCLUDE_DIRECTORIES "${LZ4_INCLUDE_DIR}")

add_library(openvpn3_core_probe SHARED
  "${OPENVPN3_SOURCE_DIR}/client/ovpncli.cpp"
  "${OPENVPN3_SOURCE_DIR}/openvpn/crypto/data_epoch.cpp")
target_include_directories(openvpn3_core_probe PRIVATE "${OPENVPN3_SOURCE_DIR}")
target_compile_features(openvpn3_core_probe PRIVATE cxx_std_20)
target_compile_definitions(openvpn3_core_probe PRIVATE ASIO_STANDALONE USE_ASIO HAVE_LZ4 USE_OPENSSL)
target_link_libraries(openvpn3_core_probe PRIVATE
  asio::asio fmt::fmt unofficial-lz4::lz4 OpenSSL::SSL OpenSSL::Crypto log android)
