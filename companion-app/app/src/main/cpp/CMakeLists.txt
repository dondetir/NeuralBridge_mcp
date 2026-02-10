# NeuralBridge JNI Native Library
# Builds JPEG encoder with libjpeg-turbo

cmake_minimum_required(VERSION 3.22.1)
project("neuralbridge_jni")

# C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build flags for optimization
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra")
# TODO: Re-enable -Werror after implementing all JNI functions

# Enable NEON on ARM (hardware-accelerated SIMD)
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon")
endif()

# Create shared library
add_library(neuralbridge_jni SHARED
    jpeg_encoder.cpp
)

# TODO Week 5: Add libjpeg-turbo
# Option 1: Prebuilt library
# set(JPEG_TURBO_DIR ${CMAKE_SOURCE_DIR}/../../../../libjpeg-turbo)
# add_library(jpeg-turbo SHARED IMPORTED)
# set_target_properties(jpeg-turbo PROPERTIES
#     IMPORTED_LOCATION ${JPEG_TURBO_DIR}/lib/${ANDROID_ABI}/libturbojpeg.so
# )
# target_include_directories(neuralbridge_jni PRIVATE
#     ${JPEG_TURBO_DIR}/include
# )
# target_link_libraries(neuralbridge_jni jpeg-turbo)
#
# Option 2: Build from source with ExternalProject
# include(ExternalProject)
# ExternalProject_Add(
#     libjpeg-turbo
#     URL https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/3.0.1.tar.gz
#     CMAKE_ARGS -DCMAKE_BUILD_TYPE=Release -DENABLE_SHARED=ON
#     INSTALL_COMMAND ""
# )

# Link Android libraries
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

target_link_libraries(neuralbridge_jni
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
    # jpeg-turbo  # TODO Week 5: Uncomment after integration
)

# Include directories
target_include_directories(neuralbridge_jni PRIVATE
    ${CMAKE_SOURCE_DIR}
)

# Strip symbols in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set_target_properties(neuralbridge_jni PROPERTIES
        LINK_FLAGS_RELEASE -s
    )
endif()

# Print build configuration
message(STATUS "NeuralBridge JNI Configuration:")
message(STATUS "  Android ABI: ${ANDROID_ABI}")
message(STATUS "  Android API: ${ANDROID_PLATFORM}")
message(STATUS "  Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "  CXX Flags: ${CMAKE_CXX_FLAGS}")
