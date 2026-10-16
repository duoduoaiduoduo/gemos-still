# Invoked only via CMAKE_PROJECT_MNN_INCLUDE for the pinned Android MNN build.
# Android.cmake suppresses automatic RPATH flags; API 28's public ELF loader
# supports DT_RUNPATH and expands $ORIGIN to the directory of the loaded object.
if(NOT ANDROID)
  return()
endif()
if(CMAKE_VERSION VERSION_LESS 3.22)
  message(FATAL_ERROR "The reviewed Android runpath hook requires CMake 3.22+")
endif()
function(gemos_mnn_origin_runpath)
  if(NOT TARGET MNN)
    message(FATAL_ERROR "Pinned MNN project did not create its MNN target")
  endif()
  get_target_property(gemos_mnn_type MNN TYPE)
  if(NOT gemos_mnn_type STREQUAL "SHARED_LIBRARY")
    message(FATAL_ERROR "The reviewed MNN runtime must be a shared library")
  endif()
  target_link_options(MNN PRIVATE
    "LINKER:--enable-new-dtags"
    "LINKER:-rpath,\$ORIGIN")
endfunction()
# project(MNN) calls this include before add_library(MNN); defer until the end
# of this same directory. No upstream source or numerical operator is changed.
cmake_language(DEFER CALL gemos_mnn_origin_runpath)
