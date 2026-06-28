cmake_minimum_required(VERSION 3.22.1)
project("facegate" CXX)

# ── FFmpeg prebuilt root directory ────────────────────────────────────────────
if(NOT DEFINED FFMPEG_ROOT)
    if(DEFINED ENV{FFMPEG_ROOT})
        set(FFMPEG_ROOT $ENV{FFMPEG_ROOT})
    else()
        set(FFMPEG_ROOT "${CMAKE_SOURCE_DIR}/../ffmpeg")
        message(WARNING "FFMPEG_ROOT not set — defaulting to ${FFMPEG_ROOT}")
    endif()
endif()

set(FFMPEG_ABI_DIR "${FFMPEG_ROOT}/${ANDROID_ABI}")

if(NOT EXISTS "${FFMPEG_ABI_DIR}/lib")
    message(FATAL_ERROR
        "FFmpeg libraries not found at: ${FFMPEG_ABI_DIR}/lib\n"
        "Download prebuilts from the ffmpeg-builder workflow or set "
        "ffmpeg.dir in local.properties.")
endif()

# ── FFmpeg prebuilt static libs ───────────────────────────────────────────────
foreach(_lib avformat avcodec avutil swresample swscale)
    add_library(${_lib} STATIC IMPORTED)
    set_target_properties(${_lib} PROPERTIES
        IMPORTED_LOCATION "${FFMPEG_ABI_DIR}/lib/lib${_lib}.a"
    )
endforeach()

# ── libyuv from source ────────────────────────────────────────────────────────
set(LIBYUV_SRC_DIR "${CMAKE_SOURCE_DIR}/libyuv-src")

if(NOT EXISTS "${LIBYUV_SRC_DIR}/include/libyuv.h")
    message(FATAL_ERROR
        "libyuv source not found at ${LIBYUV_SRC_DIR}/include/libyuv.h\n"
        "Run: git clone https://chromium.googlesource.com/libyuv/libyuv.git "
        "${LIBYUV_SRC_DIR}")
endif()

# Collect libyuv sources
file(GLOB LIBYUV_SRCS "${LIBYUV_SRC_DIR}/source/*.cc")

# EXCLUDE SVE/SME — these are for server ARM, not Android phones
list(FILTER LIBYUV_SRCS EXCLUDE REGEX ".*_sve\\.cc$")
list(FILTER LIBYUV_SRCS EXCLUDE REGEX ".*_sme\\.cc$")

# Add the library
add_library(yuv STATIC ${LIBYUV_SRCS})
target_include_directories(yuv PUBLIC "${LIBYUV_SRC_DIR}/include")
target_compile_options(yuv PRIVATE -O3 -fPIC)

# Per-file flags: only these files use dotprod+i8mm instructions
# The rest of the library stays at the baseline arm64 target for compatibility
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set_source_files_properties(
        "${LIBYUV_SRC_DIR}/source/compare_neon64.cc"
        "${LIBYUV_SRC_DIR}/source/row_neon64.cc"
        PROPERTIES COMPILE_FLAGS "-march=armv8.2-a+dotprod+i8mm"
    )
endif()

# ── System libraries ──────────────────────────────────────────────────────────
find_library(log-lib     log)
find_library(android-lib android)
find_library(z-lib       z)

# ── ffmpeg_decoder.so ─────────────────────────────────────────────────────────
add_library(ffmpeg_decoder SHARED ffmpeg_decoder.cpp)

target_include_directories(ffmpeg_decoder PRIVATE
    "${FFMPEG_ABI_DIR}/include"
)

target_compile_options(ffmpeg_decoder PRIVATE
    -O3 -fPIC -ffast-math -frtti -fexceptions -DNDEBUG
)

target_link_libraries(ffmpeg_decoder
    avformat avcodec avutil swresample swscale
    ${z-lib} ${log-lib} ${android-lib}
)

target_compile_features(ffmpeg_decoder PRIVATE cxx_std_14)

# ── libyuv_wrapper.so ─────────────────────────────────────────────────────────
add_library(libyuv_wrapper SHARED libyuv_wrapper.cpp)

target_include_directories(libyuv_wrapper PRIVATE
    "${LIBYUV_SRC_DIR}/include"
)

target_compile_options(libyuv_wrapper PRIVATE
    -O3 -fPIC -ffast-math -frtti -fexceptions -DNDEBUG
)

target_link_libraries(libyuv_wrapper
    yuv ${log-lib}
)

target_compile_features(libyuv_wrapper PRIVATE cxx_std_14)
