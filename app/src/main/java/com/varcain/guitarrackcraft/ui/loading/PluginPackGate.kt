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

package com.varcain.guitarrackcraft.ui.loading

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.varcain.guitarrackcraft.BuildConfig
import com.varcain.guitarrackcraft.engine.PluginAssetExtractor
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Asks before an on-demand plugin pack is fetched. The Play Store install stays
 * small; the large pack is downloaded only after the user confirms here, and
 * Play shows its own confirmation for that download.
 */
object PluginPackGate {

    data class Prompt(
        val title: String,
        val body: String,
        val busy: Boolean,
        val status: String
    )

    var prompt by mutableStateOf<Prompt?>(null)
        private set

    private var decision: CancellableContinuation<Boolean>? = null

    sealed class Result {
        data object Ready : Result()
        data object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    fun confirm() {
        prompt = prompt?.copy(busy = true, status = "Menyiapkan unduhan")
        val waiting = decision ?: return
        decision = null
        if (waiting.isActive) waiting.resume(true)
    }

    fun dismiss() {
        val waiting = decision ?: return
        decision = null
        prompt = null
        if (waiting.isActive) waiting.resume(false)
    }

    suspend fun ensureForUris(context: Context, uris: Iterable<String>): Result {
        if (!BuildConfig.USE_ASSET_PACKS) return Result.Ready
        val appContext = context.applicationContext
        val packs = uris.mapNotNull { PluginAssetExtractor.packForUri(appContext, it) }
            .distinct()
            .filter { !PluginAssetExtractor.isPackReady(appContext, it) }
        if (packs.isEmpty()) return Result.Ready
        for (pack in packs) {
            val label = PluginAssetExtractor.packLabel(pack)
            val bytes = PluginAssetExtractor.packSizeBytes(appContext, pack)
            val size = if (bytes > 0L) "sekitar ${bytes / (1024 * 1024)} MB" else "berkas besar"
            val accepted = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    decision = cont
                    prompt = Prompt(
                        title = "Unduh paket $label",
                        body = "Efek ini ada di paket $label, $size. Paket ini tidak ikut " +
                            "unduhan aplikasi di Play Store. Play menampilkan konfirmasi " +
                            "sebelum berkas besar diunduh.",
                        busy = false,
                        status = ""
                    )
                    cont.invokeOnCancellation {
                        if (decision === cont) decision = null
                        prompt = null
                    }
                }
            }
            if (!accepted) return Result.Cancelled
            var failure = "Unduhan paket $label gagal."
            val ok = withContext(Dispatchers.IO) {
                PluginAssetExtractor.downloadPack(appContext, pack) { status ->
                    failure = status
                    Handler(Looper.getMainLooper()).post {
                        prompt = prompt?.copy(busy = true, status = status)
                    }
                }
            }
            withContext(Dispatchers.Main) { prompt = null }
            if (!ok) return Result.Failed(failure)
        }
        return Result.Ready
    }

    fun urisInPreset(json: String): List<String> {
        val plugins = JSONObject(json).optJSONArray("plugins") ?: return emptyList()
        return buildList {
            for (i in 0 until plugins.length()) {
                val uri = plugins.optJSONObject(i)?.optString("uri").orEmpty()
                if (uri.isNotEmpty()) add(uri)
            }
        }
    }
}

@Composable
fun PluginPackDialog() {
    val current = PluginPackGate.prompt ?: return
    AlertDialog(
        onDismissRequest = { if (!current.busy) PluginPackGate.dismiss() },
        title = { Text(current.title) },
        text = {
            Column {
                Text(current.body)
                if (current.busy) {
                    Spacer(modifier = Modifier.height(16.dp))
                    if (current.status.isNotEmpty()) {
                        Text(current.status)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (!current.busy) {
                TextButton(onClick = { PluginPackGate.confirm() }) {
                    Text("Unduh")
                }
            }
        },
        dismissButton = {
            if (!current.busy) {
                TextButton(onClick = { PluginPackGate.dismiss() }) {
                    Text("Nanti")
                }
            }
        }
    )
}
