cmake_minimum_required(VERSION 3.22.1)
project(llama_jni)

# Clone llama.cpp into this directory:
#   cd android-node/app/src/main/cpp
#   git clone https://github.com/ggerganov/llama.cpp
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/llama.cpp)

if(NOT EXISTS ${LLAMA_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp source not found at ${LLAMA_CPP_DIR}\n"
        "Run: cd app/src/main/cpp && git clone https://github.com/ggerganov/llama.cpp"
    )
endif()

set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN           OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_CPP_DIR} llama_cpp_build)

add_library(llama_jni SHARED
    src/main/cpp/llama_jni.cpp
)

target_link_libraries(llama_jni
    llama
    ggml
    android
    log
)

target_include_directories(llama_jni PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
    ${LLAMA_CPP_DIR}/common
)
