cmake_minimum_required(VERSION 3.28)
project(ELFBuilder)

set(CMAKE_CXX_STANDARD 17)

include_directories(${PROJECT_SOURCE_DIR}/include)
add_executable(ELFBuilder main.cpp
        ELFLoader.cpp
        ELFLoader.h)

set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -static")