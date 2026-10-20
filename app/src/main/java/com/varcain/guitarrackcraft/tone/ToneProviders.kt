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

interface ToneSearchProvider {
    val isConfigured: Boolean
    suspend fun search(query: String): ToneSearchResult
}

interface ToneGenerationProvider {
    val isConfigured: Boolean
    suspend fun generate(request: String, references: List<ToneSearchHit>, catalog: ToneCatalog): ToneGenerationResult
}

data class ToneServiceConfig(
    val generationUrl: String = "",
    val generationApiKey: String = "",
    val generationModel: String = ""
) {
    val configured: Boolean get() = generationUrl.isNotBlank()
}

class UnconfiguredToneGenerationProvider : ToneGenerationProvider {
    override val isConfigured: Boolean = false

    override suspend fun generate(
        request: String,
        references: List<ToneSearchHit>,
        catalog: ToneCatalog
    ): ToneGenerationResult {
        return ToneGenerationResult.NotConfigured(
            "Pembuatan nada otomatis belum diatur. Isi URL layanan chat di bagian pengaturan, atau tempel JSON dari ChatGPT."
        )
    }
}
