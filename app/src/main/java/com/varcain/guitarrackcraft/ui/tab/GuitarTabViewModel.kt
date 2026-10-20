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

package com.varcain.guitarrackcraft.ui.tab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.varcain.guitarrackcraft.tab.GuitarTab
import com.varcain.guitarrackcraft.tab.GuitarTabParser
import com.varcain.guitarrackcraft.tab.GuitarTabPlayer
import com.varcain.guitarrackcraft.tab.SavedGuitarTabStore
import com.varcain.guitarrackcraft.tab.TabParseResult
import com.varcain.guitarrackcraft.tab.guitarTabPrompt
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GuitarTabUiState(
    val presetName: String = "",
    val part: String = "",
    val pasteText: String = "",
    val tab: GuitarTab? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

class GuitarTabViewModel(app: Application) : AndroidViewModel(app) {
    private val parser = GuitarTabParser()
    private val store = SavedGuitarTabStore(File(app.filesDir, "guitar_tabs"))
    private val _state = MutableStateFlow(GuitarTabUiState())
    val state: StateFlow<GuitarTabUiState> = _state.asStateFlow()

    fun setPreset(name: String) {
        if (name.isBlank() || name == _state.value.presetName) return
        _state.update { it.copy(presetName = name) }
    }

    fun setPart(value: String) = _state.update { it.copy(part = value) }

    fun setPaste(value: String) = _state.update { it.copy(pasteText = value, error = null) }

    fun prompt(): String = guitarTabPrompt(_state.value.presetName, _state.value.part)

    fun check() {
        when (val parsed = parser.parse(_state.value.pasteText)) {
            is TabParseResult.Failed -> _state.update { it.copy(tab = null, error = parsed.message) }
            is TabParseResult.Ok -> _state.update { it.copy(tab = parsed.tab, error = null) }
        }
    }

    fun save() {
        val tab = _state.value.tab ?: return
        store.save(tab, _state.value.presetName)
        _state.update { it.copy(message = "Tab \"${tab.title}\" disimpan.", error = null) }
    }

    fun play(onStarted: (String) -> Unit) {
        val tab = _state.value.tab ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val error = GuitarTabPlayer.play(getApplication(), tab)
            if (error != null) {
                _state.update { it.copy(loading = false, error = error) }
            } else {
                _state.update { it.copy(loading = false) }
                onStarted(tab.title)
            }
        }
    }
}
