/*
 * Copyright (C) 2026 Kamil Lulko <kamil.lulko@gmail.com>
 *
 * This file is part of Guitar RackCraft.
 */

package com.varcain.guitarrackcraft.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuitarTabTest {
    private val parser = GuitarTabParser()

    @Test
    fun promptAsksForTheRealRiffOnly() {
        val prompt = guitarTabPrompt("Smell Like Teen Spirit", "intro")
        assertTrue(prompt.contains("Smell Like Teen Spirit"))
        assertTrue(prompt.contains("Jangan menggubah"))
        assertTrue(prompt.contains("bagian \"intro\""))
        assertTrue(!prompt.contains("Minimal 32"))
        assertTrue(!prompt.contains("verse, dan chorus"))
    }

    @Test
    fun parsesValidTab() {
        val raw = """
            {"format":"guitarrackcraft-tab","version":1,"title":"Riff","bpm":90,
             "notes":[{"string":6,"fret":0,"beat":0,"duration":1},{"string":5,"fret":2,"beat":1,"duration":0.5}]}
        """.trimIndent()
        val parsed = parser.parse(raw) as TabParseResult.Ok
        assertEquals("Riff", parsed.tab.title)
        assertEquals(2, parsed.tab.notes.size)
        assertEquals(2, parsed.tab.notes[1].fret)
    }

    @Test
    fun parsesFencedJson() {
        val raw = """
            Ini tabnya:
            ```json
            {"format":"guitarrackcraft-tab","version":1,"title":"Intro","bpm":100,
             "notes":[{"s":1,"f":3,"beat":0,"len":1}]}
            ```
        """.trimIndent()
        val parsed = parser.parse(raw) as TabParseResult.Ok
        assertEquals(1, parsed.tab.notes.first().string)
        assertEquals(3, parsed.tab.notes.first().fret)
    }

    @Test
    fun rejectsUnknownString() {
        val raw = """
            {"format":"guitarrackcraft-tab","version":1,"title":"X","bpm":90,
             "notes":[{"string":7,"fret":0,"beat":0,"duration":1}]}
        """.trimIndent()
        assertTrue(parser.parse(raw) is TabParseResult.Failed)
    }

    @Test
    fun savedTabRoundTrip() {
        val dir = java.io.File("build/tmp/tab-store").apply { mkdirs() }
        val store = SavedGuitarTabStore(dir)
        val tab = (parser.parse(
            """{"format":"guitarrackcraft-tab","version":1,"title":"Riff","bpm":100,
               "notes":[{"string":6,"fret":0,"beat":0,"duration":1}]}"""
        ) as TabParseResult.Ok).tab
        val saved = store.save(tab, "Metal")
        val loaded = store.load(saved.id)
        assertEquals("Riff", loaded?.title)
        assertEquals("Metal", store.presetName(saved.id))
        store.delete(saved.id)
    }

    @Test
    fun renderIsAudibleAndBounded() {
        val tab = (parser.parse(
            """{"format":"guitarrackcraft-tab","version":1,"title":"E","bpm":120,
               "notes":[{"string":6,"fret":0,"beat":0,"duration":1}]}"""
        ) as TabParseResult.Ok).tab
        val samples = GuitarTabSynth.render(tab, 8000)
        assertTrue(samples.size < 8000 * 21)
        assertTrue(samples.any { kotlin.math.abs(it) > 0.05f })
    }
}
