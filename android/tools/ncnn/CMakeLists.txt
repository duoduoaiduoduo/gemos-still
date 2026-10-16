cmake_minimum_required(VERSION 3.22)
project(gemos_original_patch_probe LANGUAGES CXX)
find_package(ncnn REQUIRED)
# The prebuilt library exports -fno-exceptions to its consumers. Its own
# implementation can keep that setting; this diagnostic catches file errors.
get_target_property(ncnn_consumer_options ncnn INTERFACE_COMPILE_OPTIONS)
if(ncnn_consumer_options)
  list(REMOVE_ITEM ncnn_consumer_options "-fno-exceptions")
  set_property(TARGET ncnn PROPERTY INTERFACE_COMPILE_OPTIONS "${ncnn_consumer_options}")
endif()
add_executable(gemos-patch-probe patch_probe.cpp)
target_compile_features(gemos-patch-probe PRIVATE cxx_std_17)
target_link_libraries(gemos-patch-probe PRIVATE ncnn)
target_compile_options(gemos-patch-probe PRIVATE -Wall -Wextra -Wpedantic -fexceptions)
