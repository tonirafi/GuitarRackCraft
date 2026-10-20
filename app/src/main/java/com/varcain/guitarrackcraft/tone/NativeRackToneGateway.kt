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

import com.varcain.guitarrackcraft.engine.NativeEngine
import com.varcain.guitarrackcraft.engine.PresetManager

class NativeRackToneGateway(
    private val engine: NativeEngine = NativeEngine.getInstance()
) : RackToneGateway {

    override fun snapshot(): String? = engine.saveChainState()

    override fun restore(json: String): Boolean = PresetManager(engine).loadPresetFromJson(json)

    override fun setBypass(bypass: Boolean) {
        engine.setChainBypass(bypass)
    }

    override fun clear() {
        val size = engine.getRackSize()
        for (index in (size - 1) downTo 0) {
            engine.removePluginFromRack(index)
        }
    }

    override fun add(fullId: String): Int = engine.addPluginToRack(fullId, -1)

    override fun setParameter(pluginIndex: Int, portIndex: Int, value: Float) {
        engine.setParameter(pluginIndex, portIndex, value)
    }

    override fun currentRack(): List<RackSlot> {
        val size = engine.getRackSize()
        return (0 until size).mapNotNull { index ->
            val info = engine.getRackPluginInfo(index) ?: return@mapNotNull null
            val values = info.ports
                .filter { it.isControl && it.isInput && !it.isAudio && it.symbol.isNotBlank() }
                .associate { port -> port.symbol to engine.getParameter(index, port.index) }
            RackSlot(
                uri = info.id,
                format = info.format.ifBlank { "LV2" },
                name = info.name.ifBlank { info.id },
                valuesBySymbol = values
            )
        }
    }
}
