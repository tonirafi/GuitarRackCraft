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

/**
 * Maps an interchange document onto plugins that are actually installed.
 * Unknown URIs and symbols stay as errors. Nothing is clamped or dropped quietly.
 */
class TonePresetMapper {

    fun map(
        document: AiToneDocument,
        catalog: ToneCatalog,
        currentRack: List<RackSlot> = emptyList()
    ): MappedTone {
        val issues = mutableListOf<ToneIssue>()
        val suggestions = mutableListOf<PluginSuggestion>()
        val effects = mutableListOf<MappedEffect>()

        document.effects.forEachIndexed { index, effect ->
            val uri = stripFormatPrefix(effect.pluginUri)
            if (!effect.enabled) {
                issues += ToneIssue(
                    ToneIssueCode.EMPTY_CHAIN,
                    ToneIssueSeverity.WARNING,
                    "Efek #${index + 1} dimatikan, jadi tidak masuk rack."
                )
                return@forEachIndexed
            }
            val plugin = catalog.find(uri)
            if (plugin == null) {
                val looksLikeUri = uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("urn:")
                val code = if (looksLikeUri) ToneIssueCode.MISSING_PLUGIN else ToneIssueCode.UNKNOWN_PLUGIN
                val label = if (looksLikeUri) "tidak terpasang" else "tidak dikenal"
                val alts = suggest(uri, catalog)
                alts.forEach { suggestions += PluginSuggestion(uri, it) }
                val extra = if (alts.isEmpty()) "" else " Coba: ${alts.joinToString { it.name }}."
                issues += ToneIssue(
                    code,
                    ToneIssueSeverity.ERROR,
                    "Efek #${index + 1} $label: $uri.$extra"
                )
                return@forEachIndexed
            }
            val mappedParams = mutableListOf<MappedParam>()
            var effectOk = true
            effect.parameters.forEach { (symbol, raw) ->
                val param = plugin.parameters.find { it.symbol == symbol }
                if (param == null || !param.writable || !param.verified) {
                    effectOk = false
                    issues += ToneIssue(
                        ToneIssueCode.UNKNOWN_PARAMETER,
                        ToneIssueSeverity.ERROR,
                        "${plugin.name}: parameter '$symbol' tidak ada atau belum terverifikasi."
                    )
                    return@forEach
                }
                if (raw < param.min.toDouble() || raw > param.max.toDouble()) {
                    effectOk = false
                    issues += ToneIssue(
                        ToneIssueCode.OUT_OF_RANGE,
                        ToneIssueSeverity.ERROR,
                        "${plugin.name}: '$symbol' = $raw di luar ${param.min}…${param.max}."
                    )
                    return@forEach
                }
                if (param.kind == ToneValueKind.INTEGER && raw % 1.0 != 0.0) {
                    effectOk = false
                    issues += ToneIssue(
                        ToneIssueCode.INVALID_NUMBER,
                        ToneIssueSeverity.ERROR,
                        "${plugin.name}: '$symbol' harus bilangan bulat."
                    )
                    return@forEach
                }
                if (param.kind == ToneValueKind.ENUMERATION &&
                    param.scalePoints.none { kotlin.math.abs(it.value - raw.toFloat()) < 0.001f }
                ) {
                    effectOk = false
                    issues += ToneIssue(
                        ToneIssueCode.INVALID_ENUM,
                        ToneIssueSeverity.ERROR,
                        "${plugin.name}: '$symbol' harus salah satu nilai daftar."
                    )
                    return@forEach
                }
                if (param.toggle && !isToggleValue(raw, param)) {
                    effectOk = false
                    issues += ToneIssue(
                        ToneIssueCode.INVALID_TOGGLE,
                        ToneIssueSeverity.ERROR,
                        "${plugin.name}: '$symbol' hanya boleh ${param.min} atau ${param.max}."
                    )
                    return@forEach
                }
                val previous = currentRack.getOrNull(effects.size)?.valuesBySymbol?.get(symbol)
                mappedParams += MappedParam(
                    symbol = symbol,
                    name = param.name,
                    portIndex = param.portIndex,
                    value = raw.toFloat(),
                    previous = previous
                )
            }
            if (effectOk) {
                effects += MappedEffect(
                    uri = plugin.uri,
                    fullId = plugin.fullId,
                    name = plugin.name,
                    category = plugin.category,
                    parameters = mappedParams,
                    plugin = plugin
                )
            }
        }

        if (effects.isEmpty()) {
            issues += ToneIssue(
                ToneIssueCode.EMPTY_CHAIN,
                ToneIssueSeverity.ERROR,
                "Tidak ada efek yang bisa dipasang."
            )
        }

        val orderChanged = currentRack.map { it.uri } != effects.map { it.uri }
        return MappedTone(
            name = document.name,
            description = document.description,
            effects = effects,
            issues = issues,
            suggestions = suggestions,
            orderChanged = orderChanged,
            document = document.copy(effects = document.effects.map {
                it.copy(pluginUri = stripFormatPrefix(it.pluginUri))
            })
        )
    }

    private fun isToggleValue(raw: Double, param: ToneParam): Boolean {
        val eps = 0.001
        return kotlin.math.abs(raw - param.min) < eps || kotlin.math.abs(raw - param.max) < eps
    }

    private fun suggest(uri: String, catalog: ToneCatalog): List<TonePlugin> {
        val tokens = uri.substringAfterLast('/').substringAfterLast('#')
            .lowercase()
            .split('_', '-', '.', ' ')
            .filter { it.length > 2 }
        if (tokens.isEmpty()) return emptyList()
        return catalog.plugins.map { plugin ->
            val hay = "${plugin.name} ${plugin.category}".lowercase()
            val score = tokens.count { token -> hay.contains(token) }
            plugin to score
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(2)
            .map { it.first }
    }

    companion object {
        fun stripFormatPrefix(uri: String): String {
            val trimmed = uri.trim()
            listOf("LV2:", "VST2:", "VST3:").forEach { prefix ->
                if (trimmed.startsWith(prefix, ignoreCase = true)) {
                    return trimmed.substring(prefix.length)
                }
            }
            return trimmed
        }
    }
}
