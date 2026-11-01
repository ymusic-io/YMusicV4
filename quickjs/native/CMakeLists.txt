cmake_minimum_required(VERSION 3.20)
project(quickjsjni C)

set(CMAKE_C_STANDARD 11)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(QJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/quickjs)

# Bellard QuickJS (VERSION 2026-06-04, pinned commit 04be2460) + our JNI shim.
add_library(quickjsjni SHARED
  quickjs_jni.c
  quickjs_json.c
  quickjs_pipe.c
  quickjs_hostfn.c
  quickjs_web.c
  quickjs_mse.c
  ${QJS_DIR}/quickjs.c
  ${QJS_DIR}/cutils.c
  ${QJS_DIR}/libregexp.c
  ${QJS_DIR}/libunicode.c
  ${QJS_DIR}/dtoa.c
)

# MinGW on Windows prefixes shared libs with `lib` by default (Unix convention), producing
# libquickjsjni.dll — but the JVM loader uses System.mapLibraryName("quickjsjni") which on Windows
# resolves to quickjsjni.dll (no prefix). Drop the prefix so one name serves MinGW and MSVC alike.
if(WIN32)
  set_target_properties(quickjsjni PROPERTIES PREFIX "")
  # Statically link the MinGW runtime so quickjsjni.dll has no outside deps on the user's
  # machine. Without this it loads libgcc_s_seh-1.dll / libwinpthread-1.dll (which only exist
  # if MinGW-w64 is installed) and startup fails with "Can't find dependent libraries".
  target_link_options(quickjsjni PRIVATE
    -static-libgcc
    -Wl,-Bstatic,--whole-archive -lwinpthread -Wl,--no-whole-archive,-Bdynamic
  )
endif()

target_include_directories(quickjsjni PRIVATE ${QJS_DIR})

if(ANDROID)
  # The NDK sysroot provides jni.h automatically.
  target_link_libraries(quickjsjni mediandk log)
else()
  # Host JVM: JNI headers straight from JAVA_HOME. We avoid find_package(JNI) (it insists on the
  # AWT/jawt component we don't need) — a JNI shared library resolves JVM symbols at load time.
  if(NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME must be set to a JDK (with headers) to build the host JNI library")
  endif()
  target_include_directories(quickjsjni PRIVATE
    $ENV{JAVA_HOME}/include
    $ENV{JAVA_HOME}/include/darwin
    $ENV{JAVA_HOME}/include/linux
    $ENV{JAVA_HOME}/include/win32
  )
endif()
target_compile_definitions(quickjsjni PRIVATE _GNU_SOURCE CONFIG_VERSION="2026-06-04")
# QuickJS emits many warnings by design; silence them and optimize.
target_compile_options(quickjsjni PRIVATE -w -O2 -funsigned-char)
# Only the JNIEXPORT entry points stay visible; unreferenced sections are dropped at link.
target_compile_options(quickjsjni PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
if(APPLE)
  target_link_options(quickjsjni PRIVATE "-Wl,-dead_strip")
else()
  target_link_options(quickjsjni PRIVATE "-Wl,--gc-sections")
endif()

# ASan/UBSan harness for the C pipe layer — dev-only, never part of the normal build
# (see pipe_stress.c for build/run instructions). It #includes quickjs_native.c itself.
add_executable(pipe_stress EXCLUDE_FROM_ALL
  pipe_stress.c
  quickjs_json.c
  quickjs_pipe.c
  quickjs_hostfn.c
  quickjs_web.c
  ${QJS_DIR}/quickjs.c
  ${QJS_DIR}/cutils.c
  ${QJS_DIR}/libregexp.c
  ${QJS_DIR}/libunicode.c
  ${QJS_DIR}/dtoa.c
)
target_include_directories(pipe_stress PRIVATE ${QJS_DIR})
target_compile_definitions(pipe_stress PRIVATE _GNU_SOURCE CONFIG_VERSION="2026-06-04")
# -UNDEBUG re-arms QuickJS asserts over the Release flags; keep frame pointers for ASan traces.
target_compile_options(pipe_stress PRIVATE -w -g -O1 -UNDEBUG -funsigned-char
  -fsanitize=address,undefined -fno-omit-frame-pointer)
target_link_options(pipe_stress PRIVATE -fsanitize=address,undefined)
# The vendored core has benign int-shift UB patterns; UBSan covers OUR files only, ASan everything.
set_source_files_properties(
  ${QJS_DIR}/quickjs.c ${QJS_DIR}/cutils.c ${QJS_DIR}/libregexp.c ${QJS_DIR}/libunicode.c ${QJS_DIR}/dtoa.c
  PROPERTIES COMPILE_OPTIONS "-fno-sanitize=undefined")
