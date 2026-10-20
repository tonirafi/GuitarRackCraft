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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sends the catalog prompt to a user-configured chat endpoint.
 * The URL is whatever the user saved; this class does not pick a vendor.
 * The body follows the common chat-completions shape:
 * { "model", "messages": [{"role":"user","content"}] }.
 * A bare interchange JSON body is also accepted.
 */
class HttpToneGenerationProvider(
    private val config: ToneServiceConfig,
    private val prompts: ChatGptPromptBuilder = ChatGptPromptBuilder(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
) : ToneGenerationProvider {

    override val isConfigured: Boolean = config.configured

    override suspend fun generate(
        request: String,
        references: List<ToneSearchHit>,
        catalog: ToneCatalog
    ): ToneGenerationResult = withContext(Dispatchers.IO) {
        if (!config.configured) {
            return@withContext ToneGenerationResult.NotConfigured(
                "Isi URL layanan chat di pengaturan AI. Kunci tidak disimpan di kode aplikasi."
            )
        }
        val referenceText = references.joinToString("\n") { "- ${it.title}: ${it.url} ${it.snippet}" }
        val ask = buildString {
            append(request.trim())
            if (referenceText.isNotBlank()) {
                append("\nReferensi publik (bukan preset):\n")
                append(referenceText)
            }
        }
        val built = prompts.build(ask, catalog)
        if (!built.usable) {
            return@withContext ToneGenerationResult.Failed(built.notice)
        }
        val prompt = built.text
        val body = JsonObject()
        if (config.generationModel.isNotBlank()) body.addProperty("model", config.generationModel)
        val messages = com.google.gson.JsonArray()
        val message = JsonObject()
        message.addProperty("role", "user")
        message.addProperty("content", prompt)
        messages.add(message)
        body.add("messages", messages)
        val http = Request.Builder()
            .url(config.generationUrl.trim())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
        if (config.generationApiKey.isNotBlank()) {
            http.header("Authorization", "Bearer ${config.generationApiKey}")
        }
        try {
            client.newCall(http.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ToneGenerationResult.Failed(
                        "Layanan AI menjawab ${response.code}. Tidak ada nada yang dibuat."
                    )
                }
                val content = extractContent(text)
                if (content.isBlank()) {
                    ToneGenerationResult.Failed("Layanan AI tidak mengirim JSON nada.")
                } else {
                    ToneGenerationResult.JsonReady(content)
                }
            }
        } catch (_: Exception) {
            ToneGenerationResult.Failed("Tidak bisa menghubungi layanan AI. Periksa URL dan internet.")
        }
    }

    private fun extractContent(body: String): String {
        val root = try {
            JsonParser.parseString(body)
        } catch (_: Exception) {
            return body
        }
        if (!root.isJsonObject) return body
        val choices = root.asJsonObject.getAsJsonArray("choices") ?: return body
        if (choices.size() == 0) return ""
        val message = choices[0].asJsonObject.getAsJsonObject("message") ?: return ""
        return message.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }
}
