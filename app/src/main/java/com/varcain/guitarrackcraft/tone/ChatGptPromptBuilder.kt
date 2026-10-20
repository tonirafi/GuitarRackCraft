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

data class PromptBuildResult(
    val text: String,
    val usable: Boolean,
    val notice: String
)

/**
 * Builds a compact prompt from plugins whose control ports were actually read.
 * Plugins without a verified range, toggle, or enumeration are left out.
 */
class ChatGptPromptBuilder {

    fun build(
        request: String,
        catalog: ToneCatalog,
        existing: AiToneDocument? = null,
        currentRack: List<RackSlot> = emptyList()
    ): PromptBuildResult {
        val ask = request.trim().ifBlank { "nada gitar latihan" }
        val chosen = select(ask, catalog)
        if (chosen.isEmpty()) {
            return PromptBuildResult(
                text = "",
                usable = false,
                notice = "Belum ada plugin dengan parameter terverifikasi, jadi preset yang sah belum bisa dibuat."
            )
        }
        val text = render(ask, chosen, existing, currentRack)
        return PromptBuildResult(
            text = text,
            usable = true,
            notice = "Prompt disalin. Tempel di ChatGPT, lalu tempel JSON-nya kembali ke sini."
        )
    }

    private fun select(request: String, catalog: ToneCatalog): List<TonePlugin> {
        val verified = catalog.plugins.filter { it.parametersAvailable }
        if (verified.isEmpty()) return emptyList()
        val hay = request.lowercase()
        val clean = hay.contains("clean") || hay.contains("bersih") || hay.contains("jazz")
        val picked = mutableListOf<TonePlugin>()
        roles.forEach { role ->
            val asked = role.keywords.any { hay.contains(it) }
            val wanted = when {
                role.onlyIfAsked -> asked
                role.drive -> !clean || asked
                role.skipWhenClean -> !clean || asked
                else -> true
            }
            if (!wanted) return@forEach
            val pool = verified.filter { plugin ->
                plugin.category in role.categories && picked.none { it.uri == plugin.uri }
            }
            if (pool.isEmpty()) return@forEach
            val preferred = pool.filter { plugin ->
                role.avoid.none { plugin.name.lowercase().contains(it) }
            }
            val best = (preferred.ifEmpty { pool }).maxByOrNull { score(it, hay, role) }
            if (best != null) picked += best
        }
        if (picked.isEmpty()) {
            return verified.sortedByDescending { score(it, hay, null) }.take(3)
        }
        return picked
    }

    private fun score(plugin: TonePlugin, request: String, role: Role?): Int {
        var value = 0
        val name = plugin.name.lowercase()
        name.split(' ', '-', '_').filter { it.length > 2 }.forEach { token ->
            if (request.contains(token)) value += 8
        }
        val prefer = role?.preferFor(request).orEmpty()
        prefer.forEachIndexed { index, token ->
            if (name.contains(token)) value += 24 - index
        }
        role?.avoid?.forEach { token ->
            if (name.contains(token)) value -= 40
        }
        return value
    }

    private fun render(
        request: String,
        chosen: List<TonePlugin>,
        existing: AiToneDocument?,
        currentRack: List<RackSlot>
    ): String {
        var plugins = chosen
        var text = compose(request, plugins, existing, currentRack)
        val dropOrder = listOf("Reverb", "Delay", "Modulator", "EQ", "Filter", "Dynamics", "Compressor", "Distortion")
        while (text.length > MAX_CHARS && plugins.size > 1) {
            val drop = dropOrder.firstNotNullOfOrNull { category ->
                plugins.find { it.category == category }
            } ?: plugins.last()
            plugins = plugins.filter { it.uri != drop.uri }
            text = compose(request, plugins, existing, currentRack)
        }
        return text
    }

    private fun compose(
        request: String,
        plugins: List<TonePlugin>,
        existing: AiToneDocument?,
        currentRack: List<RackSlot>
    ): String {
        val existingBlock = existing?.let {
            "\nNada yang sudah ada, ubah hanya jika permintaan memintanya:\n${it.toInterchangeJson()}\n"
        }.orEmpty()
        val sample = plugins.first()
        val sampleParam = rankedParams(sample).firstOrNull()
        val sampleParameters = if (sampleParam == null) "{}" else {
            """{"${sampleParam.symbol}": ${trim(sampleParam.defaultValue)}}"""
        }
        val required = plugins.joinToString("\n") { "- ${it.uri}" }
        return """
            Kamu menulis preset efek gitar untuk aplikasi NadaRak.
            Permintaan pengguna: $request
            Suara yang diinginkan: ${soundHint(request)}
            Ini perkiraan latihan, bukan setelan studio asli dan bukan nada resmi sebuah lagu.
            $existingBlock
            Balas HANYA satu objek JSON. Jangan Markdown, jangan blok kode, jangan penjelasan.
            Pakai skema ini persis. Nilai contoh di bawah bukan setelan akhir; ganti angkanya, jangan ganti kunci atau URI:
            {
              "format": "$AI_TONE_FORMAT",
              "version": $AI_TONE_VERSION,
              "name": "nama singkat",
              "reference": {"song": "", "artist": "", "style": ""},
              "description": "satu kalimat pendek",
              "effects": [
                {"pluginUri": "${sample.uri}", "enabled": true, "parameters": $sampleParameters}
              ]
            }
            Aturan:
            - Array effects wajib berisi tepat ${plugins.size} objek, urutan sama dengan daftar di bawah, masing-masing satu kali.
            - Jangan menghilangkan plugin. Jangan menambah plugin. Jangan memakai nama plugin sebagai pluginUri.
            - Salin pluginUri karakter demi karakter, termasuk http dan tanda #.
            - Kunci di parameters hanya simbol yang tertulis pada plugin itu. Jangan memakai nama tampilan.
            - Angka memakai satuan plugin, di dalam minimum dan maksimum. Bukan selalu 0 sampai 1.
            - continuous boleh desimal. integer harus bulat. toggle hanya minimum atau maksimum. enumeration hanya salah satu nilai yang tertulis.
            - Plugin lain yang terpasang di aplikasi tidak boleh muncul di JSON.

            Pakai semua plugin ini, urutan ini:
            $required

            Parameter yang boleh diisi:
            ${plugins.joinToString("\n") { renderPlugin(it, currentRack) }}
        """.trimIndent()
    }

    private fun renderPlugin(plugin: TonePlugin, currentRack: List<RackSlot>): String {
        val current = currentRack.find { it.uri == plugin.uri }?.valuesBySymbol.orEmpty()
        val params = rankedParams(plugin).joinToString("\n") { param ->
            val shownCurrent = current[param.symbol] ?: param.defaultValue
            val enum = if (param.scalePoints.isEmpty()) "" else {
                " values=" + param.scalePoints.joinToString(",") { "${it.label}:${trim(it.value)}" }
            }
            val unit = if (param.unit.isBlank()) "" else " unit=${param.unit}"
            "  ${param.symbol} \"${param.name}\" ${param.kind.name.lowercase()} [${trim(param.min)},${trim(param.max)}] default=${trim(param.defaultValue)} current=${trim(shownCurrent)}$unit$enum"
        }
        return "- ${plugin.uri} | ${plugin.name} | ${plugin.category}\n$params"
    }

    private fun rankedParams(plugin: TonePlugin): List<ToneParam> {
        return plugin.parameters.filter { it.verified }.sortedBy { param ->
            val hay = "${param.symbol} ${param.name}".lowercase()
            val rank = PARAM_PREFER.indexOfFirst { hay.contains(it) }
            if (rank < 0) PARAM_PREFER.size else rank
        }.take(MAX_PARAMS)
    }

    private fun soundHint(request: String): String {
        val hay = request.lowercase()
        return when {
            hay.contains("metal") -> "Gain tinggi, bass terkendali, ruang sedikit."
            hay.contains("clean") || hay.contains("bersih") || hay.contains("jazz") ->
                "Bersih, dinamis, dengan sedikit ruang."
            hay.contains("fuzz") -> "Fuzz tebal, tidak terlalu basah."
            hay.contains("rock") || hay.contains("crunch") || hay.contains("distorsi") ->
                "Drive sedang, tepi rock, dan delay pendek kalau delay ada di daftar."
            else -> "Nada gitar yang bisa dimainkan."
        }
    }

    private fun trim(value: Float): String {
        val asLong = value.toLong()
        return if (value == asLong.toFloat()) asLong.toString() else value.toString()
    }

    private data class Role(
        val categories: Set<String>,
        val keywords: List<String>,
        val drive: Boolean = false,
        val skipWhenClean: Boolean = false,
        val onlyIfAsked: Boolean = false,
        val prefer: List<String> = emptyList(),
        val avoid: List<String> = emptyList()
    ) {
        fun preferFor(request: String): List<String> {
            val hay = request.lowercase()
            if (drive) {
                return when {
                    hay.contains("fuzz") -> listOf("fuzz", "muff", "bender")
                    hay.contains("metal") -> listOf("metal", "rat", "muff", "ds1", "ds-1")
                    else -> listOf("screamer", "sd1", "sd-1", "guvnor", "rat", "overdrive", "tube")
                }
            }
            if ("Amplifier" in categories) {
                return when {
                    hay.contains("metal") -> listOf("metal", "terror", "jcm", "plexi")
                    hay.contains("clean") || hay.contains("bersih") || hay.contains("jazz") ->
                        listOf("blue", "studio", "micro", "cream", "amplifier")
                    else -> prefer
                }
            }
            return prefer
        }
    }

    companion object {
        const val MAX_CHARS = 12000
        const val MAX_PARAMS = 16
        private val PARAM_PREFER = listOf(
            "drive", "gain", "dist", "volume", "level", "output", "tone", "bass",
            "middle", "mid", "treble", "presence", "mix", "wet", "dry", "time",
            "delay", "feedback", "decay", "room", "sustain", "threshold", "ratio",
            "attack", "release"
        )
        private val roles = listOf(
            Role(
                setOf("Dynamics", "Compressor"),
                listOf("compressor", "gate", "noise", "sustain", "kompres"),
                prefer = listOf("compressor", "dynamics"),
                avoid = listOf("multi", "expander", "de-ess", "transient", "splitter", "loudness")
            ),
            Role(
                setOf("Distortion"),
                listOf("distorsi", "distortion", "overdrive", "fuzz", "crunch", "drive", "rock", "metal"),
                drive = true,
                avoid = listOf("multi", "dither", "degrade")
            ),
            Role(
                setOf("Amplifier", "Simulator"),
                listOf("amp", "amplifier", "clean", "bersih", "sim"),
                prefer = listOf("plexi", "amplifier", "jcm", "metal", "blue"),
                avoid = listOf("cabinet", "cab", "impulse", "headless", "stereo", "ultrac")
            ),
            Role(
                setOf("EQ"),
                listOf("eq", "equalizer", "bass", "treble"),
                prefer = listOf("graphic", "eq", "tilt"),
                avoid = listOf("muff", "bright")
            ),
            Role(
                setOf("Filter"),
                listOf("wah", "filter", "auto"),
                onlyIfAsked = true,
                prefer = listOf("wah")
            ),
            Role(
                setOf("Modulator"),
                listOf("chorus", "phaser", "flanger", "univibe", "tremolo", "vibe"),
                onlyIfAsked = true
            ),
            Role(
                setOf("Delay"),
                listOf("delay", "echo", "slap"),
                skipWhenClean = true,
                prefer = listOf("delay"),
                avoid = listOf("looper", "duck", "multi")
            ),
            Role(
                setOf("Reverb"),
                listOf("reverb", "ambience", "hall", "room", "ruang"),
                prefer = listOf("reverb", "ambience"),
                avoid = listOf("impulse", "shimmer", "multi")
            )
        )
    }
}
