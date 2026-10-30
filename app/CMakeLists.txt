cmake_minimum_required(VERSION 3.22.1)
project("gemini_native")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    gemini_native
    SHARED
    src/main/cpp/gemini_renderer.cpp
    src/main/cpp/gemini_api.cpp
    src/main/cpp/gemini_jni.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(gles-lib GLESv3)
find_library(egl-lib EGL)

target_link_libraries(
    gemini_native
    ${log-lib}
    ${android-lib}
    ${gles-lib}
    ${egl-lib}
)
