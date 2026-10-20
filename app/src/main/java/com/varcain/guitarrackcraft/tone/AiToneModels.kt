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

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Interchange schema name. This is not the native rack preset format. */
const val AI_TONE_FORMAT = "guitarrackcraft-ai-tone"

const val AI_TONE_VERSION = 1

/**
 * Versioned preset that ChatGPT (or another generator) can emit.
 * Parameter values are native plugin units, not a 0–1 fraction,
 * unless that plugin's own minimum and maximum say so.
 */
data class AiToneDocument(
    val format: String,
    val version: Int,
    val name: String,
    val reference: AiToneReference?,
    val description: String,
    val effects: List<AiToneEffect>
)

data class AiToneReference(
    val song: String = "",
    val artist: String = "",
    val style: String = ""
)

data class AiToneEffect(
    val pluginUri: String,
    val enabled: Boolean,
    val parameters: Map<String, Double>
)

enum class ToneValueKind { CONTINUOUS, INTEGER, TOGGLE, ENUMERATION }

data class ToneScalePoint(val label: String, val value: Float)

data class ToneParam(
    val symbol: String,
    val name: String,
    val min: Float,
    val max: Float,
    val defaultValue: Float,
    val writable: Boolean,
    val toggle: Boolean,
    val portIndex: Int,
    val kind: ToneValueKind = if (toggle) ToneValueKind.TOGGLE else ToneValueKind.CONTINUOUS,
    val unit: String = "",
    val scalePoints: List<ToneScalePoint> = emptyList(),
    /** False when the host did not expose a usable range, enum, or toggle. */
    val verified: Boolean = true
)

data class TonePlugin(
    val uri: String,
    val format: String,
    val name: String,
    val category: String,
    val parameters: List<ToneParam>
) {
    val fullId: String get() = "$format:$uri"
    val parametersAvailable: Boolean get() = parameters.any { it.verified && it.writable }
}

data class ToneCatalog(val plugins: List<TonePlugin>) {
    fun find(uri: String): TonePlugin? = plugins.find { it.uri == uri }
}

enum class ToneIssueCode {
    MALFORMED_JSON,
    MISSING_FIELD,
    UNSUPPORTED_VERSION,
    INVALID_NUMBER,
    UNKNOWN_PLUGIN,
    MISSING_PLUGIN,
    UNKNOWN_PARAMETER,
    OUT_OF_RANGE,
    INVALID_TOGGLE,
    INVALID_ENUM,
    EMPTY_CHAIN
}

enum class ToneIssueSeverity { ERROR, WARNING }

data class ToneIssue(
    val code: ToneIssueCode,
    val severity: ToneIssueSeverity,
    val message: String
)

data class MappedParam(
    val symbol: String,
    val name: String,
    val portIndex: Int,
    val value: Float,
    val previous: Float?
)

data class MappedEffect(
    val uri: String,
    val fullId: String,
    val name: String,
    val category: String,
    val parameters: List<MappedParam>,
    val plugin: TonePlugin
)

data class PluginSuggestion(
    val requestedUri: String,
    val plugin: TonePlugin
)

data class MappedTone(
    val name: String,
    val description: String,
    val effects: List<MappedEffect>,
    val issues: List<ToneIssue>,
    val suggestions: List<PluginSuggestion>,
    val orderChanged: Boolean,
    val document: AiToneDocument
) {
    val errors: List<ToneIssue> get() = issues.filter { it.severity == ToneIssueSeverity.ERROR }
    val canApply: Boolean get() = errors.isEmpty() && effects.isNotEmpty()
}

data class RackSlot(
    val uri: String,
    val format: String,
    val name: String,
    val valuesBySymbol: Map<String, Float>
)

data class ToneSearchHit(
    val title: String,
    val url: String,
    val snippet: String
)

data class ToneSearchResult(
    val configured: Boolean,
    val hits: List<ToneSearchHit>,
    val message: String
)

sealed class ToneGenerationResult {
    data class JsonReady(val text: String) : ToneGenerationResult()
    data class NotConfigured(val message: String) : ToneGenerationResult()
    data class Failed(val message: String) : ToneGenerationResult()
}

data class SavedAiTone(
    val id: String,
    val name: String,
    val savedAt: Long,
    val document: AiToneDocument
)

fun AiToneDocument.toInterchangeJson(): String {
    val root = JsonObject()
    root.addProperty("format", format)
    root.addProperty("version", version)
    root.addProperty("name", name)
    reference?.let { ref ->
        val obj = JsonObject()
        if (ref.song.isNotBlank()) obj.addProperty("song", ref.song)
        if (ref.artist.isNotBlank()) obj.addProperty("artist", ref.artist)
        if (ref.style.isNotBlank()) obj.addProperty("style", ref.style)
        if (obj.size() > 0) root.add("reference", obj)
    }
    root.addProperty("description", description)
    val effectsArray = JsonArray()
    effects.forEach { effect ->
        val obj = JsonObject()
        obj.addProperty("pluginUri", effect.pluginUri)
        obj.addProperty("enabled", effect.enabled)
        val params = JsonObject()
        effect.parameters.forEach { (symbol, value) -> params.addProperty(symbol, value) }
        obj.add("parameters", params)
        effectsArray.add(obj)
    }
    root.add("effects", effectsArray)
    return GsonBuilder().setPrettyPrinting().create().toJson(root)
}
