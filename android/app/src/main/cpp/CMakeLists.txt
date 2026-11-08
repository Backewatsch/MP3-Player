cmake_minimum_required(VERSION 3.22.1)
project(mp3lame C)

# LAME-Quellcode wird mit der iPhone-App geteilt (ThirdParty/lame)
set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ThirdParty/lame)
file(GLOB LAME_SOURCES ${LAME_DIR}/libmp3lame/*.c)

add_library(mp3lame SHARED ${LAME_SOURCES} lame_jni.c)
target_include_directories(mp3lame PRIVATE ${LAME_DIR}/include ${LAME_DIR}/libmp3lame)
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H)
target_link_libraries(mp3lame m log)
