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

package com.varcain.guitarrackcraft.engine

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extracts plugin .so files from Play Asset Delivery fast-follow packs to a
 * dlopen-able directory on first launch (or after app update).
 *
 * Fast-follow packs are not split APKs (install-time packs are, and Play then
 * reads their missing targetSdk as 1). After the pack download finishes, files
 * are read from the pack location. A copy that is already in the base
 * AssetManager is used first. Plugin .so files live under "plugins/arm64-v8a/".
 *
 * A build-time manifest (plugin_libs.txt) lists all plugin .so filenames so
 * extraction does not depend on assets.list() (which is unreliable across
 * split APKs on some Android versions).
 *
 * For the "full" flavor (GitHub APK), all plugin .so files are in jniLibs
 * and this class is never called.
 */
object PluginAssetExtractor {

    private const val TAG = "PluginAssetExtractor"
    private const val PLUGINS_ASSET_DIR = "plugins/arm64-v8a"
    private const val MANIFEST_FILE = "plugin_libs.txt"
    private const val VERSION_STAMP_FILE = ".version_code"
    private val PACK_NAMES = listOf("gxplugins_pack", "neural_pack", "brummer_pack")
    private const val PACK_WAIT_MS = 15 * 60 * 1000L
    private const val COPY_BUFFER = 1024 * 1024
    private val preloadLock = Object()
    private val extractLock = Object()
    private val preloadFinished = AtomicBoolean(false)
    @Volatile private var preloadRunning = false
    private var hostActivity: WeakReference<Activity>? = null
    private var cellularLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    /** Activity used to show Play's download confirmation dialog. */
    fun attach(
        activity: Activity,
        cellularConfirmation: ActivityResultLauncher<IntentSenderRequest>
    ) {
        hostActivity = WeakReference(activity)
        cellularLauncher = cellularConfirmation
    }

    /**
     * Directory where extracted plugin .so files are stored.
     * This path is passed to native code as pluginLibDir.
     */
    fun getPluginDir(context: Context): File =
        File(context.filesDir, "plugins/arm64-v8a")

    /**
     * Read plugin .so filenames from the build-time manifest (plugin_libs.txt).
     * Falls back to assets.list() if manifest is missing.
     */
    private fun getPluginFileNames(context: Context): List<String> {
        // Primary: read build-time manifest
        try {
            val names = context.assets.open(MANIFEST_FILE).bufferedReader().use { reader ->
                reader.readLines().map { it.trim() }.filter { it.endsWith(".so") }
            }
            if (names.isNotEmpty()) {
                Log.d(TAG, "Read ${names.size} entries from $MANIFEST_FILE")
                return names
            }
        } catch (e: Exception) {
            Log.w(TAG, "$MANIFEST_FILE not found, falling back to assets.list(): ${e.message}")
        }

        // Fallback: enumerate via assets.list()
        return try {
            val listed = context.assets.list(PLUGINS_ASSET_DIR)
                ?.filter { it.endsWith(".so") } ?: emptyList()
            Log.d(TAG, "assets.list($PLUGINS_ASSET_DIR) returned ${listed.size} entries")
            listed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list plugin assets: ${e.message}")
            emptyList()
        }
    }

    /**
     * Ensure plugins are extracted. Skips the copy when this version is already
     * on disk. DSP System.load runs in the background so the first screen can open.
     * @param onProgress optional callback: (extracted, total) counts
     * @return true if every plugin file is on disk
     */
    fun ensurePluginsExtracted(
        context: Context,
        onProgress: ((extracted: Int, total: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): Boolean = synchronized(extractLock) {
        extractPlugins(context, force = false, onProgress, onStatus)
    }

    /**
     * Deletes the local copies, asks Play to fetch the packs again, then copies
     * them once more. Already-loaded libraries stay mapped until the process restarts.
     */
    fun redownloadPlugins(
        context: Context,
        onProgress: ((extracted: Int, total: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): Boolean = synchronized(extractLock) {
        awaitDspPreload()
        onStatus?.invoke("Menghapus plugin lama")
        val pluginDir = getPluginDir(context)
        pluginDir.listFiles()?.forEach { file ->
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        val manager = packManager(context)
        if (manager != null) {
            for (pack in PACK_NAMES) {
                try {
                    Tasks.await(manager.removePack(pack))
                } catch (e: Exception) {
                    Log.w(TAG, "removePack $pack: ${e.message}")
                }
            }
        }
        extractPlugins(context, force = true, onProgress, onStatus)
    }

    private fun extractPlugins(
        context: Context,
        force: Boolean,
        onProgress: ((extracted: Int, total: Int) -> Unit)?,
        onStatus: ((String) -> Unit)?
    ): Boolean {
        val pluginDir = getPluginDir(context)
        pluginDir.mkdirs()

        val currentVersion = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .longVersionCode
        } catch (_: Exception) {
            0L
        }

        val assetNames = getPluginFileNames(context)

        if (assetNames.isEmpty()) {
            Log.w(TAG, "No plugin .so files found (no manifest and assets.list empty)")
            return true
        }

        val stampFile = File(pluginDir, VERSION_STAMP_FILE)
        var needsExtraction = force || !stampFile.isFile
        if (!needsExtraction) {
            val parts = stampFile.readText().trim().split(":")
            val stampedVersion = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val stampedCount = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (stampedVersion == currentVersion && stampedCount >= assetNames.size) {
                val sample = assetNames.firstOrNull()
                if (sample == null || File(pluginDir, sample).isFile) {
                    Log.d(TAG, "Plugins already extracted for version $currentVersion ($stampedCount files)")
                } else {
                    Log.d(TAG, "Stamp OK but files missing on disk, re-extracting")
                    needsExtraction = true
                }
            } else {
                Log.d(TAG, "Stamp mismatch (v=$stampedVersion/$currentVersion, n=$stampedCount/${assetNames.size}), re-extracting")
                needsExtraction = true
            }
        }

        if (needsExtraction) {
            if (!pluginsInBaseAssets(context, assetNames.first())) {
                onStatus?.invoke("Mengunduh plugin")
                val packsReady = awaitFastFollowPacks(context, onStatus)
                if (!packsReady) {
                    onStatus?.invoke("Unduhan plugin gagal. Periksa koneksi, lalu coba lagi.")
                    return false
                }
            }
            onStatus?.invoke("Menyalin plugin")
            Log.d(TAG, "Extracting ${assetNames.size} plugin .so files...")
            val total = assetNames.size
            var extracted = 0

            for (name in assetNames) {
                val dest = File(pluginDir, name)
                try {
                    val expected = packedSize(context, name)
                    if (!force && dest.isFile && dest.length() > 0L &&
                        (expected <= 0L || dest.length() == expected)
                    ) {
                        extracted++
                        onProgress?.invoke(extracted, total)
                        continue
                    }
                    if (dest.exists()) dest.delete()
                    openPlugin(context, name).use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output, COPY_BUFFER)
                        }
                    }
                    if (expected > 0L && dest.length() != expected) {
                        dest.delete()
                        Log.e(TAG, "Size mismatch for $name: ${dest.length()} != $expected")
                        continue
                    }
                    if (!dest.isFile || dest.length() <= 0L) continue
                    extracted++
                    onProgress?.invoke(extracted, total)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract $name: ${e.message}")
                }
            }

            if (extracted != total) {
                stampFile.delete()
                Log.e(TAG, "Extraction incomplete: $extracted/$total")
                onStatus?.invoke("Plugin belum lengkap ($extracted/$total). Coba lagi.")
                return false
            }
            stampFile.writeText("$currentVersion:$extracted")
            Log.d(TAG, "Extraction complete: $extracted/$total plugins")
        }

        // DSP libraries are loaded after the first screen is up. Native
        // dlopen of a filesDir .so still needs System.load first, so the
        // rack waits on awaitDspPreload() before the first plugin is added.
        startDspPreload(pluginDir, assetNames)
        return true
    }

    /** Blocks until the background DSP preload finishes, if one was started. */
    fun awaitDspPreload() {
        synchronized(preloadLock) {
            while (preloadRunning && !preloadFinished.get()) {
                try {
                    preloadLock.wait(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun startDspPreload(pluginDir: File, assetNames: List<String>) {
        synchronized(preloadLock) {
            if (preloadRunning || preloadFinished.get()) return
            preloadRunning = true
            Thread {
                try {
                    preloadDspPlugins(pluginDir, assetNames)
                } finally {
                    synchronized(preloadLock) {
                        preloadFinished.set(true)
                        preloadRunning = false
                        preloadLock.notifyAll()
                    }
                }
            }.apply {
                name = "nadarak-dsp-preload"
                isDaemon = true
                start()
            }
        }
    }

    private fun pluginsInBaseAssets(context: Context, sample: String): Boolean =
        try {
            context.assets.open("$PLUGINS_ASSET_DIR/$sample").close()
            true
        } catch (_: Exception) {
            false
        }

    private fun openPlugin(context: Context, name: String): InputStream {
        try {
            return context.assets.open("$PLUGINS_ASSET_DIR/$name")
        } catch (_: Exception) {
        }
        val located = locateAsset(context, name)
        if (located != null) {
            val raw = FileInputStream(located.file)
            skipFully(raw, located.offset)
            return if (located.size > 0L) BoundedInputStream(raw, located.size) else raw
        }
        val packed = findPackedFile(context, name)
            ?: throw java.io.FileNotFoundException(name)
        return packed.inputStream()
    }

    private fun packedSize(context: Context, name: String): Long {
        val located = locateAsset(context, name)
        if (located != null && located.size > 0L) return located.size
        return findPackedFile(context, name)?.length() ?: -1L
    }

    private data class LocatedAsset(val file: File, val offset: Long, val size: Long)

    private fun locateAsset(context: Context, name: String): LocatedAsset? {
        val manager = packManager(context) ?: return null
        val assetPath = "$PLUGINS_ASSET_DIR/$name"
        for (pack in PACK_NAMES) {
            val location = try {
                manager.getAssetLocation(pack, assetPath)
            } catch (_: Exception) {
                null
            } ?: continue
            val path = location.path() ?: continue
            val file = File(path)
            if (!file.isFile) continue
            return LocatedAsset(file, location.offset().coerceAtLeast(0L), location.size())
        }
        return null
    }

    private fun packManager(context: Context): AssetPackManager? =
        try {
            AssetPackManagerFactory.getInstance(context)
        } catch (e: Exception) {
            Log.w(TAG, "AssetPackManager unavailable: ${e.message}")
            null
        }

    private fun findPackedFile(context: Context, name: String): File? {
        val manager = try {
            AssetPackManagerFactory.getInstance(context)
        } catch (e: Exception) {
            Log.w(TAG, "AssetPackManager unavailable: ${e.message}")
            return null
        }
        for (pack in PACK_NAMES) {
            val location = manager.getPackLocation(pack) ?: continue
            val candidates = listOfNotNull(
                location.assetsPath()?.let { File(it, "$PLUGINS_ASSET_DIR/$name") },
                location.path()?.let { File(it, "assets/$PLUGINS_ASSET_DIR/$name") },
                location.path()?.let { File(it, "$PLUGINS_ASSET_DIR/$name") },
            )
            candidates.firstOrNull { it.isFile }?.let { return it }
        }
        return null
    }

    /** Blocks until every fast-follow pack is on disk, or the wait expires. */
    private fun awaitFastFollowPacks(context: Context, onStatus: ((String) -> Unit)?): Boolean {
        val manager = packManager(context) ?: return false
        fun requestFetch() {
            try {
                Tasks.await(manager.fetch(PACK_NAMES))
            } catch (e: Exception) {
                Log.w(TAG, "Asset pack fetch failed: ${e.message}")
            }
        }
        requestFetch()
        var retries = 0
        var confirmationAt = 0L
        var cellularAt = 0L
        val deadline = System.currentTimeMillis() + PACK_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val states = try {
                Tasks.await(manager.getPackStates(PACK_NAMES)).packStates()
            } catch (e: Exception) {
                Log.w(TAG, "Asset pack status failed: ${e.message}")
                if (!sleepBriefly()) return false
                continue
            }
            val pending = PACK_NAMES.filter { name ->
                val status = states[name]?.status() ?: AssetPackStatus.UNKNOWN
                status != AssetPackStatus.COMPLETED
            }
            if (pending.isEmpty()) {
                Log.d(TAG, "Fast-follow packs ready")
                return true
            }
            val failed = pending.filter { name ->
                val status = states[name]?.status()
                status == AssetPackStatus.FAILED || status == AssetPackStatus.CANCELED
            }
            if (failed.isNotEmpty()) {
                val detail = failed.joinToString { name ->
                    val state = states[name]
                    "$name:${state?.status()}/${state?.errorCode()}"
                }
                Log.e(TAG, "Asset packs not available: $detail")
                if (retries < 1) {
                    retries++
                    onStatus?.invoke("Mengulang unduhan")
                    requestFetch()
                    if (!sleepBriefly(2000)) return false
                    continue
                }
                return false
            }
            val now = System.currentTimeMillis()
            val needsConfirm = pending.any { name ->
                states[name]?.status() == AssetPackStatus.REQUIRES_USER_CONFIRMATION
            }
            val needsWifi = pending.any { name ->
                states[name]?.status() == AssetPackStatus.WAITING_FOR_WIFI
            }
            if (needsConfirm && now - confirmationAt > 20_000L) {
                confirmationAt = now
                onStatus?.invoke("Konfirmasi unduhan di Play Store")
                showPlayDialog { activity ->
                    manager.showConfirmationDialog(activity)
                }
            } else if (needsWifi && now - cellularAt > 20_000L) {
                cellularAt = now
                onStatus?.invoke("Izinkan unduhan lewat data seluler, atau sambungkan Wi-Fi")
                showCellularConfirmation(manager)
            } else if (!needsConfirm && !needsWifi) {
                val bytes = pending.sumOf { name -> states[name]?.bytesDownloaded() ?: 0L }
                val total = pending.sumOf { name -> states[name]?.totalBytesToDownload() ?: 0L }
                val label = if (total > 0L) {
                    "Mengunduh plugin ${bytes * 100 / total}%"
                } else {
                    "Mengunduh plugin"
                }
                onStatus?.invoke(label)
                Log.d(TAG, "Waiting for asset packs: $pending ($label)")
            }
            if (!sleepBriefly()) return false
        }
        Log.e(TAG, "Timed out waiting for fast-follow asset packs")
        return false
    }

    private fun showPlayDialog(show: (Activity) -> Task<Int?>?) {
        val activity = hostActivity?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread {
            try {
                show(activity)?.addOnFailureListener { error ->
                    Log.w(TAG, "Play dialog: ${error.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Play dialog: ${e.message}")
            }
        }
    }

    private fun showCellularConfirmation(manager: AssetPackManager) {
        val activity = hostActivity?.get() ?: return
        val launcher = cellularLauncher ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread {
            try {
                manager.showCellularDataConfirmation(launcher)
            } catch (e: Exception) {
                Log.w(TAG, "Cellular dialog: ${e.message}")
            }
        }
    }

    private fun sleepBriefly(millis: Long = 1000): Boolean =
        try {
            Thread.sleep(millis)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (input.read() < 0) return
            left--
        }
    }

    private class BoundedInputStream(
        private val source: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = source.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val count = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count > 0) remaining -= count
            return count
        }

        override fun close() {
            source.close()
        }
    }

    private fun preloadDspPlugins(pluginDir: File, assetNames: List<String>) {
        var preloaded = 0
        for (name in assetNames) {
            if (name.contains("_ui.so")) continue
            val dest = File(pluginDir, name)
            if (dest.exists() && dest.length() > 0) {
                try {
                    System.load(dest.absolutePath)
                    preloaded++
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "Pre-load skip $name: ${e.message}")
                }
            }
        }
        Log.i(TAG, "Pre-loaded $preloaded DSP plugins via System.load()")
    }
}
