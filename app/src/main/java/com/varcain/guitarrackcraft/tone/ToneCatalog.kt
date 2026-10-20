/*
 * Copyright (C) 2026 Kamil Lulko <kamil.lulko@gmail.com>
 *
 * This file is part of Guitar RackCraft.
 *
 * Guitar RackCraft is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Guitar RackCraft is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Guitar RackCraft. If not, see <https://www.gnu.org/licenses/>.
 */

package com.varcain.guitarrackcraft.tone

import com.google.gson.JsonParser
import com.varcain.guitarrackcraft.engine.PluginInfo

private val LV2_CLASS_TO_CATEGORY = mapOf(
    "DistortionPlugin" to "Distortion",
    "AmplifierPlugin" to "Amplifier",
    "SimulatorPlugin" to "Simulator",
    "DelayPlugin" to "Delay",
    "ModulatorPlugin" to "Modulator",
    "FilterPlugin" to "Filter",
    "ReverbPlugin" to "Reverb",
    "EQPlugin" to "EQ",
    "CompressorPlugin" to "Compressor",
    "LimiterPlugin" to "Dynamics",
    "DynamicsPlugin" to "Dynamics",
    "MultiEQPlugin" to "EQ",
    "PitchPlugin" to "Pitch",
    "ChorusPlugin" to "Modulator",
    "FlangerPlugin" to "Modulator",
    "PhaserPlugin" to "Modulator",
    "EnvelopePlugin" to "Dynamics",
    "GatePlugin" to "Dynamics",
    "ExpanderPlugin" to "Dynamics",
    "UtilityPlugin" to "Utility",
    "AnalyserPlugin" to "Utility",
    "InstrumentPlugin" to "Instrument",
    "GeneratorPlugin" to "Instrument"
)

fun displayCategory(lv2Class: String?): String {
    if (lv2Class.isNullOrBlank()) return "Other"
    return LV2_CLASS_TO_CATEGORY[lv2Class] ?: "Other"
}

fun categoriesFromMetadataJson(json: String): Map<String, String> {
    return try {
        val root = JsonParser.parseString(json).asJsonObject
        val categories = root.getAsJsonObject("categories") ?: return emptyMap()
        categories.entrySet().associate { (name, value) ->
            name to if (value.isJsonPrimitive) value.asString else ""
        }
    } catch (_: Exception) {
        emptyMap()
    }
}

fun toneCatalogFromInstalled(
    plugins: List<PluginInfo>,
    categoriesByName: Map<String, String>
): ToneCatalog {
    val entries = plugins.map { plugin ->
        val lv2Class = categoriesByName.entries
            .find { it.key.equals(plugin.name, ignoreCase = true) }
            ?.value
        val params = plugin.ports.mapNotNull { port ->
            if (!port.isControl || port.isAudio || !port.isInput || port.symbol.isBlank()) {
                null
            } else if (port.symbol.equals("BYPASS", true) || port.symbol.equals("enabled", true)) {
                null
            } else {
                val scales = port.scalePoints.map { ToneScalePoint(it.label, it.value) }
                val kind = when {
                    port.isToggle -> ToneValueKind.TOGGLE
                    scales.isNotEmpty() -> ToneValueKind.ENUMERATION
                    port.isInteger -> ToneValueKind.INTEGER
                    else -> ToneValueKind.CONTINUOUS
                }
                val verified = when (kind) {
                    ToneValueKind.TOGGLE, ToneValueKind.ENUMERATION -> true
                    else -> port.maxValue > port.minValue
                }
                ToneParam(
                    symbol = port.symbol,
                    name = port.name.ifBlank { port.symbol },
                    min = port.minValue,
                    max = port.maxValue,
                    defaultValue = port.defaultValue,
                    writable = true,
                    toggle = port.isToggle,
                    portIndex = port.index,
                    kind = kind,
                    unit = port.unit,
                    scalePoints = scales,
                    verified = verified
                )
            }
        }.filter { it.verified }
        TonePlugin(
            uri = plugin.id,
            format = plugin.format.ifBlank { "LV2" },
            name = plugin.name.ifBlank { plugin.id },
            category = displayCategory(lv2Class),
            parameters = params
        )
    }
    return ToneCatalog(entries)
}
