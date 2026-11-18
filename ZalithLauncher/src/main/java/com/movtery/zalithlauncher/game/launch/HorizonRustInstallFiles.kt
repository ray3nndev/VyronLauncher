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
import com.movtery.zalithlauncher.game.multirt.RuntimesManager
import com.movtery.zalithlauncher.utils.device.Architecture
import com.movtery.zalithlauncher.game.version.download.BaseMinecraftDownloader
import com.movtery.zalithlauncher.game.addons.mirror.mapBMCLMirrorUrls
import com.movtery.zalithlauncher.utils.file.compareSHA1
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
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
    private const val PREPARE_JRE_NAME = "Horizon-25-Prepare"
    private const val JRE25_URL = "https://github.com/BattlyLauncher/battly-openjdk-build/releases/download/last-release/jre25-android-arm64.tar.xz"
    private const val JRE25_SIZE = 38_013_284L
    private const val JRE25_SHA256 = "c4ee53fc699be07ff10930261f12e335cbf411dd53e13a29d4cf7d6be8c35065"


    /**
     * Materialize the exact pinned upstream preparer inputs into writable app-private storage.
     * The Java oracle sources and exporter settings are original Horizon inputs; game JARs are
     * always downloaded by the user through Mojang's manifest and are never bundled here.
     */
    private fun ensurePreparationRoot(context: Context): File {
        val root = File(context.filesDir, "horizon/engine-check")
        val required = listOf(
            File(root, "research-lock.json"),
            File(root, "crates/horizon-prepare/src/exporters.rs"),
            File(root, "mods/horizon/src/test/java")
        )
        fun complete(file: File): Boolean =
            file.isFile || (file.isDirectory && file.walkTopDown().any { child -> child.isFile })

        if (required.all(::complete)) return root

        if (root.exists()) {
            check(root.deleteRecursively()) { "Could not reset Horizon preparation inputs." }
        }
        check(root.mkdirs() || root.isDirectory) { "Could not create Horizon preparation directory." }
        copyPreparationAsset(context, "horizon/prepare/research-lock.json", File(root, "research-lock.json"))
        copyPreparationAsset(
            context,
            "horizon/prepare/crates/horizon-prepare/src/exporters.rs",
            File(root, "crates/horizon-prepare/src/exporters.rs")
        )
        copyPreparationAsset(
            context,
            "horizon/prepare/mods/horizon/src/test/java",
            File(root, "mods/horizon/src/test/java")
        )
        check(required.all(::complete)) {
            "The APK is missing pinned Horizon preparation sources. Rebuild with the Horizon source package step."
        }
        return root
    }

    private fun copyPreparationAsset(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            check(destination.isDirectory || destination.mkdirs()) {
                "Could not create Horizon asset directory: ${destination.absolutePath}"
            }
            children.forEach { child ->
                copyPreparationAsset(context, "$assetPath/$child", File(destination, child))
            }
        } else {
            destination.parentFile?.let { parent ->
                check(parent.isDirectory || parent.mkdirs()) {
                    "Could not create Horizon asset parent: ${parent.absolutePath}"
                }
            }
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /** Canonical app-private output consumed by both preparation and native launch. */
    fun resourcesDirectory(context: Context): File =
        File(context.filesDir, "horizon/26.3/resources")

    /**
     * Download Java 25 only for Horizon's resource-preparation phase, verify the
     * release asset digest, run the official preparer, then remove the temporary
     * runtime so it does not appear in the user's Java runtime list.
     */
    fun createResourcePreparationTask(context: Context, gameHome: String): Task = Task.runTask(
        id = "HorizonRust.PrepareResources",
        dispatcher = Dispatchers.IO,
        task = { task ->
            require(Architecture.getDeviceArchitecture() == Architecture.ARCH_ARM64) {
                "The Horizon Rust engine currently requires a 64-bit ARM Android device."
            }
            task.updateProgress(-1f)
            val archive = File(context.cacheDir, "horizon-jre25-arm64.tar.xz")
            try {
                val javaHome = runCatching {
                    RuntimesManager.getRuntimeHome(PREPARE_JRE_NAME)
                }.getOrElse {
                    RuntimesManager.removeRuntime(PREPARE_JRE_NAME)
                    DownloadEngine.download(
                        DownloadRequest(
                            urls = listOf(JRE25_URL),
                            targetFile = archive,
                            expectedSize = JRE25_SIZE
                        )
                    )
                    check(archive.isFile && archive.length() == JRE25_SIZE) {
                        "The Java 25 runtime download is incomplete."
                    }
                    check(sha256(archive) == JRE25_SHA256) {
                        "The Java 25 runtime failed SHA-256 verification."
                    }
                    FileInputStream(archive).use { input ->
                        RuntimesManager.installRuntime(
                            nativeLibDir = context.applicationInfo.nativeLibraryDir,
                            inputStream = input,
                            name = PREPARE_JRE_NAME,
                            prepareRuntime = false
                        )
                    }
                    RuntimesManager.getRuntimeHome(PREPARE_JRE_NAME)
                }

                prepareResources(
                    context = context,
                    gameHome = gameHome,
                    javaHome = javaHome,
                    outputDir = resourcesDirectory(context)
                )
            } finally {
                archive.delete()
                // This runtime is only a preparation dependency, never a gameplay runtime.
                runCatching { RuntimesManager.removeRuntime(PREPARE_JRE_NAME) }
            }
            task.updateProgress(1f)
        }
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify prepared resources with Horizon's official checker, not a Kotlin approximation.
     * The lock file is copied from this APK's pinned engine revision into app-private storage.
     */
    fun verifyPreparedResources(context: Context, resourcesDir: File) {
        val engineRoot = ensurePreparationRoot(context)

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

    /**
     * Prepare the downloaded 26.3 server with Horizon's pinned Rust preparer.
     */
    fun prepareResources(
        context: Context,
        gameHome: String,
        javaHome: File,
        outputDir: File
    ) {
        require(javaHome.isDirectory) { "Java 25 runtime is missing: $javaHome" }

        val profileDir = File(getVersionsHome(gameHome), PROFILE_NAME)
        val serverJar = File(profileDir, SERVER_JAR_NAME)
        require(serverJar.isFile) { "Minecraft 26.3 server JAR is missing: $serverJar" }

        val engineRoot = ensurePreparationRoot(context)

        val javaLauncher = File(context.applicationInfo.nativeLibraryDir, "libhorizon_java25.so")
        val jliLibrary = File(javaHome, "lib/jli/libjli.so")
        require(javaLauncher.isFile && javaLauncher.canExecute()) {
            "Horizon Java 25 launcher is missing or not executable: $javaLauncher"
        }
        require(jliLibrary.isFile) { "Java 25 libjli.so is missing: $jliLibrary" }

        val result = runCatching {
            JSONObject(
                HorizonBridge.nativePrepareResources(
                    engineRoot.absolutePath,
                    File(gameHome).absolutePath,
                    serverJar.absolutePath,
                    javaHome.absolutePath,
                    outputDir.absolutePath,
                    javaLauncher.absolutePath,
                    jliLibrary.absolutePath
                )
            )
        }.getOrElse { error ->
            throw IllegalStateException("Horizon 26.3 resource preparation could not start.", error)
        }

        if (!result.optBoolean("ok", false)) {
            throw IllegalStateException(
                "Horizon 26.3 resource preparation failed: " +
                    result.optString("error", "unknown preparation error")
            )
        }

        verifyPreparedResources(context, outputDir)
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
