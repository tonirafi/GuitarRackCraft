# Copyright (C) 2026 Kamil Lulko <kamil.lulko@gmail.com>
#
# This file is part of Guitar RackCraft.
#
# Guitar RackCraft is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.

# =============================================================================
# cmake/targets/mda.cmake — MDA LV2 (36 effects and instruments, DSP only)
# Each plugin is its own .so inside one mda.lv2 bundle.
# =============================================================================

set(_mda_root  "${THIRD_PARTY}/mda-lv2")
set(_mda_src   "${_mda_root}/src")
set(_mda_ttl   "${_mda_root}/mda.lv2")
set(_mda_wrap  "${_mda_root}/lvz")
set(_mda_assets "${ASSETS_DIR}/mda.lv2")
set(_mda_build "${CMAKE_BINARY_DIR}/mda")

set(_mda_plugins
    Ambience Bandisto BeatBox Combo DX10 DeEss Degrade Delay Detune Dither
    DubDelay Dynamics EPiano Image JX10 Leslie Limiter Loudness MultiBand
    Overdrive Piano RePsycho RezFilter RingMod RoundPan Shepard Splitter
    Stereo SubSynth TalkBox TestTone ThruZero Tracker Transient VocInput
    Vocoder
)

file(MAKE_DIRECTORY "${_mda_assets}" "${_mda_build}")

file(READ "${_mda_ttl}/manifest.ttl.in" _mda_manifest)
string(REPLACE "@LIB_EXT@" ".so" _mda_manifest "${_mda_manifest}")
grc_write_if_changed("${_mda_assets}/manifest.ttl" "${_mda_manifest}")

file(GLOB _mda_ttls CONFIGURE_DEPENDS "${_mda_ttl}/*.ttl")
foreach(_ttl IN LISTS _mda_ttls)
    get_filename_component(_ttl_name "${_ttl}" NAME)
    configure_file("${_ttl}" "${_mda_assets}/${_ttl_name}" COPYONLY)
endforeach()

set(_mda_targets "")
foreach(_plugin IN LISTS _mda_plugins)
    set(_target "mda_${_plugin}")
    add_library(${_target} SHARED
        "${_mda_wrap}/wrapper.cpp"
        "${_mda_src}/mda${_plugin}.cpp"
    )
    target_include_directories(${_target} PRIVATE
        "${_mda_wrap}"
        "${_mda_src}"
        "${LV2_INCLUDE}"
    )
    target_compile_definitions(${_target} PRIVATE
        "PLUGIN_CLASS=mda${_plugin}"
        "URI_PREFIX=\"http://drobilla.net/plugins/mda/\""
        "PLUGIN_URI_SUFFIX=\"${_plugin}\""
        "PLUGIN_HEADER=\"../src/mda${_plugin}.h\""
    )
    lv2_set_dsp_properties(${_target} "${_plugin}" "${_mda_build}")
    list(APPEND _mda_targets ${_target})
endforeach()

lv2_sync_to_jnilibs(mda_sync "${_mda_build}" "${_mda_targets}")
add_custom_target(mda_done DEPENDS mda_sync)
