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

package com.varcain.guitarrackcraft.tone

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToneTest {

    private val fuzzUri = "http://example.test/plugins/fuzz"
    private val delayUri = "http://guitarix.sourceforge.net/plugins/gx_delay_stereo#_delay_stereo"
    private val catalog = ToneCatalog(
        listOf(
            TonePlugin(
                uri = fuzzUri,
                format = "LV2",
                name = "Test Fuzz",
                category = "Distortion",
                parameters = listOf(
                    ToneParam("drive", "Drive", 0f, 1f, 0.2f, true, false, 0),
                    ToneParam("tone", "Tone", 0f, 10f, 5f, true, false, 1)
                )
            ),
            TonePlugin(
                uri = delayUri,
                format = "LV2",
                name = "GxDelay-Stereo",
                category = "Delay",
                parameters = listOf(
                    ToneParam("level_l", "Level L", -20f, 0f, -6f, true, false, 2),
                    ToneParam("mix", "Mix", 0f, 1f, 0.3f, true, false, 4)
                )
            )
        )
    )
    private val parser = TonePresetParser()
    private val mapper = TonePresetMapper()

    @Test
    fun validJsonImports() {
        val parsed = parser.parse(validJson(drive = 0.65))
        assertTrue(parsed is ParseResult.Ok)
        val plan = mapper.map((parsed as ParseResult.Ok).document, catalog)
        assertTrue(plan.canApply)
        assertEquals(0.65f, plan.effects[0].parameters[0].value)
        assertEquals(-12f, plan.effects[1].parameters[0].value)
    }

    @Test
    fun markdownFenceImports() {
        val raw = """
            Ini nadanya:
            ```json
            ${validJson(drive = 0.4)}
            ```
            Semoga cocok.
        """.trimIndent()
        val parsed = parser.parse(raw)
        assertTrue(parsed is ParseResult.Ok)
        assertEquals("Rock latihan", (parsed as ParseResult.Ok).document.name)
    }

    @Test
    fun invalidJsonFails() {
        val parsed = parser.parse("{ ini bukan json")
        assertTrue(parsed is ParseResult.Failed)
        assertEquals(ToneIssueCode.MALFORMED_JSON, (parsed as ParseResult.Failed).issues.first().code)
    }

    @Test
    fun unknownPluginFails() {
        val json = validJson(drive = 0.5).replace(fuzzUri, "FuzzFace")
        val plan = map(json)
        assertFalse(plan.canApply)
        assertTrue(plan.issues.any { it.code == ToneIssueCode.UNKNOWN_PLUGIN })
    }

    @Test
    fun missingPluginFailsAndSuggests() {
        val json = validJson(drive = 0.5).replace(fuzzUri, "http://example.test/plugins/not-installed-fuzz")
        val plan = map(json)
        assertFalse(plan.canApply)
        assertTrue(plan.issues.any { it.code == ToneIssueCode.MISSING_PLUGIN })
        assertTrue(plan.suggestions.any { it.plugin.name == "Test Fuzz" })
    }

    @Test
    fun unknownParameterFails() {
        val plan = map(validJson(drive = 0.5).replace("\"drive\"", "\"wet\""))
        assertFalse(plan.canApply)
        assertTrue(plan.issues.any { it.code == ToneIssueCode.UNKNOWN_PARAMETER })
    }

    @Test
    fun outOfRangeFailsWithoutClamping() {
        val plan = map(validJson(drive = 4.0))
        assertFalse(plan.canApply)
        assertTrue(plan.issues.any { it.code == ToneIssueCode.OUT_OF_RANGE })
        assertTrue(plan.effects.none { effect -> effect.parameters.any { it.symbol == "drive" } })
    }

    @Test
    fun unsupportedVersionFails() {
        val parsed = parser.parse(validJson(drive = 0.2).replace("\"version\": 1", "\"version\": 9"))
        assertTrue(parsed is ParseResult.Failed)
        assertEquals(ToneIssueCode.UNSUPPORTED_VERSION, (parsed as ParseResult.Failed).issues.first().code)
    }

    @Test
    fun missingFieldAndInvalidNumberFail() {
        val missing = parser.parse(validJson(drive = 0.2).replace("\"name\": \"Rock latihan\",", ""))
        assertTrue(missing is ParseResult.Failed)
        assertTrue((missing as ParseResult.Failed).issues.any { it.code == ToneIssueCode.MISSING_FIELD })

        val badNumber = parser.parse(validJson(drive = 0.2).replace("0.2", "\"keras\""))
        assertTrue(badNumber is ParseResult.Failed)
        assertTrue((badNumber as ParseResult.Failed).issues.any { it.code == ToneIssueCode.INVALID_NUMBER })
    }

    @Test
    fun applyFailureRollsBack() {
        val plan = map(validJson(drive = 0.5))
        val gateway = FakeGateway(failOnAdd = delayUri)
        val result = TonePresetApplier(gateway).apply(plan)
        assertTrue(result is ApplyResult.Failed)
        assertTrue((result as ApplyResult.Failed).restored)
        assertEquals(listOf("old"), gateway.plugins)
        assertTrue(gateway.bypassEnded)
    }

    @Test
    fun applyReplacesRackInOrder() {
        val plan = map(validJson(drive = 0.5))
        val gateway = FakeGateway()
        val result = TonePresetApplier(gateway).apply(plan)
        assertTrue(result is ApplyResult.Applied)
        assertEquals(listOf(fuzzUri, delayUri), gateway.plugins)
        assertEquals(0.5f, gateway.values[fuzzUri to 0])
        assertEquals(-12f, gateway.values[delayUri to 2])
    }

    @Test
    fun saveRestoreAndLegacyDocument() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ai-tone-test-${System.nanoTime()}")
        val repo = FileTonePresetRepository(dir)
        val document = (parser.parse(validJson(drive = 0.3)) as ParseResult.Ok).document
        val saved = repo.save(document)
        val loaded = repo.find(saved.id)
        assertNotNull(loaded)
        assertEquals("Rock latihan", loaded!!.document.name)
        assertEquals(0.3, loaded.document.effects[0].parameters.getValue("drive"), 0.0001)

        val renamed = repo.rename(saved.id, "Rock malam")
        assertEquals("Rock malam", renamed!!.name)
        assertNull(repo.find(saved.id))

        val raw = File(dir, "saved/legacy.json")
        raw.parentFile?.mkdirs()
        raw.writeText(document.copy(name = "Nada lama").toInterchangeJson())
        val legacy = repo.find("legacy.json")
        assertEquals("Nada lama", legacy!!.name)

        val nativePreset = File(dir, "saved/native.json")
        nativePreset.writeText("""{"version":1,"plugins":[{"uri":"http://x","format":"LV2","controlPorts":[]}]}""")
        assertNull(repo.find("native.json"))

        assertTrue(repo.delete(renamed.id))
        assertTrue(repo.listSaved().none { it.name == "Rock malam" })
        dir.deleteRecursively()
    }

    @Test
    fun promptUsesVerifiedParametersOnly() {
        val prompt = ChatGptPromptBuilder().build("lagu rock", catalog)
        assertTrue(prompt.usable)
        assertTrue(prompt.text.contains(fuzzUri))
        assertTrue(prompt.text.contains(delayUri))
        assertTrue(prompt.text.contains("Pakai semua plugin ini"))
        assertTrue(prompt.text.contains("drive"))
        assertTrue(prompt.text.contains("[0,1]"))
        assertTrue(prompt.text.contains("continuous"))
        assertFalse(prompt.text.contains("http://invented.example/plugin"))
    }

    @Test
    fun promptRefusesCatalogWithoutVerifiedParameters() {
        val mystery = TonePlugin(
            uri = "http://example.test/nam",
            format = "LV2",
            name = "Neural Amp Modeler",
            category = "Simulator",
            parameters = emptyList()
        )
        val result = ChatGptPromptBuilder().build("clean amp", ToneCatalog(listOf(mystery)))
        assertFalse(result.usable)
        assertTrue(result.notice.contains("terverifikasi"))
        assertTrue(result.text.isEmpty())
    }

    @Test
    fun promptStaysWithinSizeLimit() {
        val longName = "parameter".repeat(12)
        val categories = listOf("Dynamics", "Distortion", "Amplifier", "EQ", "Modulator", "Delay", "Reverb")
        val plugins = categories.mapIndexed { index, category ->
            TonePlugin(
                uri = "http://example.test/plugin-$index",
                format = "LV2",
                name = "Plugin $category",
                category = category,
                parameters = (0 until 16).map { param ->
                    ToneParam("p$param$longName", longName, 0f, 10f, 1f, true, false, param)
                }
            )
        }
        val result = ChatGptPromptBuilder().build(
            "compressor distortion amp eq chorus delay reverb rock",
            ToneCatalog(plugins)
        )
        assertTrue(result.usable)
        assertTrue(result.text.length <= ChatGptPromptBuilder.MAX_CHARS)
        assertTrue(result.text.contains("http://example.test/plugin-"))
    }

    @Test
    fun integerAndEnumerationValuesAreChecked() {
        val uri = "http://example.test/amp"
        val local = ToneCatalog(listOf(
            TonePlugin(
                uri = uri,
                format = "LV2",
                name = "Amp",
                category = "Amplifier",
                parameters = listOf(
                    ToneParam(
                        "model", "Model", 0f, 2f, 0f, true, false, 0,
                        ToneValueKind.ENUMERATION, "",
                        listOf(ToneScalePoint("a", 0f), ToneScalePoint("b", 1f), ToneScalePoint("c", 2f))
                    ),
                    ToneParam("cab", "Cab", 1f, 8f, 1f, true, false, 1, ToneValueKind.INTEGER)
                )
            )
        ))
        val badEnum = mapOn(ampJson(uri, """"model": 1.5, "cab": 2"""), local)
        assertTrue(badEnum.issues.any { it.code == ToneIssueCode.INVALID_ENUM })
        val badInt = mapOn(ampJson(uri, """"model": 1, "cab": 2.5"""), local)
        assertTrue(badInt.issues.any { it.code == ToneIssueCode.INVALID_NUMBER })
        val ok = mapOn(ampJson(uri, """"model": 1, "cab": 3"""), local)
        assertTrue(ok.canApply)
    }

    @Test
    fun beginnerControlsFollowRealParameters() {
        val plan = map(validJson(drive = 0.5))
        val adjuster = BeginnerToneAdjuster()
        val available = adjuster.available(plan)
        assertTrue(ToneNudge.MORE_DISTORTION in available)
        assertTrue(ToneNudge.MORE_AMBIENCE in available)
        assertFalse(ToneNudge.MORE_BASS in available)
        val louder = adjuster.adjust(plan, plan, ToneNudge.MORE_DISTORTION)
        val drive = louder.effects[0].parameters.first { it.symbol == "drive" }.value
        assertTrue(drive > 0.5f)
        assertTrue(drive <= 1f)
        val reset = adjuster.adjust(louder, plan, ToneNudge.RESET)
        assertEquals(0.5f, reset.effects[0].parameters.first { it.symbol == "drive" }.value)
    }

    @Test
    fun importedTextIsNotExecuted() {
        val malicious = validJson(drive = 0.2).replace(
            "\"description\": \"Perkiraan nada rock.\"",
            "\"description\": \"Runtime.getRuntime().exec(\\\"id\\\")\""
        )
        val parsed = parser.parse(malicious) as ParseResult.Ok
        assertTrue(parsed.document.description.contains("exec"))
        assertEquals(2, parsed.document.effects.size)
    }

    private fun map(json: String): MappedTone = mapOn(json, catalog)

    private fun mapOn(json: String, local: ToneCatalog): MappedTone {
        val parsed = parser.parse(json)
        check(parsed is ParseResult.Ok) { parsed }
        return mapper.map(parsed.document, local)
    }

    private fun ampJson(uri: String, parameters: String) = """
        {
          "format": "guitarrackcraft-ai-tone",
          "version": 1,
          "name": "Amp",
          "description": "Amp",
          "effects": [
            {"pluginUri": "$uri", "enabled": true, "parameters": {$parameters}}
          ]
        }
    """.trimIndent()

    private fun validJson(drive: Double) = """
        {
          "format": "guitarrackcraft-ai-tone",
          "version": 1,
          "name": "Rock latihan",
          "reference": {"song": "Contoh", "artist": "Contoh"},
          "description": "Perkiraan nada rock.",
          "effects": [
            {"pluginUri": "$fuzzUri", "enabled": true, "parameters": {"drive": $drive}},
            {"pluginUri": "$delayUri", "enabled": true, "parameters": {"level_l": -12}}
          ]
        }
    """.trimIndent()

    private class FakeGateway(private val failOnAdd: String? = null) : RackToneGateway {
        val plugins = mutableListOf("old")
        val values = mutableMapOf<Pair<String, Int>, Float>()
        var bypassEnded = false
        private var bypass = false

        override fun snapshot(): String = "snapshot"

        override fun restore(json: String): Boolean {
            plugins.clear()
            plugins += "old"
            values.clear()
            return json == "snapshot"
        }

        override fun setBypass(bypass: Boolean) {
            this.bypass = bypass
            if (!bypass) bypassEnded = true
        }

        override fun clear() {
            plugins.clear()
        }

        override fun add(fullId: String): Int {
            val uri = fullId.substringAfter(':')
            if (uri == failOnAdd) return -1
            plugins += uri
            return plugins.lastIndex
        }

        override fun setParameter(pluginIndex: Int, portIndex: Int, value: Float) {
            values[plugins[pluginIndex] to portIndex] = value
        }

        override fun currentRack(): List<RackSlot> = emptyList()
    }
}
