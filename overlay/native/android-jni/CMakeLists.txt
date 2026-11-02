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
target_link_libraries(openvpn3_jni PRIVATE log android)
set_target_properties(openvpn3_jni PROPERTIES
  CXX_STANDARD 20
  CXX_STANDARD_REQUIRED YES
  OUTPUT_NAME openvpn3_jni
)
