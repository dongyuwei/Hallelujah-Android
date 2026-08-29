# OpenCC doesn't expose its generated opencc_config.h via a target include
# dir, so copy all public headers (sources + generated config) into one place.
file(GLOB LIBOPENCC_HEADERS
     "${CMAKE_SOURCE_DIR}/librime/deps/opencc/src/*.hpp"
     "${CMAKE_BINARY_DIR}/librime/deps/opencc/src/opencc_config.h")
file(COPY ${LIBOPENCC_HEADERS} DESTINATION "${CMAKE_BINARY_DIR}/include/opencc")
