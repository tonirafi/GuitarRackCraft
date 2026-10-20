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

enum class ToneNudge {
    MORE_DISTORTION,
    CLEANER,
    LESS_NOISE,
    MORE_BASS,
    MORE_TREBLE,
    MORE_AMBIENCE,
    RESET
}

/**
 * Maps beginner buttons onto real control ports of the loaded chain.
 * A button is omitted when no loaded parameter can represent it.
 */
class BeginnerToneAdjuster {

    fun available(plan: MappedTone): Set<ToneNudge> {
        val set = linkedSetOf<ToneNudge>()
        if (matches(plan, DISTORTION_PARAMS, DISTORTION_CATEGORIES).isNotEmpty()) {
            set += ToneNudge.MORE_DISTORTION
            set += ToneNudge.CLEANER
        }
        if (matches(plan, NOISE_PARAMS, NOISE_CATEGORIES).isNotEmpty() ||
            matches(plan, DISTORTION_PARAMS, DISTORTION_CATEGORIES).isNotEmpty()
        ) {
            set += ToneNudge.LESS_NOISE
        }
        if (matches(plan, BASS_PARAMS, EQ_CATEGORIES).isNotEmpty()) set += ToneNudge.MORE_BASS
        if (matches(plan, TREBLE_PARAMS, EQ_CATEGORIES).isNotEmpty()) set += ToneNudge.MORE_TREBLE
        if (matches(plan, AMBIENCE_PARAMS, AMBIENCE_CATEGORIES).isNotEmpty()) set += ToneNudge.MORE_AMBIENCE
        if (plan.effects.isNotEmpty()) set += ToneNudge.RESET
        return set
    }

    fun adjust(current: MappedTone, original: MappedTone, nudge: ToneNudge): MappedTone {
        if (nudge == ToneNudge.RESET) return original
        if (nudge == ToneNudge.LESS_NOISE &&
            matches(current, NOISE_PARAMS, NOISE_CATEGORIES).isEmpty()
        ) {
            return adjust(current, original, ToneNudge.CLEANER)
        }
        val direction = if (nudge == ToneNudge.CLEANER || nudge == ToneNudge.LESS_NOISE) -1f else 1f
        val (symbols, categories) = targets(nudge)
        val updated = current.effects.map { effect ->
            if (categories.isNotEmpty() && effect.category !in categories && !nameMatches(effect, nudge)) {
                effect
            } else {
                val params = effect.parameters.map { param ->
                    val spec = effect.plugin.parameters.find { it.symbol == param.symbol }
                    if (spec == null || !symbolMatches(param, symbols)) param
                    else param.copy(value = step(param.value, spec, direction, nudge))
                }
                val missing = effect.plugin.parameters.filter { spec ->
                    symbolMatches(
                        MappedParam(spec.symbol, spec.name, spec.portIndex, spec.defaultValue, null),
                        symbols
                    ) && effect.parameters.none { it.symbol == spec.symbol }
                }
                val added = missing.map { spec ->
                    MappedParam(
                        spec.symbol,
                        spec.name,
                        spec.portIndex,
                        step(spec.defaultValue, spec, direction, nudge),
                        spec.defaultValue
                    )
                }
                effect.copy(parameters = params + added)
            }
        }
        return current.copy(effects = updated)
    }

    private fun targets(nudge: ToneNudge): Pair<List<String>, Set<String>> = when (nudge) {
        ToneNudge.MORE_DISTORTION, ToneNudge.CLEANER -> DISTORTION_PARAMS to DISTORTION_CATEGORIES
        ToneNudge.LESS_NOISE -> NOISE_PARAMS to NOISE_CATEGORIES
        ToneNudge.MORE_BASS -> BASS_PARAMS to EQ_CATEGORIES
        ToneNudge.MORE_TREBLE -> TREBLE_PARAMS to EQ_CATEGORIES
        ToneNudge.MORE_AMBIENCE -> AMBIENCE_PARAMS to AMBIENCE_CATEGORIES
        ToneNudge.RESET -> emptyList<String>() to emptySet()
    }

    private fun matches(plan: MappedTone, symbols: List<String>, categories: Set<String>): List<ToneParam> {
        return plan.effects.flatMap { effect ->
            val categoryOk = effect.category in categories || nameMatches(effect, symbols)
            if (!categoryOk) emptyList()
            else effect.plugin.parameters.filter { param ->
                symbols.any { key -> param.symbol.contains(key, true) || param.name.contains(key, true) }
            }
        }
    }

    private fun nameMatches(effect: MappedEffect, nudge: ToneNudge): Boolean {
        val (_, categories) = targets(nudge)
        if (effect.category in categories) return true
        return nameMatches(effect, targets(nudge).first)
    }

    private fun nameMatches(effect: MappedEffect, symbols: List<String>): Boolean {
        val hay = "${effect.name} ${effect.category}".lowercase()
        return symbols.any { hay.contains(it) }
    }

    private fun symbolMatches(param: MappedParam, symbols: List<String>): Boolean {
        return symbols.any { key ->
            param.symbol.contains(key, true) || param.name.contains(key, true)
        }
    }

    private fun step(value: Float, spec: ToneParam, direction: Float, nudge: ToneNudge): Float {
        val span = spec.max - spec.min
        if (span == 0f) return value
        val delta = span * STEP * if (nudge == ToneNudge.LESS_NOISE && spec.symbol.contains("threshold", true)) -direction else direction
        return (value + delta).coerceIn(spec.min, spec.max)
    }

    companion object {
        private const val STEP = 0.12f
        private val DISTORTION_PARAMS = listOf("drive", "gain", "dist", "fuzz", "overdrive", "saturation")
        private val BASS_PARAMS = listOf("bass", "low")
        private val TREBLE_PARAMS = listOf("treble", "presence", "high", "bright")
        private val AMBIENCE_PARAMS = listOf("mix", "wet", "decay", "room", "feedback", "level")
        private val NOISE_PARAMS = listOf("gate", "threshold", "noise")
        private val DISTORTION_CATEGORIES = setOf("Distortion", "Amplifier")
        private val EQ_CATEGORIES = setOf("EQ", "Filter", "Amplifier", "Distortion")
        private val AMBIENCE_CATEGORIES = setOf("Delay", "Reverb")
        private val NOISE_CATEGORIES = setOf("Dynamics", "Distortion", "Amplifier")
    }
}
