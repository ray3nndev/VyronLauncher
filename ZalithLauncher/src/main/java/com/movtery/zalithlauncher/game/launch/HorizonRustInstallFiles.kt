/*
 * Vyron Launcher
 * Copyright (C) 2026 VyronLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package com.movtery.zalithlauncher.game.launch

import android.content.Context
import com.ray3nndev.vyron.nativebridge.HorizonBridge

import com.movtery.zalithlauncher.coroutine.Task
import com.movtery.zalithlauncher.game.download.engine.DownloadEngine
import com.movtery.zalithlauncher.game.download.engine.DownloadRequest
import com.movtery.zalithlauncher.game.path.getVersionsHome
import com.movtery.zalithlauncher.game.version.download.BaseMinecraftDownloader
import com.movtery.zalithlauncher.game.addons.mirror.mapBMCLMirrorUrls
import com.movtery.zalithlauncher.utils.file.compareSHA1
import kotlinx.coroutines.Dispatchers
import java.io.File
import org.json.JSONObject

/**
 * File acquisition for the native Horizon 26.3 profile.
 *
 * The vanilla client, libraries and assets remain the responsibility of
 * Zalith's existing MinecraftDownloader. Horizon additionally consumes the
 * matching Mojang server JAR while preparing its pinned game resources.
 *
 * This task deliberately does not mark a profile as installed: that is only
 * valid after Horizon's resource preparation and integrity check have passed.
 */
object HorizonRustInstallFiles {
    const val MINECRAFT_VERSION = "26.3"
    const val PROFILE_NAME = "26.3-rust"
    const val SERVER_JAR_NAME = "server-26.3.jar"


    /**
     * Verify prepared resources with Horizon's official checker, not a Kotlin approximation.
     * The lock file is copied from this APK's pinned engine revision into app-private storage.
     */
    fun verifyPreparedResources(context: Context, resourcesDir: File) {
        val engineRoot = File(context.filesDir, "horizon/engine-check").apply {
            check(isDirectory || mkdirs()) { "Could not create Horizon verification directory." }
        }
        val lockFile = File(engineRoot, "research-lock.json")
        context.assets.open("horizon/research-lock.json").use { input ->
            lockFile.outputStream().use { output -> input.copyTo(output) }
        }

        val result = runCatching {
            JSONObject(HorizonBridge.nativeCheckResources(engineRoot.absolutePath, resourcesDir.absolutePath))
        }.getOrElse { error ->
            throw IllegalStateException("Horizon's official resource checker could not run.", error)
        }
        if (!result.optBoolean("ok", false)) {
            val details = result.optJSONArray("missing")
                ?.let { array -> (0 until array.length()).joinToString("; ") { array.optString(it) } }
                ?: result.optString("error", "unknown validation error")
            throw IllegalStateException("Horizon 26.3 resources are not ready: $details")
        }
    }

    fun createServerJarTask(gameHome: String): Task = Task.runTask(
        id = "HorizonRust.DownloadServerJar",
        dispatcher = Dispatchers.IO,
        task = { task ->
            task.updateProgress(-1f)
            val downloader = BaseMinecraftDownloader(gameHome)
            val version = downloader.findVersion(MINECRAFT_VERSION)
                ?: throw IllegalStateException("Minecraft $MINECRAFT_VERSION is missing from Mojang's version manifest.")

            val manifest = downloader.createVersionJson(version)
            val server = manifest.downloads?.server
                ?: throw IllegalStateException("Mojang did not publish a server JAR for Minecraft $MINECRAFT_VERSION.")
            require(server.url.isNotBlank()) { "Minecraft $MINECRAFT_VERSION server JAR URL is empty." }
            require(server.sha1.isNotBlank()) { "Minecraft $MINECRAFT_VERSION server JAR checksum is missing." }
            require(server.size > 0L) { "Minecraft $MINECRAFT_VERSION server JAR size is invalid." }

            val profileDir = File(getVersionsHome(gameHome), PROFILE_NAME).apply {
                check(isDirectory || mkdirs()) { "Could not create Horizon profile directory: $absolutePath" }
            }
            val target = File(profileDir, SERVER_JAR_NAME)

            if (target.isFile && target.length() == server.size && compareSHA1(target, server.sha1)) {
                task.updateProgress(1f)
                return@runTask
            }

            DownloadEngine.download(
                DownloadRequest(
                    urls = server.url.mapBMCLMirrorUrls(),
                    targetFile = target,
                    sha1 = server.sha1,
                    expectedSize = server.size
                )
            )

            check(target.isFile && target.length() == server.size && compareSHA1(target, server.sha1)) {
                "Downloaded Minecraft $MINECRAFT_VERSION server JAR failed integrity verification."
            }
            task.updateProgress(1f)
        }
    )
}
