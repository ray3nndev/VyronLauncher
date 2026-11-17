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
     * Mirror the upstream horizon-prepare --check contract before starting native code.
     * A server JAR alone is not a prepared game: the resource manifest, matching output
     * stamp, and required extra assets must all be present.
     */
    fun verifyPreparedResources(resourcesDir: File) {
        val manifestFile = File(resourcesDir, "horizon-resources.json")
        val stampFile = File(resourcesDir, "horizon-prepare.json")
        check(manifestFile.isFile) {
            "Horizon 26.3 resources are not prepared. Missing horizon-resources.json."
        }
        check(stampFile.isFile) {
            "Horizon 26.3 resources are outdated or incomplete. Missing horizon-prepare.json."
        }

        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        check(manifest.optInt("schema", -1) == 7) {
            "Horizon resources use an unsupported schema; prepare them again with the pinned Horizon version."
        }
        check(manifest.optString("minecraft") == MINECRAFT_VERSION) {
            "Horizon resources are not prepared for Minecraft $MINECRAFT_VERSION."
        }
        val stamp = JSONObject(stampFile.readText(Charsets.UTF_8))
        check(stamp.optInt("output_version", -1) == 4) {
            "Horizon resources were prepared by an incompatible exporter version."
        }

        val assets = File(resourcesDir, "assets")
        val required = listOf(
            "minecraft/resourcepacks/programmer_art.zip",
            "minecraft/resourcepacks/high_contrast.zip",
            "minecraft/font/unifont.zip",
            "pack.mcmeta",
            "minecraft/sounds.json"
        )
        required.forEach { relative ->
            check(File(assets, relative).isFile) {
                "Horizon resources are incomplete: missing assets/$relative."
            }
        }

        val panorama = File(assets, "minecraft/textures/gui/title/background")
        var expectedSize = -1
        for (face in 0..5) {
            val png = File(panorama, "panorama_$face.png")
            check(png.isFile) { "Horizon resources are incomplete: missing ${png.name}." }
            val bytes = png.readBytes()
            check(bytes.size >= 24 &&
                bytes.copyOfRange(0, 16).contentEquals(
                    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 0x49, 0x48, 0x44, 0x52)
                )
            ) { "Horizon panorama face ${png.name} is not a valid PNG." }
            val width = readPngInt(bytes, 16)
            val height = readPngInt(bytes, 20)
            check(width > 1 && width == height && (expectedSize == -1 || expectedSize == width)) {
                "Horizon panorama faces must be non-placeholder squares of equal dimensions."
            }
            expectedSize = width
        }
        check(File(panorama, "panorama_overlay.png").isFile) {
            "Horizon resources are incomplete: missing panorama_overlay.png."
        }
    }

    private fun readPngInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

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
