find_package(Opencc REQUIRED)

add_library(rime_jni SHARED RimeJni.cpp)

target_link_libraries(rime_jni
        PRIVATE
        # whole-archive: librime registers its modules via static objects that
        # would otherwise be dropped when linking a static archive.
        "-Wl,--whole-archive"
        rime-static
        "-Wl,--no-whole-archive"
        ${Opencc_LIBRARY})
