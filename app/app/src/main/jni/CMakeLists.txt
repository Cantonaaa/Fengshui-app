cmake_minimum_required(VERSION 3.4.1)
project("yoloworld")

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ncnn（各 ABI 的预编译库）
set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(yoloworld SHARED yoloworld.cpp yoloworld.h)
target_include_directories(yoloworld PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/include)
target_link_libraries(yoloworld ncnn)
