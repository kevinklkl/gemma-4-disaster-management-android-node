# Install script for directory: C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml

# Set the install prefix
if(NOT DEFINED CMAKE_INSTALL_PREFIX)
  set(CMAKE_INSTALL_PREFIX "C:/Program Files (x86)/llama_jni")
endif()
string(REGEX REPLACE "/$" "" CMAKE_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# Set the install configuration name.
if(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)
  if(BUILD_TYPE)
    string(REGEX REPLACE "^[^A-Za-z0-9_]+" ""
           CMAKE_INSTALL_CONFIG_NAME "${BUILD_TYPE}")
  else()
    set(CMAKE_INSTALL_CONFIG_NAME "Debug")
  endif()
  message(STATUS "Install configuration: \"${CMAKE_INSTALL_CONFIG_NAME}\"")
endif()

# Set the component getting installed.
if(NOT CMAKE_INSTALL_COMPONENT)
  if(COMPONENT)
    message(STATUS "Install component: \"${COMPONENT}\"")
    set(CMAKE_INSTALL_COMPONENT "${COMPONENT}")
  else()
    set(CMAKE_INSTALL_COMPONENT)
  endif()
endif()

# Install shared libraries without execute permission?
if(NOT DEFINED CMAKE_INSTALL_SO_NO_EXE)
  set(CMAKE_INSTALL_SO_NO_EXE "0")
endif()

# Is this installation the result of a crosscompile?
if(NOT DEFINED CMAKE_CROSSCOMPILING)
  set(CMAKE_CROSSCOMPILING "TRUE")
endif()

# Set default install directory permissions.
if(NOT DEFINED CMAKE_OBJDUMP)
  set(CMAKE_OBJDUMP "C:/Users/telef/AppData/Local/Android/Sdk/ndk/25.1.8937393/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objdump.exe")
endif()

if(NOT CMAKE_INSTALL_LOCAL_ONLY)
  # Include the install script for the subdirectory.
  include("C:/Users/telef/gemma-4-disaster-management--android-node/app/.cxx/Debug/464f625n/arm64-v8a/llama_cpp_build/ggml/src/cmake_install.cmake")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so")
    file(RPATH_CHECK
         FILE "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so"
         RPATH "")
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE SHARED_LIBRARY FILES "C:/Users/telef/gemma-4-disaster-management--android-node/app/.cxx/Debug/464f625n/arm64-v8a/bin/libggml.so")
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so")
    if(CMAKE_INSTALL_DO_STRIP)
      execute_process(COMMAND "C:/Users/telef/AppData/Local/Android/Sdk/ndk/25.1.8937393/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so")
    endif()
  endif()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include" TYPE FILE FILES
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-cpu.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-alloc.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-backend.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-blas.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-cann.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-cpp.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-cuda.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-opt.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-metal.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-rpc.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-virtgpu.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-sycl.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-vulkan.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-webgpu.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-zendnn.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/ggml-openvino.h"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/src/main/cpp/llama.cpp/ggml/include/gguf.h"
    )
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so")
    file(RPATH_CHECK
         FILE "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so"
         RPATH "")
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE SHARED_LIBRARY FILES "C:/Users/telef/gemma-4-disaster-management--android-node/app/.cxx/Debug/464f625n/arm64-v8a/bin/libggml-base.so")
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so")
    if(CMAKE_INSTALL_DO_STRIP)
      execute_process(COMMAND "C:/Users/telef/AppData/Local/Android/Sdk/ndk/25.1.8937393/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so")
    endif()
  endif()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/ggml" TYPE FILE FILES
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/.cxx/Debug/464f625n/arm64-v8a/llama_cpp_build/ggml/ggml-config.cmake"
    "C:/Users/telef/gemma-4-disaster-management--android-node/app/.cxx/Debug/464f625n/arm64-v8a/llama_cpp_build/ggml/ggml-version.cmake"
    )
endif()

