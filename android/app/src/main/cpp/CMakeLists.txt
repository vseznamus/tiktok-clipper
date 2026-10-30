# Распознавание речи Whisper: whisper.cpp (MIT, github.com/ggml-org/whisper.cpp) + мост JNI.
# Исходники whisper.cpp скачиваются при сборке (закреплённая версия v1.9.5).
# Только arm64-v8a: на 32-битных телефонах Whisper слишком медленный — там остаётся Vosk.
cmake_minimum_required(VERSION 3.22.1)
project(narezka_whisper C CXX)

if (NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "Whisper: ABI ${ANDROID_ABI} пропущен")
    return()
endif ()

include(FetchContent)
FetchContent_Declare(whisper
        GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
        GIT_TAG v1.9.5 # d1be6fde11ac6e0407606b4e42fe72d34add8037
        GIT_SHALLOW TRUE)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Несколько вариантов вычислительного ядра под разные процессоры телефонов (armv8.0 … armv9.2);
# при запуске ggml сам выбирает лучший, который поддерживает процессор.
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
if (NOT CMAKE_RUNTIME_OUTPUT_DIRECTORY)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})
endif ()
FetchContent_MakeAvailable(whisper)

add_library(narezka_whisper SHARED whisper_jni.c)
target_link_libraries(narezka_whisper whisper ggml ggml-base log)
target_compile_options(narezka_whisper PRIVATE -O2 -Wall)

# Варианты ядра собираются как MODULE-библиотеки; кладём копии в папку jniLibs, чтобы они точно попали в APK.
if (NAREZKA_MODULES_DIR)
    get_property(_ggml_targets DIRECTORY ${whisper_SOURCE_DIR}/ggml/src PROPERTY BUILDSYSTEM_TARGETS)
    foreach (_t IN LISTS _ggml_targets)
        get_target_property(_type ${_t} TYPE)
        if (_type STREQUAL "MODULE_LIBRARY")
            add_custom_command(TARGET ${_t} POST_BUILD
                    COMMAND ${CMAKE_COMMAND} -E make_directory ${NAREZKA_MODULES_DIR}/${ANDROID_ABI}
                    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:${_t}> ${NAREZKA_MODULES_DIR}/${ANDROID_ABI}/)
            add_dependencies(narezka_whisper ${_t})
        endif ()
    endforeach ()
endif ()
