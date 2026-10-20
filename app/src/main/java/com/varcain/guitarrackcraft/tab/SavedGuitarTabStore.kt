/*
 * Copyright (C) 2026 Kamil Lulko <kamil.lulko@gmail.com>
 *
 * This file is part of Guitar RackCraft.
 */

package com.varcain.guitarrackcraft.tab

import com.google.gson.JsonParser
import java.io.File

data class SavedGuitarTab(
    val id: String,
    val title: String,
    val presetName: String,
    val bpm: Int,
    val noteCount: Int
)

class SavedGuitarTabStore(private val dir: File) {
    private val parser = GuitarTabParser()

    fun list(): List<SavedGuitarTab> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> readSaved(file) }
            ?.sortedBy { it.title.lowercase() }
            .orEmpty()
    }

    fun save(tab: GuitarTab, presetName: String): SavedGuitarTab {
        dir.mkdirs()
        val file = File(dir, "${fileStem(tab.title)}.json")
        file.writeText(tab.toInterchangeJson(presetName))
        return readSaved(file) ?: SavedGuitarTab(file.name, tab.title, presetName, tab.bpm, tab.notes.size)
    }

    fun load(id: String): GuitarTab? {
        val parsed = parser.parse(File(dir, id).takeIf { it.isFile }?.readText().orEmpty())
        return (parsed as? TabParseResult.Ok)?.tab
    }

    fun presetName(id: String): String {
        val file = File(dir, id)
        if (!file.isFile) return ""
        return try {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            root.get("presetName")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun delete(id: String) {
        File(dir, id).delete()
    }

    private fun readSaved(file: File): SavedGuitarTab? {
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return null
        }
        val parsed = parser.parse(text) as? TabParseResult.Ok ?: return null
        val preset = try {
            JsonParser.parseString(text).asJsonObject.get("presetName")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        } catch (_: Exception) {
            ""
        }
        return SavedGuitarTab(file.name, parsed.tab.title, preset, parsed.tab.bpm, parsed.tab.notes.size)
    }

    private fun fileStem(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80)
        return cleaned.ifBlank { "Tab" }
    }
}
