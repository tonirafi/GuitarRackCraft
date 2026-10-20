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

import android.content.Context

class ToneServicePreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(): ToneServiceConfig = ToneServiceConfig(
        generationUrl = prefs.getString(KEY_URL, "").orEmpty(),
        generationApiKey = prefs.getString(KEY_KEY, "").orEmpty(),
        generationModel = prefs.getString(KEY_MODEL, "").orEmpty()
    )

    fun write(config: ToneServiceConfig) {
        prefs.edit()
            .putString(KEY_URL, config.generationUrl.trim())
            .putString(KEY_KEY, config.generationApiKey.trim())
            .putString(KEY_MODEL, config.generationModel.trim())
            .apply()
    }

    companion object {
        private const val PREFS = "ai_tone_service"
        private const val KEY_URL = "generation_url"
        private const val KEY_KEY = "generation_api_key"
        private const val KEY_MODEL = "generation_model"
    }
}
