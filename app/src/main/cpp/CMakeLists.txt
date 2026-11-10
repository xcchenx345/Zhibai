cmake_minimum_required(VERSION 3.22.1)

project("zhibai_adb")

set(CMAKE_CXX_STANDARD 17)

find_package(boringssl REQUIRED CONFIG)
find_package(cxx REQUIRED CONFIG)

add_library(adb SHARED
        adb_pairing.cpp
        mm_codec.cpp)

target_link_libraries(adb log boringssl::crypto_static cxx::cxx)
