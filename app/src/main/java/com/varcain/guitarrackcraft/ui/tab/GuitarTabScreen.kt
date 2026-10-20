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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.varcain.guitarrackcraft.tab.tabAscii

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuitarTabScreen(
    presetName: String,
    onNavigateBack: () -> Unit,
    onPlayed: (String) -> Unit,
    viewModel: GuitarTabViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(presetName) { viewModel.setPreset(presetName) }
    val clipboard = LocalClipboardManager.current
    var notice by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tab gitar") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (state.presetName.isBlank()) "Pilih preset dulu. Prompt tab dibuat per preset."
                else "Prompt ini khusus preset \"${state.presetName}\". Petikan nanti lewat efek preset itu."
            )
            OutlinedTextField(
                value = state.part,
                onValueChange = viewModel::setPart,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bagian, misalnya intro (opsional)") },
                singleLine = true,
                enabled = state.presetName.isNotBlank()
            )
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(viewModel.prompt()))
                    notice = "Prompt untuk \"${state.presetName}\" disalin."
                },
                enabled = state.presetName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Salin prompt ChatGPT") }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            OutlinedTextField(
                value = state.pasteText,
                onValueChange = viewModel::setPaste,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tempel JSON tab") },
                minLines = 6
            )
            OutlinedButton(
                onClick = viewModel::check,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Periksa") }
            if (state.loading) CircularProgressIndicator()
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.tab?.let { tab ->
                Text(tab.title, style = MaterialTheme.typography.titleMedium)
                val beats = tab.notes.maxOf { it.beat + it.duration }
                Text(
                    "${tab.bpm} BPM · ${tab.notes.size} nada · ${beats.toInt()} ketukan",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Gambar di bawah hanya awal tab.", style = MaterialTheme.typography.bodySmall)
                Text(tabAscii(tab), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { viewModel.save() },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Simpan tab") }
                Button(
                    onClick = { viewModel.play(onPlayed) },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Mainkan dengan efek") }
            }
        }
    }
}
