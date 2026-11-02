cmake_minimum_required(VERSION 3.24)
project(RahYabOpenVPN3Probe LANGUAGES CXX)

if(NOT DEFINED OPENVPN3_SOURCE_DIR OR NOT EXISTS "${OPENVPN3_SOURCE_DIR}/client/ovpncli.cpp")
  message(FATAL_ERROR "OPENVPN3_SOURCE_DIR must point to the pinned OpenVPN 3 checkout")
endif()
if(NOT DEFINED VCPKG_INSTALLED_DIR)
  get_filename_component(VCPKG_INSTALLED_DIR "${CMAKE_PREFIX_PATH}" ABSOLUTE)
endif()

find_package(fmt CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(unofficial-lz4 CONFIG REQUIRED)

add_library(asio_headers INTERFACE)
target_include_directories(asio_headers INTERFACE "${VCPKG_INSTALLED_DIR}/include")
add_library(asio::asio ALIAS asio_headers)

add_library(openvpn3_core_probe SHARED
  "${OPENVPN3_SOURCE_DIR}/client/ovpncli.cpp"
  "${OPENVPN3_SOURCE_DIR}/openvpn/crypto/data_epoch.cpp"
)
target_include_directories(openvpn3_core_probe PRIVATE "${OPENVPN3_SOURCE_DIR}")
target_compile_features(openvpn3_core_probe PRIVATE cxx_std_20)
target_compile_definitions(openvpn3_core_probe PRIVATE
  ASIO_STANDALONE USE_ASIO HAVE_LZ4 USE_OPENSSL
)
target_link_libraries(openvpn3_core_probe PRIVATE
  asio::asio fmt::fmt unofficial-lz4::lz4 OpenSSL::SSL OpenSSL::Crypto
  log android
)
