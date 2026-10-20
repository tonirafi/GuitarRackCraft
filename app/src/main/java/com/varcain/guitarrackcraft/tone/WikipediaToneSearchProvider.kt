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

import com.varcain.guitarrackcraft.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Public Wikipedia search. Results are reference pages, not guitar presets.
 * No API key is required and nothing is scraped from HTML.
 */
class WikipediaToneSearchProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val userAgent: String = "GuitarRackCraft/${BuildConfig.VERSION_NAME} (https://github.com/Varcain/GuitarRackCraft)"
) : ToneSearchProvider {

    override val isConfigured: Boolean = true

    override suspend fun search(query: String): ToneSearchResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext ToneSearchResult(true, emptyList(), "Isi lagu, artis, atau jenis suara dulu.")
        }
        val url = "https://en.wikipedia.org/w/api.php".toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("action", "opensearch")
            .addQueryParameter("limit", "5")
            .addQueryParameter("namespace", "0")
            .addQueryParameter("format", "json")
            .addQueryParameter("search", trimmed)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ToneSearchResult(
                        true,
                        emptyList(),
                        "Wikipedia menolak pencarian (${response.code}). Mode tempel tetap bisa dipakai."
                    )
                }
                val hits = parseOpenSearch(body)
                ToneSearchResult(
                    configured = true,
                    hits = hits,
                    message = if (hits.isEmpty()) {
                        "Tidak ada halaman Wikipedia untuk itu. Kamu tetap bisa tempel JSON dari ChatGPT."
                    } else {
                        "Ini halaman referensi, bukan setting gitar asli."
                    }
                )
            }
        } catch (_: Exception) {
            ToneSearchResult(
                true,
                emptyList(),
                "Pencarian gagal. Periksa internet, atau pakai tempel dari ChatGPT."
            )
        }
    }

    private fun parseOpenSearch(body: String): List<ToneSearchHit> {
        val root = try {
            com.google.gson.JsonParser.parseString(body).asJsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        if (root.size() < 4) return emptyList()
        val titles = root[1].takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val snippets = root[2].takeIf { it.isJsonArray }?.asJsonArray
        val urls = root[3].takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return (0 until minOf(titles.size(), urls.size())).map { index ->
            ToneSearchHit(
                title = titles[index].asString,
                url = urls[index].asString,
                snippet = snippets?.get(index)?.asString.orEmpty()
            )
        }
    }
}
