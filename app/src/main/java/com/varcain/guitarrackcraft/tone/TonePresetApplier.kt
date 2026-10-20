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

sealed class ApplyResult {
    data class Applied(val snapshot: String) : ApplyResult()
    data class Rejected(val issues: List<ToneIssue>) : ApplyResult()
    data class Failed(val message: String, val restored: Boolean) : ApplyResult()
}

/**
 * Rack operations used by the applier. The native implementation
 * calls the existing engine off the audio callback.
 */
interface RackToneGateway {
    fun snapshot(): String?
    fun restore(json: String): Boolean
    fun setBypass(bypass: Boolean)
    fun clear()
    fun add(fullId: String): Int
    fun setParameter(pluginIndex: Int, portIndex: Int, value: Float)
    fun currentRack(): List<RackSlot>
}

class TonePresetApplier(private val gateway: RackToneGateway) {

    fun apply(plan: MappedTone): ApplyResult {
        if (!plan.canApply) return ApplyResult.Rejected(plan.errors)
        val snapshot = gateway.snapshot()
            ?: return ApplyResult.Failed("Keadaan rack tidak bisa disimpan, jadi tidak ada yang diubah.", restored = true)
        gateway.setBypass(true)
        try {
            gateway.clear()
            plan.effects.forEach { effect ->
                val position = gateway.add(effect.fullId)
                if (position < 0) {
                    val restored = gateway.restore(snapshot)
                    return ApplyResult.Failed(
                        "Plugin ${effect.name} gagal dimuat. Rack sebelumnya ${if (restored) "sudah dikembalikan" else "gagal dikembalikan"}.",
                        restored
                    )
                }
                effect.parameters.forEach { param ->
                    gateway.setParameter(position, param.portIndex, param.value)
                }
            }
            return ApplyResult.Applied(snapshot)
        } catch (error: Exception) {
            val restored = try {
                gateway.restore(snapshot)
            } catch (_: Exception) {
                false
            }
            return ApplyResult.Failed(
                error.message ?: "Pemasangan nada gagal.",
                restored
            )
        } finally {
            gateway.setBypass(false)
        }
    }

    /**
     * Updates control values on an already matching chain.
     * If the rack order differs, falls back to a full apply with rollback.
     */
    fun applyParameters(plan: MappedTone): ApplyResult {
        if (!plan.canApply) return ApplyResult.Rejected(plan.errors)
        val current = gateway.currentRack()
        if (current.map { it.uri } != plan.effects.map { it.uri }) {
            return apply(plan)
        }
        val snapshot = gateway.snapshot()
            ?: return ApplyResult.Failed("Keadaan rack tidak bisa disimpan.", restored = true)
        return try {
            plan.effects.forEachIndexed { index, effect ->
                effect.parameters.forEach { param ->
                    gateway.setParameter(index, param.portIndex, param.value)
                }
            }
            ApplyResult.Applied(snapshot)
        } catch (error: Exception) {
            val restored = try {
                gateway.restore(snapshot)
            } catch (_: Exception) {
                false
            }
            ApplyResult.Failed(error.message ?: "Parameter gagal diubah.", restored)
        }
    }
}
