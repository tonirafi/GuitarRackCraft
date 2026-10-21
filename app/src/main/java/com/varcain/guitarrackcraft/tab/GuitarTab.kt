/*
 * Copyright (C) 2026 Kamil Lulko <kamil.lulko@gmail.com>
 *
 * This file is part of Guitar RackCraft.
 *
 * Guitar RackCraft is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.varcain.guitarrackcraft.tab

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

const val TAB_FORMAT = "guitarrackcraft-tab"
const val TAB_VERSION = 1

data class TabNote(
    val string: Int,
    val fret: Int,
    val beat: Double,
    val duration: Double
)

data class GuitarTab(
    val title: String,
    val bpm: Int,
    val notes: List<TabNote>
)

sealed class TabParseResult {
    data class Ok(val tab: GuitarTab) : TabParseResult()
    data class Failed(val message: String) : TabParseResult()
}

class GuitarTabParser {
    fun parse(raw: String): TabParseResult {
        val payload = extractJson(raw)
        if (payload.isBlank()) return TabParseResult.Failed("Tempel JSON tab dari ChatGPT.")
        val root = try {
            JsonParser.parseString(payload)
        } catch (_: JsonSyntaxException) {
            return TabParseResult.Failed("JSON tab tidak bisa dibaca.")
        }
        if (!root.isJsonObject) return TabParseResult.Failed("JSON tab harus berupa objek.")
        val obj = root.asJsonObject
        val format = obj.get("format")?.takeIf { it.isJsonPrimitive }?.asString
        if (format != TAB_FORMAT) return TabParseResult.Failed("Format tab tidak dikenali.")
        val version = obj.get("version")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        if (version != TAB_VERSION) return TabParseResult.Failed("Versi tab tidak didukung.")
        val title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (title.isEmpty()) return TabParseResult.Failed("Tab butuh judul.")
        val bpm = obj.get("bpm")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            ?: return TabParseResult.Failed("BPM harus angka.")
        if (bpm !in 40..200) return TabParseResult.Failed("BPM harus antara 40 dan 200.")
        val notesEl = obj.get("notes")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return TabParseResult.Failed("Daftar nada kosong.")
        if (notesEl.size() == 0) return TabParseResult.Failed("Daftar nada kosong.")
        if (notesEl.size() > 800) return TabParseResult.Failed("Tab terlalu panjang. Maksimal 800 nada.")
        val notes = mutableListOf<TabNote>()
        for (element in notesEl) {
            if (!element.isJsonObject) return TabParseResult.Failed("Setiap nada harus berupa objek.")
            val note = element.asJsonObject
            val string = intField(note, "string", "s") ?: return TabParseResult.Failed("Senar harus 1 sampai 6.")
            val fret = intField(note, "fret", "f") ?: return TabParseResult.Failed("Fret harus 0 sampai 22.")
            val beat = doubleField(note, "beat") ?: return TabParseResult.Failed("Posisi beat harus angka.")
            val duration = doubleField(note, "duration", "len") ?: return TabParseResult.Failed("Durasi nada harus angka.")
            if (string !in 1..6) return TabParseResult.Failed("Senar harus 1 sampai 6. Senar 1 adalah e tipis.")
            if (fret !in 0..22) return TabParseResult.Failed("Fret harus 0 sampai 22.")
            if (beat < 0 || beat > 400) return TabParseResult.Failed("Posisi beat di luar batas.")
            if (duration <= 0 || duration > 8) return TabParseResult.Failed("Durasi nada di luar batas.")
            notes += TabNote(string, fret, beat, duration)
        }
        return TabParseResult.Ok(GuitarTab(title, bpm, notes.sortedBy { it.beat }))
    }

    private fun extractJson(raw: String): String {
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(raw)
        val body = fence?.groupValues?.get(1) ?: raw
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return ""
        return body.substring(start, end + 1)
    }

    private fun intField(obj: com.google.gson.JsonObject, vararg names: String): Int? {
        for (name in names) {
            val value = obj.get(name) ?: continue
            if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) return value.asInt
        }
        return null
    }

    private fun doubleField(obj: com.google.gson.JsonObject, vararg names: String): Double? {
        for (name in names) {
            val value = obj.get(name) ?: continue
            if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                val number = value.asDouble
                if (number.isFinite()) return number
            }
        }
        return null
    }
}

fun guitarTabPrompt(presetName: String, part: String = ""): String {
    val name = presetName.trim().ifBlank { "Preset" }
    val section = part.trim()
    val partRule = if (section.isEmpty()) {
        "Tulis hanya riff gitar utama lagu ini, dua kali pengulangan yang sama. Jangan menambah verse, chorus, atau solo."
    } else {
        "Tulis hanya bagian \"$section\". Jangan menulis bagian lain."
    }
    return """
        Tuliskan tab gitar lagu "$name" untuk aplikasi GitarRak.
        Balas HANYA satu objek JSON. Jangan Markdown, jangan blok kode, jangan penjelasan.
        $partRule
        Ikuti nada dan ritme lagu itu. Jangan menggubah riff baru, jangan mengganti dengan pola yang mirip, jangan menambah nada supaya tab terlihat panjang.
        Kalau sebuah nada tidak kamu ingat, potong di situ. Jangan menebak.
        Aplikasi memainkan senar terbuka sebagai E A D G B e: senar 6 adalah E rendah, senar 1 adalah e tinggi. Kalau lagu aslinya drop D atau tuning lain, tulis fret pada tuning standar yang nadanya sama. Jangan menulis fret versi tuning lain.
        title wajib persis "$name".
        Bentuk JSON di bawah hanya kerangka. Angka string, fret, dan bpm itu bukan nada lagu dan tidak boleh disalin:
        {"format":"guitarrackcraft-tab","version":1,"title":"$name","bpm":1,"notes":[{"string":1,"fret":1,"beat":0,"duration":1}]}
        Aturan:
        - Kunci hanya format, version, title, bpm, notes.
        - Setiap nada punya string, fret, beat, dan duration. Semuanya angka.
        - string 1 sampai 6. fret 0 sampai 22.
        - beat dihitung dalam ketukan seperempat dari awal bagian ini. Seperdelapan = 0.5, seperenambelas = 0.25.
        - Akor yang dipetik bersama ditulis sebagai beberapa nada dengan beat yang sama dan senar berbeda. Jangan diringkas menjadi satu nada root.
        - bpm adalah tempo lagu tersebut, antara 40 dan 200.
        - Maksimal 160 nada.
    """.trimIndent()
}

fun GuitarTab.toInterchangeJson(presetName: String = ""): String {
    val notesArray = JsonArray()
    notes.forEach { note ->
        notesArray.add(JsonObject().apply {
            addProperty("string", note.string)
            addProperty("fret", note.fret)
            addProperty("beat", note.beat)
            addProperty("duration", note.duration)
        })
    }
    return JsonObject().apply {
        addProperty("format", TAB_FORMAT)
        addProperty("version", TAB_VERSION)
        addProperty("title", title)
        addProperty("bpm", bpm)
        if (presetName.isNotBlank()) addProperty("presetName", presetName)
        add("notes", notesArray)
    }.toString()
}

fun tabAscii(tab: GuitarTab): String {
    val step = 0.5
    val last = tab.notes.maxOf { it.beat }
    val cols = ((last / step).toInt() + 2).coerceIn(8, 48)
    val grid = Array(6) { CharArray(cols) { '-' } }
    for (note in tab.notes) {
        val col = (note.beat / step).toInt().coerceIn(0, cols - 1)
        val row = (note.string - 1).coerceIn(0, 5)
        val text = note.fret.toString()
        for (i in text.indices) {
            val at = col + i
            if (at < cols) grid[row][at] = text[i]
        }
    }
    val names = listOf("e", "B", "G", "D", "A", "E")
    return grid.mapIndexed { index, chars -> "${names[index]}|${String(chars)}" }.joinToString("\n")
}

object GuitarTabSynth {
    private val openMidi = intArrayOf(64, 59, 55, 50, 45, 40)

    fun render(tab: GuitarTab, sampleRate: Int = 48000): FloatArray {
        val secondsPerBeat = 60.0 / tab.bpm
        val endBeat = tab.notes.maxOf { it.beat + it.duration }
        val seconds = (endBeat * secondsPerBeat + 0.35).coerceAtMost(180.0)
        val total = (seconds * sampleRate).roundToInt().coerceAtLeast(1)
        val mix = FloatArray(total)
        for (note in tab.notes) {
            val midi = openMidi[note.string - 1] + note.fret
            val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val start = (note.beat * secondsPerBeat * sampleRate).roundToInt().coerceIn(0, total - 1)
            val length = (note.duration * secondsPerBeat * sampleRate).roundToInt().coerceAtLeast(sampleRate / 20)
            pluckInto(mix, start, length, freq, sampleRate)
        }
        var peak = 0f
        for (sample in mix) {
            val abs = kotlin.math.abs(sample)
            if (abs > peak) peak = abs
        }
        if (peak > 0.001f) {
            val gain = 0.72f / peak
            for (i in mix.indices) mix[i] *= gain
        }
        return mix
    }

    private fun pluckInto(mix: FloatArray, start: Int, length: Int, freq: Double, sampleRate: Int) {
        val period = (sampleRate / freq).roundToInt().coerceAtLeast(2)
        val line = FloatArray(period) { (Math.random().toFloat() * 2f - 1f) }
        val decay = (0.992f + (80.0 / freq).toFloat() * 0.004f).coerceIn(0.99f, 0.998f)
        val limit = minOf(mix.size, start + length + sampleRate / 8)
        var index = 0
        var cursor = start
        while (cursor < limit) {
            val current = line[index % period]
            val envelope = if (index < length) 1f else {
                val left = limit - cursor
                (left / (sampleRate / 8f)).coerceIn(0f, 1f)
            }
            val attack = if (index < 48) index / 48f else 1f
            mix[cursor] += current * envelope * attack * 0.8f
            val averaged = (current + line[(index + 1) % period]) * 0.5f * decay
            line[index % period] = averaged
            index++
            cursor++
        }
    }
}

fun writePcm16Wav(file: File, samples: FloatArray, sampleRate: Int) {
    val dataBytes = samples.size * 2
    val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray())
    buffer.putInt(36 + dataBytes)
    buffer.put("WAVE".toByteArray())
    buffer.put("fmt ".toByteArray())
    buffer.putInt(16)
    buffer.putShort(1)
    buffer.putShort(1)
    buffer.putInt(sampleRate)
    buffer.putInt(sampleRate * 2)
    buffer.putShort(2)
    buffer.putShort(16)
    buffer.put("data".toByteArray())
    buffer.putInt(dataBytes)
    for (sample in samples) {
        val clipped = sample.coerceIn(-1f, 1f)
        buffer.putShort((clipped * 32767f).roundToInt().toShort())
    }
    file.parentFile?.mkdirs()
    file.writeBytes(buffer.array())
}
