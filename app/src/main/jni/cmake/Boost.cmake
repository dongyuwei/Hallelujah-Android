# Downloads the Boost source distribution at configure time. librime on
# Android uses Boost headers only, so no compiled Boost libraries are linked.

set(BOOST_VERSION 1.89.0)
set(BOOST_ARCHIVE "boost_1_89_0.tar.bz2")
set(BOOST_URL "https://archives.boost.io/release/${BOOST_VERSION}/source/${BOOST_ARCHIVE}")
# SHA256 of the official boost_1_89_0.tar.bz2
set(BOOST_SHA256 85a33fa22621b4f314f8e85e1a5e2a9363d22e4f4992925d4bb3bc631b5a0c7a)

set(BOOST_ROOT_DIR "${CMAKE_SOURCE_DIR}/boost")

if(NOT EXISTS "${CMAKE_SOURCE_DIR}/${BOOST_ARCHIVE}")
  message(STATUS "Downloading Boost ${BOOST_VERSION} ...")
  file(DOWNLOAD "${BOOST_URL}" "${CMAKE_SOURCE_DIR}/${BOOST_ARCHIVE}"
       EXPECTED_HASH SHA256=${BOOST_SHA256} SHOW_PROGRESS)
endif()

if(NOT EXISTS "${BOOST_ROOT_DIR}/boost/version.hpp")
  message(STATUS "Extracting Boost ${BOOST_VERSION} ...")
  file(ARCHIVE_EXTRACT INPUT "${CMAKE_SOURCE_DIR}/${BOOST_ARCHIVE}"
       DESTINATION "${CMAKE_BINARY_DIR}/boost_extract")
  file(RENAME "${CMAKE_BINARY_DIR}/boost_extract/boost_${BOOST_VERSION}"
       "${BOOST_ROOT_DIR}")
endif()
