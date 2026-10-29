cmake_minimum_required(VERSION 3.20)
project(mseffmpegjni C)

set(CMAKE_C_STANDARD 11)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

if(NOT DEFINED FFMPEG_ROOT)
  message(FATAL_ERROR "FFMPEG_ROOT must be ffmpeg-build out/<slug> (include/ + shared lib/)")
endif()

# The ffmpeg CLI and this JNI must load the same libav DSOs. A prefix that only has
# libavformat.a would silently static-link a second copy into libmseffmpegjni.
set(_avformat_shared "")
foreach(_n libavformat.so libavformat.dylib avformat.dll libavformat.dll)
  foreach(_d ${FFMPEG_ROOT}/lib ${FFMPEG_ROOT}/bin ${FFMPEG_ROOT})
    if(EXISTS "${_d}/${_n}")
      set(_avformat_shared "${_d}/${_n}")
    endif()
  endforeach()
endforeach()
# ffmpeg on Windows ships the shared libs with a version-in-name: `avformat-63.dll` (no `lib`
# prefix), not `avformat.dll`. Match the pattern instead of a fixed set of names.
if(NOT _avformat_shared)
  foreach(_d ${FFMPEG_ROOT}/bin ${FFMPEG_ROOT}/lib ${FFMPEG_ROOT})
    file(GLOB _av_dlls "${_d}/avformat-*.dll" "${_d}/libavformat-*.dll")
    if(_av_dlls)
      list(GET _av_dlls 0 _avformat_shared)
      break()
    endif()
  endforeach()
endif()
if(NOT _avformat_shared)
  message(FATAL_ERROR
    "FFMPEG_ROOT=${FFMPEG_ROOT} has no shared libavformat (.so/.dylib/.dll). "
    "Rebuild ffmpeg-build with --enable-shared (scripts/build-android.sh / build-desktop.sh). "
    "Do not static-link libav into mseffmpegjni.")
endif()

if(NOT DEFINED PRODUCTION)
  set(PRODUCTION 0)
endif()

set(MSE_SOURCES mse_ffmpeg.c mse_host.c mse_vt.c mse_host_jni.c audio_fx.c audio_fx_jni.c
  vpresent.c vpresent_cpu.c)
add_library(mseffmpegjni SHARED ${MSE_SOURCES})
# MinGW on Windows adds a `lib` prefix to shared libs; the JVM loader expects mseffmpegjni.dll
# (System.mapLibraryName on Windows has no prefix). Drop it so MinGW matches MSVC and the loader.
if(WIN32)
  set_target_properties(mseffmpegjni PROPERTIES PREFIX "")
  # Statically link the MinGW runtime so mseffmpegjni.dll has no outside deps on the user's
  # machine. Without this it loads libgcc_s_seh-1.dll / libwinpthread-1.dll (which only exist
  # if MinGW-w64 is installed) and startup fails with "Can't find dependent libraries".
  target_link_options(mseffmpegjni PRIVATE
    -static-libgcc
    -Wl,-Bstatic,--whole-archive -lwinpthread -Wl,--no-whole-archive,-Bdynamic
  )
endif()
target_compile_definitions(mseffmpegjni PRIVATE PRODUCTION=${PRODUCTION})
target_include_directories(mseffmpegjni PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/../../quickjs/native
  ${FFMPEG_ROOT}/include)
target_link_directories(mseffmpegjni PRIVATE ${FFMPEG_ROOT}/lib)
# Same shared libav the packed ffmpeg CLI loads. LAME/shine/dav1d/vpx are inside libavcodec.
target_link_libraries(mseffmpegjni PRIVATE avformat avcodec swscale swresample avutil)
target_compile_options(mseffmpegjni PRIVATE -O2 -Wall -Wextra)
# Only the JNIEXPORT entry points stay visible; unreferenced sections are dropped at link.
target_compile_options(mseffmpegjni PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
if(APPLE)
  target_link_options(mseffmpegjni PRIVATE "-Wl,-dead_strip")
else()
  target_link_options(mseffmpegjni PRIVATE "-Wl,--gc-sections")
endif()

if(ANDROID)
  find_library(log-lib log)
  target_link_libraries(mseffmpegjni PRIVATE android ${log-lib} mediandk m z atomic)
  # -Bsymbolic: ffmpeg aarch64 asm uses ADRP against globals; lld only accepts that
  # in a DSO when those symbols cannot bind externally (ffmpeg SHFLAGS does the same).
  target_link_options(mseffmpegjni PRIVATE
    "-Wl,-Bsymbolic"
    "-Wl,-z,max-page-size=16384"
    "-Wl,-rpath,\$ORIGIN")
  return()
endif()

# Local-file playback (FileAvNative) exists only in the desktop JVM app.
target_sources(mseffmpegjni PRIVATE file_av.c file_av_jni.c)

if(APPLE)
    enable_language(OBJC)
endif()

# @loader_path / $ORIGIN for a packed tree (CLI + JNI + libav in one dir). The prefix lib/
# rpath is for tests running out of the cmake build dir before the copy.
if(APPLE)
  target_link_options(mseffmpegjni PRIVATE
    "-Wl,-rpath,@loader_path"
    "-Wl,-rpath,${FFMPEG_ROOT}/lib"
    "-Wl,-headerpad_max_install_names")
elseif(UNIX)
  target_link_options(mseffmpegjni PRIVATE
    "-Wl,-rpath,\$ORIGIN"
    "-Wl,-rpath,${FFMPEG_ROOT}/lib")
  # libpulse-simple is dlopen'd by aq_pulse.c; only its headers are needed here.
  find_path(PULSE_INCLUDE_DIR pulse/simple.h REQUIRED)
  target_sources(mseffmpegjni PRIVATE aq_pulse.c mse_hwdev.c)
  target_include_directories(mseffmpegjni PRIVATE ${PULSE_INCLUDE_DIR})
  target_link_libraries(mseffmpegjni PRIVATE dl m pthread)
endif()

if(WIN32)
  target_sources(mseffmpegjni PRIVATE
    mse_d3d11.c mse_d3d11_dec.c mse_d3d11_jawt.c vpresent_d3d11.c
    aq_winmm.c)
  # d3dcompiler.lib runtime-compiles the NV12 shader; dxguid.lib provides IID_* GUID symbols.
  # winmm.lib is waveOut* for aq_winmm (file_av AudioQueue shim on Windows).
  target_link_libraries(mseffmpegjni PRIVATE d3d11 dxgi d3dcompiler dxguid winmm)
  target_compile_definitions(mseffmpegjni PRIVATE MSE_WIN_HW=1)
endif()

if(APPLE)
  target_sources(mseffmpegjni PRIVATE
    mse_vt_present.m mse_vt_layer.m mse_vt_dec.m mse_vt_blit.c vpresent_vt.c)
  set_source_files_properties(mse_vt_present.m mse_vt_layer.m mse_vt_dec.m PROPERTIES COMPILE_FLAGS "-fobjc-arc")
  # JAWT is dlopen'd at runtime (System.loadLibrary("jawt")). Do not link libjawt:
  # its install name is @rpath/libjawt.dylib and the JVM, not this DSO, owns that rpath.
  target_link_libraries(mseffmpegjni PRIVATE
    "-framework AudioToolbox"
    "-framework CoreAudio"
    "-framework VideoToolbox"
    "-framework CoreMedia"
    "-framework CoreFoundation"
    "-framework CoreVideo"
    "-framework AVFoundation"
    "-framework QuartzCore"
    "-framework CoreImage"
    "-framework CoreGraphics"
    "-framework AppKit"
  )
endif()

if(NOT DEFINED ENV{JAVA_HOME})
  message(FATAL_ERROR "JAVA_HOME must be set to a JDK (with headers) to build the host JNI library")
endif()
target_include_directories(mseffmpegjni PRIVATE
  $ENV{JAVA_HOME}/include
  $ENV{JAVA_HOME}/include/darwin
  $ENV{JAVA_HOME}/include/linux
  $ENV{JAVA_HOME}/include/win32
)
