cmake_minimum_required(VERSION 3.22.1)
project(audioenc C)

# 和 iPhone 版共用同一份编码器源码（仓库根目录的 Codecs / Vendor）
set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

file(GLOB LAME_SRC ${ROOT}/Vendor/lame/libmp3lame/*.c)
file(GLOB OGG_SRC ${ROOT}/Vendor/ogg/src/*.c)
file(GLOB VORBIS_SRC ${ROOT}/Vendor/vorbis/lib/*.c)

add_library(audioenc SHARED
        jni_bridge.c
        ${ROOT}/Codecs/AudioEncoders.c
        ${LAME_SRC}
        ${OGG_SRC}
        ${VORBIS_SRC})

target_include_directories(audioenc PRIVATE
        ${ROOT}/Codecs
        ${ROOT}/Vendor/lame/include
        ${ROOT}/Vendor/lame/libmp3lame
        ${ROOT}/Vendor/ogg/include
        ${ROOT}/Vendor/vorbis/include
        ${ROOT}/Vendor/vorbis/lib)

target_compile_definitions(audioenc PRIVATE HAVE_CONFIG_H)
target_compile_options(audioenc PRIVATE -w -O2)
target_link_libraries(audioenc m log)
