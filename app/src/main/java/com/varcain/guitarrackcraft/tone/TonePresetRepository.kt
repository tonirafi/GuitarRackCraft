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

import com.google.gson.JsonParser
import java.io.File

interface TonePresetRepository {
    fun listSaved(): List<SavedAiTone>
    fun listRecent(): List<SavedAiTone>
    fun save(document: AiToneDocument): SavedAiTone
    fun remember(document: AiToneDocument): SavedAiTone
    fun rename(id: String, newName: String): SavedAiTone?
    fun delete(id: String): Boolean
    fun find(id: String): SavedAiTone?
}

/**
 * Saved AI tones live in their own folder, separate from native rack presets.
 * A raw interchange file (no library wrapper) still loads.
 */
class FileTonePresetRepository(private val root: File) : TonePresetRepository {

    private val parser = TonePresetParser()
    private val savedDir = File(root, "saved")
    private val recentDir = File(root, "recent")

    override fun listSaved(): List<SavedAiTone> = list(savedDir)

    override fun listRecent(): List<SavedAiTone> = list(recentDir).take(MAX_RECENT)

    override fun save(document: AiToneDocument): SavedAiTone = write(savedDir, document, null)

    override fun remember(document: AiToneDocument): SavedAiTone {
        val stored = write(recentDir, document, null)
        trim(recentDir, MAX_RECENT)
        return stored
    }

    override fun rename(id: String, newName: String): SavedAiTone? {
        val existing = readFile(File(savedDir, id)) ?: return null
        if (newName.isBlank()) return null
        val updated = existing.document.copy(name = newName.trim())
        delete(id)
        return write(savedDir, updated, fileStem(newName))
    }

    override fun delete(id: String): Boolean {
        val saved = File(savedDir, id)
        val recent = File(recentDir, id)
        val deletedSaved = saved.exists() && saved.delete()
        val deletedRecent = recent.exists() && recent.delete()
        return deletedSaved || deletedRecent
    }

    override fun find(id: String): SavedAiTone? {
        return readFile(File(savedDir, id)) ?: readFile(File(recentDir, id))
    }

    private fun list(dir: File): List<SavedAiTone> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { readFile(it) }
            ?.sortedByDescending { it.savedAt }
            ?: emptyList()
    }

    private fun write(dir: File, document: AiToneDocument, stem: String?): SavedAiTone {
        if (!dir.exists()) dir.mkdirs()
        val savedAt = System.currentTimeMillis()
        val base = stem ?: fileStem(document.name)
        var file = File(dir, "$base.json")
        var n = 2
        while (file.exists()) {
            file = File(dir, "$base-$n.json")
            n++
        }
        val wrapper = com.google.gson.JsonObject()
        wrapper.addProperty("storage", STORAGE)
        wrapper.addProperty("storageVersion", 1)
        wrapper.addProperty("savedAt", savedAt)
        wrapper.add("document", JsonParser.parseString(document.toInterchangeJson()))
        file.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(wrapper))
        return SavedAiTone(file.name, document.name, savedAt, document)
    }

    private fun readFile(file: File): SavedAiTone? {
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return null
        }
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            return null
        }
        val documentJson: String
        val savedAt: Long
        if (root.get("storage")?.takeIf { it.isJsonPrimitive }?.asString == STORAGE) {
            val document = root.getAsJsonObject("document") ?: return null
            documentJson = document.toString()
            savedAt = root.get("savedAt")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
                ?: file.lastModified()
        } else if (root.get("format")?.takeIf { it.isJsonPrimitive }?.asString == AI_TONE_FORMAT) {
            documentJson = text
            savedAt = file.lastModified()
        } else {
            return null
        }
        val parsed = parser.parse(documentJson)
        if (parsed !is ParseResult.Ok) return null
        return SavedAiTone(file.name, parsed.document.name, savedAt, parsed.document)
    }

    private fun trim(dir: File, max: Int) {
        val files = dir.listFiles { file -> file.extension == "json" }?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(max).forEach { it.delete() }
    }

    private fun fileStem(name: String): String {
        val cleaned = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
        return cleaned.ifBlank { "nada" }
    }

    companion object {
        const val STORAGE = "guitarrackcraft-ai-tone-library"
        const val MAX_RECENT = 8
    }
}
