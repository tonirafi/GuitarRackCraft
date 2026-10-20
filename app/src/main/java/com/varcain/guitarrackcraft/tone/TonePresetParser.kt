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

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

sealed class ParseResult {
    data class Ok(val document: AiToneDocument, val warnings: List<ToneIssue>) : ParseResult()
    data class Failed(val issues: List<ToneIssue>) : ParseResult()
}

/**
 * Parses the interchange document. Imported text is data only:
 * it is never evaluated as code.
 */
class TonePresetParser {

    fun parse(raw: String): ParseResult {
        val payload = extractJsonPayload(raw)
        if (payload.isBlank()) {
            return fail(ToneIssueCode.MALFORMED_JSON, "Teks kosong. Tempel JSON nada.")
        }
        val root = try {
            JsonParser.parseString(payload)
        } catch (_: JsonSyntaxException) {
            return fail(ToneIssueCode.MALFORMED_JSON, "JSON tidak valid. Periksa tanda kutip dan kurung.")
        } catch (_: IllegalStateException) {
            return fail(ToneIssueCode.MALFORMED_JSON, "JSON tidak valid.")
        }
        if (root == null || !root.isJsonObject) {
            return fail(ToneIssueCode.MALFORMED_JSON, "JSON harus berupa objek, bukan daftar atau teks.")
        }
        return parseObject(root.asJsonObject)
    }

    private fun parseObject(root: JsonObject): ParseResult {
        val issues = mutableListOf<ToneIssue>()

        val formatEl = root.get("format")
        if (formatEl == null || formatEl.isJsonNull) {
            issues += issue(ToneIssueCode.MISSING_FIELD, "Field format wajib ada.")
        } else if (!formatEl.isJsonPrimitive || !formatEl.asJsonPrimitive.isString) {
            issues += issue(ToneIssueCode.MALFORMED_JSON, "Field format harus teks.")
        } else if (formatEl.asString != AI_TONE_FORMAT) {
            issues += issue(
                ToneIssueCode.UNSUPPORTED_VERSION,
                "Format tidak dikenal: ${formatEl.asString}."
            )
        }

        val version = readVersion(root, issues)
        val name = readRequiredString(root, "name", issues)
        val description = readOptionalString(root, "description")
        val reference = readReference(root, issues)
        val effects = readEffects(root, issues)

        if (issues.any { it.severity == ToneIssueSeverity.ERROR }) {
            return ParseResult.Failed(issues)
        }
        return ParseResult.Ok(
            AiToneDocument(
                format = AI_TONE_FORMAT,
                version = version ?: AI_TONE_VERSION,
                name = name.orEmpty(),
                reference = reference,
                description = description,
                effects = effects.orEmpty()
            ),
            warnings = issues
        )
    }

    private fun readVersion(root: JsonObject, issues: MutableList<ToneIssue>): Int? {
        val el = root.get("version") ?: run {
            issues += issue(ToneIssueCode.MISSING_FIELD, "Field version wajib ada.")
            return null
        }
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) {
            issues += issue(ToneIssueCode.UNSUPPORTED_VERSION, "Version harus angka bulat.")
            return null
        }
        val number = el.asDouble
        if (!number.isFinite() || number % 1.0 != 0.0) {
            issues += issue(ToneIssueCode.UNSUPPORTED_VERSION, "Version harus angka bulat.")
            return null
        }
        val version = number.toInt()
        if (version != AI_TONE_VERSION) {
            issues += issue(
                ToneIssueCode.UNSUPPORTED_VERSION,
                "Version $version belum didukung. Pakai version $AI_TONE_VERSION."
            )
            return null
        }
        return version
    }

    private fun readReference(root: JsonObject, issues: MutableList<ToneIssue>): AiToneReference? {
        val el = root.get("reference") ?: return null
        if (el.isJsonNull) return null
        if (!el.isJsonObject) {
            issues += issue(ToneIssueCode.MALFORMED_JSON, "Field reference harus objek.")
            return null
        }
        val obj = el.asJsonObject
        return AiToneReference(
            song = readOptionalString(obj, "song"),
            artist = readOptionalString(obj, "artist"),
            style = readOptionalString(obj, "style")
        )
    }

    private fun readEffects(root: JsonObject, issues: MutableList<ToneIssue>): List<AiToneEffect>? {
        val el = root.get("effects") ?: run {
            issues += issue(ToneIssueCode.MISSING_FIELD, "Field effects wajib ada.")
            return null
        }
        if (!el.isJsonArray) {
            issues += issue(ToneIssueCode.MALFORMED_JSON, "Field effects harus berupa daftar.")
            return null
        }
        val effects = mutableListOf<AiToneEffect>()
        el.asJsonArray.forEachIndexed { index, item ->
            if (!item.isJsonObject) {
                issues += issue(ToneIssueCode.MALFORMED_JSON, "Efek #${index + 1} harus objek.")
                return@forEachIndexed
            }
            val obj = item.asJsonObject
            val uri = readRequiredString(obj, "pluginUri", issues, "Efek #${index + 1}")
            val enabled = readEnabled(obj, index, issues)
            val parameters = readParameters(obj, index, issues)
            if (uri != null && enabled != null && parameters != null) {
                effects += AiToneEffect(uri.trim(), enabled, parameters)
            }
        }
        return effects
    }

    private fun readEnabled(obj: JsonObject, index: Int, issues: MutableList<ToneIssue>): Boolean? {
        val el = obj.get("enabled") ?: return true
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isBoolean) {
            issues += issue(
                ToneIssueCode.MALFORMED_JSON,
                "Efek #${index + 1}: enabled harus true atau false."
            )
            return null
        }
        return el.asBoolean
    }

    private fun readParameters(
        obj: JsonObject,
        index: Int,
        issues: MutableList<ToneIssue>
    ): Map<String, Double>? {
        val el = obj.get("parameters") ?: return emptyMap()
        if (el.isJsonNull) return emptyMap()
        if (!el.isJsonObject) {
            issues += issue(
                ToneIssueCode.MALFORMED_JSON,
                "Efek #${index + 1}: parameters harus objek simbol → angka."
            )
            return null
        }
        val values = linkedMapOf<String, Double>()
        var ok = true
        for ((key, value) in el.asJsonObject.entrySet()) {
            val number = readFiniteNumber(value)
            if (number == null) {
                ok = false
                issues += issue(
                    ToneIssueCode.INVALID_NUMBER,
                    "Efek #${index + 1}: nilai '$key' bukan angka yang sah."
                )
            } else {
                values[key] = number
            }
        }
        return if (ok) values else null
    }

    private fun readFiniteNumber(el: JsonElement): Double? {
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) return null
        val number = try {
            el.asDouble
        } catch (_: NumberFormatException) {
            return null
        }
        if (!number.isFinite()) return null
        return number
    }

    private fun readRequiredString(
        obj: JsonObject,
        key: String,
        issues: MutableList<ToneIssue>,
        prefix: String = ""
    ): String? {
        val where = if (prefix.isBlank()) "Field $key" else "$prefix: field $key"
        val el = obj.get(key)
        if (el == null || el.isJsonNull) {
            issues += issue(ToneIssueCode.MISSING_FIELD, "$where wajib ada.")
            return null
        }
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString || el.asString.isBlank()) {
            issues += issue(ToneIssueCode.MISSING_FIELD, "$where harus teks yang tidak kosong.")
            return null
        }
        return el.asString
    }

    private fun readOptionalString(obj: JsonObject, key: String): String {
        val el = obj.get(key) ?: return ""
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) return ""
        return el.asString
    }

    private fun fail(code: ToneIssueCode, message: String) =
        ParseResult.Failed(listOf(issue(code, message)))

    private fun issue(code: ToneIssueCode, message: String) =
        ToneIssue(code, ToneIssueSeverity.ERROR, message)

    companion object {
        private val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

        fun extractJsonPayload(raw: String): String {
            val trimmed = raw.trim()
            val fenced = fence.findAll(trimmed).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }
            val block = fenced.firstOrNull { it.startsWith("{") } ?: fenced.firstOrNull()
            if (block != null) return block
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
            return trimmed
        }
    }
}
