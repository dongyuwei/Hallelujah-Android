# Hallelujah-Android native build: librime + dependencies + JNI wrapper.
#
# Structure mirrors the proven Trime/fcitx5-android approach: build librime's
# vendored dependencies (deps/) as static libs, then librime itself
# (BUILD_STATIC), then our own JNI shared library. Only the luna_pinyin_simp
# schema is used, so no librime plugins (lua/octagram/predict) are built.
cmake_minimum_required(VERSION 3.22.1)
project(rime_jni LANGUAGES C CXX)

add_link_options("LINKER:--hash-style=both,--build-id=none")

# Clang 18 (NDK 27) promotes deprecated declarations to hard errors in
# dependency headers (e.g. OpenCC's Segments.hpp); keep them as warnings.
add_compile_options("-Wno-error=deprecated-declarations")

# Our Find modules shadow librime's: they hand librime the in-tree
# add_subdirectory() targets instead of searching the system.
list(INSERT CMAKE_MODULE_PATH 0 "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)

# Boost (header-only usage in librime), downloaded at configure time.
include(Boost)

# glog
option(WITH_GFLAGS "" OFF)
option(WITH_UNWIND "" OFF)
option(WITH_GTEST "" OFF)
add_subdirectory(librime/deps/glog)

# yaml-cpp
option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_BUILD_CONTRIB "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(librime/deps/yaml-cpp)

# leveldb (without snappy compression)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory(librime/deps/leveldb)

# marisa-trie
add_subdirectory(librime/deps/marisa-trie)

# opencc (traditional -> simplified conversion for luna_pinyin_simp)
set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)
# OpenCC's data subdirectory generates .ocd2 files with a target-architecture
# opencc_dict tool, which cannot run on the host in a cross build. The data is
# shipped as prebuilt assets instead, so the whole subproject is excluded from
# "all" (libopencc itself is still built because rime links it) and its
# configure-time python lookup is satisfied with the host interpreter.
find_program(PYTHON_EXECUTABLE python3)
add_subdirectory(librime/deps/opencc EXCLUDE_FROM_ALL)
include(OpenccWorkarounds)

# librime
option(BUILD_MERGED_PLUGINS "" ON)
option(BUILD_STATIC "" ON)
option(BUILD_TEST "" OFF)
option(BUILD_TOOLS "" OFF)
option(BUILD_SAMPLE "" OFF)
option(BUILD_DATA "" OFF)
include_directories(librime/include librime/src)
add_subdirectory(librime)

add_subdirectory(rime_jni)
