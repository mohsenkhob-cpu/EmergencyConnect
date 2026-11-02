cmake_minimum_required(VERSION 3.24)
project(RahYabOpenVPN3Probe LANGUAGES CXX)

if(NOT DEFINED OPENVPN3_SOURCE_DIR OR NOT EXISTS "${OPENVPN3_SOURCE_DIR}/client/ovpncli.cpp")
  message(FATAL_ERROR "OPENVPN3_SOURCE_DIR must point to the pinned OpenVPN 3 checkout")
endif()

find_package(asio CONFIG REQUIRED)
find_package(fmt CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(unofficial-lz4 CONFIG REQUIRED)

add_library(openvpn3_core_probe SHARED
  "${OPENVPN3_SOURCE_DIR}/client/ovpncli.cpp"
  "${OPENVPN3_SOURCE_DIR}/openvpn/crypto/data_epoch.cpp"
)
target_include_directories(openvpn3_core_probe PRIVATE "${OPENVPN3_SOURCE_DIR}")
target_compile_features(openvpn3_core_probe PRIVATE cxx_std_20)
target_compile_definitions(openvpn3_core_probe PRIVATE
  ASIO_STANDALONE USE_ASIO HAVE_LZ4 USE_OPENSSL
  OPENVPN_CORE_API_VISIBILITY_HIDDEN
)
target_link_libraries(openvpn3_core_probe PRIVATE
  asio::asio fmt::fmt unofficial-lz4::lz4 OpenSSL::SSL OpenSSL::Crypto
  log android
)
