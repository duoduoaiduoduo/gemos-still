cmake_minimum_required(VERSION 3.22)
project(gemos_original_rest_probe LANGUAGES CXX)

set(MNN_SOURCE_DIR "" CACHE PATH "Pinned MNN source checkout")
set(MNN_LIBRARY "" CACHE FILEPATH "MNN shared library with the OpenCL backend included")
if(NOT EXISTS "${MNN_SOURCE_DIR}/include/MNN/Interpreter.hpp" OR NOT EXISTS "${MNN_LIBRARY}")
  message(FATAL_ERROR "Pass MNN_SOURCE_DIR and MNN_LIBRARY from the reviewed pinned MNN build")
endif()
add_executable(gemos-rest-probe rest_probe.cpp)
target_compile_features(gemos-rest-probe PRIVATE cxx_std_17)
target_compile_options(gemos-rest-probe PRIVATE -Wall -Wextra -Wpedantic -fexceptions)
target_include_directories(gemos-rest-probe SYSTEM PRIVATE
  "${MNN_SOURCE_DIR}/include"
  "${MNN_SOURCE_DIR}/source"
  "${MNN_SOURCE_DIR}/schema/current"
  "${MNN_SOURCE_DIR}/3rd_party/flatbuffers/include"
  "${MNN_SOURCE_DIR}/3rd_party/half"
  "${MNN_SOURCE_DIR}/3rd_party/OpenCLHeaders")
target_link_libraries(gemos-rest-probe PRIVATE "${MNN_LIBRARY}" ${CMAKE_DL_LIBS})
set_target_properties(gemos-rest-probe PROPERTIES
  BUILD_WITH_INSTALL_RPATH TRUE
  BUILD_RPATH "\$ORIGIN"
  INSTALL_RPATH "\$ORIGIN")

