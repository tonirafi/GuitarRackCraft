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

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.varcain.guitarrackcraft.tone.MappedTone
import com.varcain.guitarrackcraft.tone.ToneNudge
import com.varcain.guitarrackcraft.tone.toInterchangeJson

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiToneScreen(
    onNavigateBack: () -> Unit,
    onRackChanged: () -> Unit,
    onSaveRackPreset: (String) -> Unit,
    startMode: String = "",
    startQuery: String = "",
    viewModel: AiToneViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(startMode, startQuery) {
        viewModel.openFromPreset(startMode, startQuery)
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val previewName = state.preview?.name.orEmpty()
    var saveName by remember(previewName) { mutableStateOf(previewName) }
    var serviceUrl by remember(state.generationUrl) { mutableStateOf(state.generationUrl) }
    var serviceModel by remember(state.generationModel) { mutableStateOf(state.generationModel) }
    var serviceKey by remember { mutableStateOf("") }
    var showService by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val title = when (state.step) {
        "search" -> "Cari nada"
        "paste" -> "Tempel dari ChatGPT"
        "review" -> "Cek nada"
        else -> "Tambah preset"
    }

    if (state.askApply) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissApply() },
            title = { Text("Pasang preset ini?") },
            text = { Text("Rack yang sedang terbuka akan diganti, lalu preset disimpan sekali. Kalau gagal, rack lama dikembalikan.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.applyConfirmed(onRackChanged, onSaveRackPreset, saveName, onNavigateBack)
                }) { Text("Pasang") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissApply() }) { Text("Batal") }
            }
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!viewModel.back()) onNavigateBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.loading) CircularProgressIndicator()
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            when (state.step) {
                "search" -> SearchStep(
                    state = state,
                    onQuery = viewModel::setQuery,
                    onSearch = { viewModel.search() },
                    onOpen = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                    onCopyPrompt = {
                        val result = viewModel.preparePrompt()
                        if (result.usable) clipboard.setText(AnnotatedString(result.text))
                        notice = result.notice
                    },
                    onGenerate = { viewModel.generate() },
                    onPaste = { viewModel.goTo("paste") }
                )
                "paste" -> PasteStep(
                    text = state.pasteText,
                    onText = viewModel::setPasteText,
                    enabled = !state.loading,
                    onCheck = { viewModel.importPasted() }
                )
                "review" -> state.preview?.let { preview ->
                    ReviewStep(
                        preview = preview,
                        saveName = saveName.ifBlank { preview.name },
                        onName = { saveName = it },
                        applied = state.applied,
                        nudges = state.nudges,
                        showAdvanced = state.showAdvanced,
                        loading = state.loading,
                        onApply = { viewModel.requestApply() },
                        onNudge = viewModel::nudge,
                        onToggleAdvanced = { viewModel.toggleAdvanced() },
                        onCopyJson = {
                            clipboard.setText(AnnotatedString(preview.document.toInterchangeJson()))
                            notice = "JSON disalin."
                        },
                        onDone = onNavigateBack
                    )
                }
                else -> ChooseStep(
                    pluginCount = state.pluginCount,
                    onSearch = { viewModel.goTo("search") },
                    onPaste = { viewModel.goTo("paste") },
                    showService = showService,
                    onToggleService = { showService = !showService },
                    generationConfigured = state.generationConfigured,
                    serviceUrl = serviceUrl,
                    serviceModel = serviceModel,
                    serviceKey = serviceKey,
                    onServiceUrl = { serviceUrl = it },
                    onServiceModel = { serviceModel = it },
                    onServiceKey = { serviceKey = it },
                    onSaveService = { viewModel.saveServiceConfig(serviceUrl, serviceModel, serviceKey) }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ChooseStep(
    pluginCount: Int,
    onSearch: () -> Unit,
    onPaste: () -> Unit,
    showService: Boolean,
    onToggleService: () -> Unit,
    generationConfigured: Boolean,
    serviceUrl: String,
    serviceModel: String,
    serviceKey: String,
    onServiceUrl: (String) -> Unit,
    onServiceModel: (String) -> Unit,
    onServiceKey: (String) -> Unit,
    onSaveService: () -> Unit
) {
    Text(
        "Pilih satu cara. Hasilnya dicek dulu, lalu disimpan sekali di daftar preset. Plugin yang dipakai: $pluginCount.",
        style = MaterialTheme.typography.bodyMedium
    )
    ChoiceCard(
        title = "Cari lagu atau gaya",
        body = "Ketik judul, artis, atau jenis suara. Salin prompt ke ChatGPT, atau buat nada kalau layanan AI sudah diatur.",
        onClick = onSearch
    )
    ChoiceCard(
        title = "Tempel dari ChatGPT",
        body = "Tempel JSON yang sudah jadi. Nada ditolak kalau plugin atau nilainya tidak cocok.",
        onClick = onPaste
    )
    TextButton(onClick = onToggleService) {
        Text(if (showService) "Sembunyikan pengaturan AI" else "Pengaturan AI")
    }
    if (showService) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (generationConfigured) "Pembuatan nada otomatis aktif."
                    else "Belum diatur. Tempel dari ChatGPT tetap bisa dipakai."
                )
                OutlinedTextField(value = serviceUrl, onValueChange = onServiceUrl, modifier = Modifier.fillMaxWidth(), label = { Text("URL layanan chat") }, singleLine = true)
                OutlinedTextField(value = serviceModel, onValueChange = onServiceModel, modifier = Modifier.fillMaxWidth(), label = { Text("Nama model (opsional)") }, singleLine = true)
                OutlinedTextField(value = serviceKey, onValueChange = onServiceKey, modifier = Modifier.fillMaxWidth(), label = { Text("Kunci API (opsional)") }, singleLine = true)
                Button(onClick = onSaveService) { Text("Simpan pengaturan") }
            }
        }
    }
}

@Composable
private fun ChoiceCard(title: String, body: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SearchStep(
    state: AiToneUiState,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    onCopyPrompt: () -> Unit,
    onGenerate: () -> Unit,
    onPaste: () -> Unit
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Lagu, artis, atau jenis suara") },
        singleLine = true
    )
    Button(onClick = onSearch, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Cari") }
    if (state.hits.isNotEmpty()) {
        Text("Ini halaman referensi, bukan setelan gitar.", style = MaterialTheme.typography.bodySmall)
        state.hits.forEach { hit ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(hit.title, fontWeight = FontWeight.SemiBold)
                    if (hit.snippet.isNotBlank()) Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onOpen(hit.url) }) { Text("Buka sumber") }
                }
            }
        }
    }
    Button(onClick = onCopyPrompt, modifier = Modifier.fillMaxWidth()) { Text("Salin prompt ChatGPT") }
    OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) { Text("Tempel hasilnya") }
    if (state.generationConfigured) {
        OutlinedButton(onClick = onGenerate, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
            Text("Buat nada di sini")
        }
    }
}

@Composable
private fun PasteStep(
    text: String,
    onText: (String) -> Unit,
    enabled: Boolean,
    onCheck: () -> Unit
) {
    Text("Tempel JSON dari ChatGPT. Boleh masih di dalam blok kode.")
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("JSON nada") },
        minLines = 8
    )
    Button(onClick = onCheck, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Periksa") }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(
    preview: MappedTone,
    saveName: String,
    onName: (String) -> Unit,
    applied: Boolean,
    nudges: Set<ToneNudge>,
    showAdvanced: Boolean,
    loading: Boolean,
    onApply: () -> Unit,
    onNudge: (ToneNudge) -> Unit,
    onToggleAdvanced: () -> Unit,
    onCopyJson: () -> Unit,
    onDone: () -> Unit
) {
    OutlinedTextField(
        value = saveName,
        onValueChange = onName,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Nama preset") },
        singleLine = true,
        enabled = !applied
    )
    PreviewCard(preview)
    if (!applied) {
        Button(onClick = onApply, enabled = preview.canApply && !loading, modifier = Modifier.fillMaxWidth()) {
            Text("Pasang dan simpan")
        }
    } else {
        Text("Preset sudah masuk daftar. Buka menu Preset untuk memakainya lagi.")
        if (nudges.isNotEmpty()) {
            Text("Sesuaikan", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                nudges.forEach { nudge ->
                    FilterChip(selected = false, onClick = { onNudge(nudge) }, label = { Text(nudgeLabel(nudge)) })
                }
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Selesai") }
    }
    TextButton(onClick = onToggleAdvanced) {
        Text(if (showAdvanced) "Sembunyikan detail" else "Lihat detail plugin")
    }
    if (showAdvanced) AdvancedCard(preview)
    OutlinedButton(onClick = onCopyJson, modifier = Modifier.fillMaxWidth()) { Text("Salin JSON") }
}

@Composable
private fun PreviewCard(preview: MappedTone) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(preview.name, style = MaterialTheme.typography.titleMedium)
            if (preview.description.isNotBlank()) Text(preview.description)
            if (preview.orderChanged) Text("Urutan efek di rack akan diganti.")
            preview.effects.forEachIndexed { index, effect ->
                val changes = effect.parameters.joinToString(", ") { param ->
                    val from = param.previous?.let { " (dari $it)" }.orEmpty()
                    "${param.name} → ${param.value}$from"
                }
                Text("${index + 1}. ${effect.name}${if (changes.isBlank()) "" else ": $changes"}")
            }
            preview.suggestions.distinctBy { it.plugin.uri }.forEach { suggestion ->
                Text("Pengganti yang terpasang: ${suggestion.plugin.name}")
            }
            preview.issues.forEach { issue ->
                Text(issue.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AdvancedCard(preview: MappedTone) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            preview.effects.forEach { effect ->
                Text(effect.name, fontWeight = FontWeight.SemiBold)
                Text(effect.uri, style = MaterialTheme.typography.bodySmall)
                effect.parameters.forEach { param ->
                    Text("${param.symbol} = ${param.value}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun nudgeLabel(nudge: ToneNudge) = when (nudge) {
    ToneNudge.MORE_DISTORTION -> "Lebih distorsi"
    ToneNudge.CLEANER -> "Lebih bersih"
    ToneNudge.LESS_NOISE -> "Kurangi noise"
    ToneNudge.MORE_BASS -> "Lebih bass"
    ToneNudge.MORE_TREBLE -> "Lebih treble"
    ToneNudge.MORE_AMBIENCE -> "Lebih ambience"
    ToneNudge.RESET -> "Kembali ke nada awal"
}
