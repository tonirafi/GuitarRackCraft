/*
 * Copyright (C) 2026 Kamil Lulko <kamil.lulko@gmail.com>
 *
 * This file is part of Guitar RackCraft.
 */

package com.varcain.guitarrackcraft.tab

import android.content.Context
import com.varcain.guitarrackcraft.engine.AudioEngine
import com.varcain.guitarrackcraft.engine.AudioSettingsManager
import com.varcain.guitarrackcraft.engine.WavPlayer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GuitarTabPlayer {
    suspend fun play(context: Context, tab: GuitarTab): String? {
        val file = try {
            withContext(Dispatchers.Default) {
                val samples = GuitarTabSynth.render(tab)
                val out = File(context.cacheDir, "guitar-tab.wav")
                writePcm16Wav(out, samples, 48000)
                out
            }
        } catch (e: Exception) {
            return "Tab gagal dibuat: ${e.message}"
        }
        if (!AudioEngine.isRunning()) {
            val started = AudioEngine.start(
                inputDeviceId = AudioSettingsManager.getInputDeviceId(context),
                outputDeviceId = AudioSettingsManager.getOutputDeviceId(context),
                bufferFrames = AudioSettingsManager.getBufferSize(context)
            )
            if (!started) return "Mesin audio belum bisa dinyalakan."
        }
        val loaded = withContext(Dispatchers.IO) { WavPlayer.load(file) }
        if (!loaded) return "Tab tidak bisa diputar."
        WavPlayer.play()
        return null
    }
}
