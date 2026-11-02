cmake_minimum_required(VERSION 3.22)
project(rah_yab_openvpn3_android LANGUAGES CXX)

if(NOT DEFINED OPENVPN3_SOURCE_DIR OR NOT EXISTS "${OPENVPN3_SOURCE_DIR}/client/ovpncli.cpp")
  message(FATAL_ERROR "OPENVPN3_SOURCE_DIR must point to the pinned OpenVPN 3 checkout")
endif()
if(NOT DEFINED VCPKG_INSTALLED_DIR)
  message(FATAL_ERROR "VCPKG_INSTALLED_DIR is required")
endif()

set(VCPKG_ANDROID_INCLUDE "${VCPKG_INSTALLED_DIR}/arm64-android/include")
if(NOT EXISTS "${VCPKG_ANDROID_INCLUDE}/asio.hpp")
  message(FATAL_ERROR "Standalone Asio headers not found under ${VCPKG_ANDROID_INCLUDE}")
endif()
# OpenVPN's Findasio.cmake does not consistently discover the vcpkg
# arm64-android include directory from CMAKE_PREFIX_PATH alone.
set(ASIO_INCLUDE_DIR "${VCPKG_ANDROID_INCLUDE}" CACHE PATH "Standalone Asio include directory" FORCE)
# OpenVPN ships a Findlz4.cmake module that uses the legacy LZ4_* variables.
# Point it explicitly at the pinned vcpkg Android release artifacts.
set(LZ4_INCLUDE_DIR "${VCPKG_ANDROID_INCLUDE}" CACHE PATH "LZ4 include directory" FORCE)
set(LZ4_LIBRARY "${VCPKG_INSTALLED_DIR}/arm64-android/lib/liblz4.a" CACHE FILEPATH "LZ4 static library" FORCE)
# OpenVPN uses find_package(fmt); vcpkg installs its config under share/fmt.
set(fmt_DIR "${VCPKG_INSTALLED_DIR}/arm64-android/share/fmt" CACHE PATH "fmt package config directory" FORCE)
if(NOT EXISTS "${fmt_DIR}/fmt-config.cmake" AND NOT EXISTS "${fmt_DIR}/fmtConfig.cmake")
  message(FATAL_ERROR "Pinned Android fmt CMake package was not found under ${fmt_DIR}")
endif()
if(NOT EXISTS "${LZ4_INCLUDE_DIR}/lz4.h" OR NOT EXISTS "${LZ4_LIBRARY}")
  message(FATAL_ERROR "Pinned Android LZ4 headers/library were not found")
endif()

list(APPEND CMAKE_MODULE_PATH "${OPENVPN3_SOURCE_DIR}/cmake")
include(findcoredeps)

add_library(openvpn3_jni SHARED
  jni_bridge.cpp
  "${OPENVPN3_SOURCE_DIR}/client/ovpncli.cpp"
)
add_core_dependencies(openvpn3_jni)

target_compile_definitions(openvpn3_jni PRIVATE
  OPENVPN_PLATFORM_ANDROID
  OPENVPN_EXTERNAL_TUN_FACTORY
  OPENVPN_PACKAGE_ID=ir_emergencyconnect_engine
)
target_include_directories(openvpn3_jni PRIVATE
  "${OPENVPN3_SOURCE_DIR}"
  "${OPENVPN3_SOURCE_DIR}/client"
  "${CMAKE_CURRENT_SOURCE_DIR}"
  "${VCPKG_ANDROID_INCLUDE}"
)
target_link_directories(openvpn3_jni PRIVATE
  "${VCPKG_INSTALLED_DIR}/arm64-android/lib"
)
# add_core_dependencies() uses the plain target_link_libraries signature.
# Keep this target's remaining link additions on the same signature.
target_link_libraries(openvpn3_jni log android)
set_target_properties(openvpn3_jni PROPERTIES
  CXX_STANDARD 20
  CXX_STANDARD_REQUIRED YES
  OUTPUT_NAME openvpn3_jni
)
