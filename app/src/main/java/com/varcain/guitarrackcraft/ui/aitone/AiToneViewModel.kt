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

package com.varcain.guitarrackcraft.ui.aitone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.varcain.guitarrackcraft.engine.RackManager
import com.varcain.guitarrackcraft.tone.AiToneDocument
import com.varcain.guitarrackcraft.tone.ApplyResult
import com.varcain.guitarrackcraft.tone.BeginnerToneAdjuster
import com.varcain.guitarrackcraft.tone.ChatGptPromptBuilder
import com.varcain.guitarrackcraft.tone.HttpToneGenerationProvider
import com.varcain.guitarrackcraft.tone.MappedTone
import com.varcain.guitarrackcraft.tone.NativeRackToneGateway
import com.varcain.guitarrackcraft.tone.ParseResult
import com.varcain.guitarrackcraft.tone.SavedAiTone
import com.varcain.guitarrackcraft.tone.ToneCatalog
import com.varcain.guitarrackcraft.tone.ToneGenerationProvider
import com.varcain.guitarrackcraft.tone.ToneGenerationResult
import com.varcain.guitarrackcraft.tone.ToneNudge
import com.varcain.guitarrackcraft.tone.TonePresetApplier
import com.varcain.guitarrackcraft.tone.TonePresetMapper
import com.varcain.guitarrackcraft.tone.TonePresetParser
import com.varcain.guitarrackcraft.tone.ToneSearchHit
import com.varcain.guitarrackcraft.tone.ToneSearchProvider
import com.varcain.guitarrackcraft.tone.ToneServiceConfig
import com.varcain.guitarrackcraft.tone.ToneServicePreferences
import com.varcain.guitarrackcraft.tone.WikipediaToneSearchProvider
import com.varcain.guitarrackcraft.tone.categoriesFromMetadataJson
import com.varcain.guitarrackcraft.tone.toneCatalogFromInstalled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AiToneUiState(
    val step: String = "choose",
    val source: String = "choose",
    val query: String = "",
    val pasteText: String = "",
    val showPaste: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val hits: List<ToneSearchHit> = emptyList(),
    val preview: MappedTone? = null,
    val applied: Boolean = false,
    val nudges: Set<ToneNudge> = emptySet(),
    val showAdvanced: Boolean = false,
    val saved: List<SavedAiTone> = emptyList(),
    val recent: List<SavedAiTone> = emptyList(),
    val generationConfigured: Boolean = false,
    val generationUrl: String = "",
    val generationModel: String = "",
    val pluginCount: Int = 0,
    val askApply: Boolean = false
)

class AiToneViewModel(app: Application) : AndroidViewModel(app) {

    private val preferences = ToneServicePreferences(app)
    private val parser = TonePresetParser()
    private val mapper = TonePresetMapper()
    private val prompts = ChatGptPromptBuilder()
    private val adjuster = BeginnerToneAdjuster()
    private val searchProvider: ToneSearchProvider = WikipediaToneSearchProvider()
    private var generationProvider: ToneGenerationProvider = generationFrom(preferences.read())
    private val gateway = NativeRackToneGateway()
    private val applier = TonePresetApplier(gateway)

    private var catalog = ToneCatalog(emptyList())
    private var originalPlan: MappedTone? = null

    private val _state = MutableStateFlow(AiToneUiState())
    val state: StateFlow<AiToneUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        catalog = loadCatalog()
        val config = preferences.read()
        generationProvider = generationFrom(config)
        _state.update {
            it.copy(
                generationConfigured = config.configured,
                generationUrl = config.generationUrl,
                generationModel = config.generationModel,
                pluginCount = catalog.plugins.size
            )
        }
    }

    fun openFromPreset(mode: String, query: String) {
        val step = when (mode) {
            "search" -> "search"
            "paste" -> "paste"
            else -> "choose"
        }
        _state.update {
            it.copy(
                step = step,
                source = if (step == "choose") "choose" else step,
                query = query.ifBlank { it.query },
                showPaste = step == "paste"
            )
        }
        if (step == "search" && query.isNotBlank()) search()
    }

    fun goTo(step: String) {
        _state.update {
            val source = if (step == "search" || step == "paste") step else it.source
            it.copy(step = step, source = source, error = null, message = null)
        }
    }

    fun back(): Boolean {
        return when (_state.value.step) {
            "review" -> {
                val source = _state.value.source
                goTo(if (source == "search" || source == "paste") source else "choose")
                true
            }
            "search", "paste" -> {
                goTo("choose")
                true
            }
            else -> false
        }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun setPasteText(value: String) = _state.update { it.copy(pasteText = value) }

    fun showPaste(show: Boolean) = _state.update { it.copy(showPaste = show, error = null) }

    fun toggleAdvanced() = _state.update { it.copy(showAdvanced = !it.showAdvanced) }

    fun clearError() = _state.update { it.copy(error = null, message = null) }

    fun requestApply() {
        val preview = _state.value.preview
        if (preview == null || !preview.canApply) {
            _state.update { it.copy(error = "Nada ini belum bisa dipasang. Perbaiki bagian yang ditolak.") }
            return
        }
        _state.update { it.copy(askApply = true) }
    }

    fun dismissApply() = _state.update { it.copy(askApply = false) }

    fun search() {
        val query = _state.value.query.trim()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, message = null) }
            val result = searchProvider.search(query)
            _state.update {
                it.copy(
                    loading = false,
                    hits = result.hits,
                    message = result.message,
                    error = if (result.hits.isEmpty() && query.isNotEmpty() && result.message.contains("gagal", true)) result.message else null
                )
            }
        }
    }

    fun importPasted() {
        importText(_state.value.pasteText)
    }

    fun importText(raw: String) {
        when (val parsed = parser.parse(raw)) {
            is ParseResult.Failed -> _state.update {
                it.copy(error = parsed.issues.joinToString("\n") { issue -> issue.message }, preview = null, applied = false)
            }
            is ParseResult.Ok -> publish(mapper.map(parsed.document, catalog, safeRack()))
        }
    }

    fun generate() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(error = "Isi lagu, artis, atau jenis suara dulu.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, message = null) }
            val result = generationProvider.generate(query, _state.value.hits, catalog)
            when (result) {
                is ToneGenerationResult.NotConfigured -> _state.update {
                    it.copy(loading = false, message = result.message)
                }
                is ToneGenerationResult.Failed -> _state.update {
                    it.copy(loading = false, error = result.message)
                }
                is ToneGenerationResult.JsonReady -> {
                    _state.update { it.copy(loading = false, pasteText = result.text, showPaste = true) }
                    importText(result.text)
                }
            }
        }
    }

    fun preparePrompt(existing: AiToneDocument? = null): com.varcain.guitarrackcraft.tone.PromptBuildResult {
        return prompts.build(
            _state.value.query,
            catalog,
            existing ?: _state.value.preview?.document,
            safeRack()
        )
    }

    fun applyConfirmed(
        onApplied: () -> Unit,
        onSaveRackPreset: (String) -> Unit,
        presetName: String,
        onFinished: () -> Unit
    ) {
        val plan = _state.value.preview ?: return
        val storedName = presetMenuName(presetName.ifBlank { plan.name })
        viewModelScope.launch {
            _state.update { it.copy(askApply = false, loading = true, error = null) }
            when (val packs = com.varcain.guitarrackcraft.ui.loading.PluginPackGate.ensureForUris(
                getApplication(), plan.effects.map { it.uri }
            )) {
                is com.varcain.guitarrackcraft.ui.loading.PluginPackGate.Result.Cancelled -> {
                    _state.update { it.copy(loading = false, error = "Unduhan paket dibatalkan.") }
                    return@launch
                }
                is com.varcain.guitarrackcraft.ui.loading.PluginPackGate.Result.Failed -> {
                    _state.update { it.copy(loading = false, error = packs.message) }
                    return@launch
                }
                is com.varcain.guitarrackcraft.ui.loading.PluginPackGate.Result.Ready -> Unit
            }
            val result = withContext(Dispatchers.IO) { applier.apply(plan) }
            when (result) {
                is ApplyResult.Applied -> {
                    originalPlan = plan
                    onApplied()
                    onSaveRackPreset(storedName)
                    onFinished()
                }
                is ApplyResult.Rejected -> _state.update {
                    it.copy(loading = false, error = result.issues.joinToString("\n") { issue -> issue.message })
                }
                is ApplyResult.Failed -> _state.update {
                    it.copy(loading = false, error = result.message, applied = false)
                }
            }
        }
    }

    fun nudge(nudge: ToneNudge) {
        val current = _state.value.preview ?: return
        val original = originalPlan ?: current
        val next = adjuster.adjust(current, original, nudge)
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) { applier.applyParameters(next) }
            when (result) {
                is ApplyResult.Applied -> {
                    _state.update {
                        it.copy(loading = false, preview = next, applied = true, message = nudgeLabel(nudge))
                    }
                }
                is ApplyResult.Rejected -> _state.update {
                    it.copy(loading = false, error = "Perubahan ini tidak bisa dipasang.")
                }
                is ApplyResult.Failed -> _state.update {
                    it.copy(loading = false, error = result.message)
                }
            }
        }
    }

    private fun presetMenuName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80)
        return cleaned.ifBlank { "AI Tone" }
    }

    fun saveServiceConfig(url: String, model: String, apiKey: String) {
        val config = ToneServiceConfig(url, apiKey, model)
        preferences.write(config)
        generationProvider = generationFrom(config)
        _state.update {
            it.copy(
                generationConfigured = config.configured,
                generationUrl = config.generationUrl,
                generationModel = config.generationModel,
                message = if (config.configured) {
                    "Layanan AI disimpan di perangkat ini."
                } else {
                    "Layanan AI dikosongkan. Tempel dari ChatGPT tetap bisa dipakai."
                }
            )
        }
    }

    private fun publish(plan: MappedTone) {
        originalPlan = plan
        _state.update {
            it.copy(
                step = "review",
                preview = plan,
                applied = false,
                nudges = emptySet(),
                error = plan.errors.joinToString("\n") { issue -> issue.message }.ifBlank { null },
                message = null
            )
        }
    }

    private fun safeRack() = try {
        gateway.currentRack()
    } catch (_: Exception) {
        emptyList()
    }

    private fun loadCatalog(): ToneCatalog {
        val plugins = try {
            RackManager.getAvailablePlugins()
        } catch (_: Exception) {
            emptyList()
        }
        val metadata = try {
            getApplication<Application>().assets.open("plugin_metadata.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
        return toneCatalogFromInstalled(plugins, categoriesFromMetadataJson(metadata))
    }

    private fun generationFrom(config: ToneServiceConfig): ToneGenerationProvider {
        return if (config.configured) HttpToneGenerationProvider(config) else com.varcain.guitarrackcraft.tone.UnconfiguredToneGenerationProvider()
    }

    private fun nudgeLabel(nudge: ToneNudge) = when (nudge) {
        ToneNudge.MORE_DISTORTION -> "Distorsi ditambah."
        ToneNudge.CLEANER -> "Suara dibuat lebih bersih."
        ToneNudge.LESS_NOISE -> "Noise dikurangi."
        ToneNudge.MORE_BASS -> "Bass ditambah."
        ToneNudge.MORE_TREBLE -> "Treble ditambah."
        ToneNudge.MORE_AMBIENCE -> "Ambience ditambah."
        ToneNudge.RESET -> "Kembali ke nada yang dibuat."
    }
}
