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

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File
import java.io.InputStream
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
    private val preloadFinished = AtomicBoolean(false)
    @Volatile private var preloadRunning = false

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
     * @return true if extraction completed (or was already up to date)
     */
    fun ensurePluginsExtracted(
        context: Context,
        onProgress: ((extracted: Int, total: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
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

        // Stamp format: "<versionCode>:<count>"
        var needsExtraction = true
        val stampFile = File(pluginDir, VERSION_STAMP_FILE)
        if (stampFile.exists()) {
            val parts = stampFile.readText().trim().split(":")
            val stampedVersion = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val stampedCount = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (stampedVersion == currentVersion && stampedCount > 0 && stampedCount >= assetNames.size) {
                val sample = assetNames.firstOrNull()
                if (sample == null || File(pluginDir, sample).exists()) {
                    Log.d(TAG, "Plugins already extracted for version $currentVersion ($stampedCount files)")
                    needsExtraction = false
                } else {
                    Log.d(TAG, "Stamp OK but files missing on disk, re-extracting")
                }
            } else {
                Log.d(TAG, "Stamp mismatch (v=$stampedVersion/$currentVersion, n=$stampedCount/${assetNames.size}), re-extracting")
            }
        }

        if (needsExtraction) {
            if (!pluginsInBaseAssets(context, assetNames.first())) {
                onStatus?.invoke("Mengunduh plugin")
                awaitFastFollowPacks(context, onStatus)
            }
            onStatus?.invoke("Menyalin plugin")
            Log.d(TAG, "Extracting ${assetNames.size} plugin .so files...")
            val total = assetNames.size
            var extracted = 0

            for (name in assetNames) {
                val dest = File(pluginDir, name)
                try {
                    if (dest.isFile && dest.length() > 0L) {
                        extracted++
                        onProgress?.invoke(extracted, total)
                        continue
                    }
                    openPlugin(context, name).use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output, COPY_BUFFER)
                        }
                    }
                    extracted++
                    onProgress?.invoke(extracted, total)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract $name: ${e.message}")
                }
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
        val packed = findPackedFile(context, name)
            ?: throw java.io.FileNotFoundException(name)
        return packed.inputStream()
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
    private fun awaitFastFollowPacks(context: Context, onStatus: ((String) -> Unit)?) {
        val manager = try {
            AssetPackManagerFactory.getInstance(context)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot request asset packs: ${e.message}")
            return
        }
        try {
            Tasks.await(manager.fetch(PACK_NAMES))
        } catch (e: Exception) {
            Log.w(TAG, "Asset pack fetch failed: ${e.message}")
        }
        val deadline = System.currentTimeMillis() + PACK_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val states = try {
                Tasks.await(manager.getPackStates(PACK_NAMES)).packStates()
            } catch (e: Exception) {
                Log.w(TAG, "Asset pack status failed: ${e.message}")
                return
            }
            val pending = PACK_NAMES.filter { name ->
                val status = states[name]?.status() ?: AssetPackStatus.UNKNOWN
                status != AssetPackStatus.COMPLETED
            }
            if (pending.isEmpty()) {
                Log.d(TAG, "Fast-follow packs ready")
                return
            }
            val failed = pending.filter { name ->
                val status = states[name]?.status()
                status == AssetPackStatus.FAILED || status == AssetPackStatus.CANCELED
            }
            if (failed.isNotEmpty()) {
                Log.e(TAG, "Asset packs not available: $failed")
                return
            }
            val bytes = pending.sumOf { name -> states[name]?.bytesDownloaded() ?: 0L }
            val total = pending.sumOf { name -> states[name]?.totalBytesToDownload() ?: 0L }
            val label = if (total > 0L) {
                "Mengunduh plugin ${bytes * 100 / total}%"
            } else {
                "Mengunduh plugin"
            }
            onStatus?.invoke(label)
            Log.d(TAG, "Waiting for asset packs: $pending ($label)")
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        Log.e(TAG, "Timed out waiting for fast-follow asset packs")
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
