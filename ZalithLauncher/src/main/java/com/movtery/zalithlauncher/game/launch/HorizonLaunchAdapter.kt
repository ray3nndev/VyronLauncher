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

import androidx.annotation.Keep
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.isLocalAccount
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.versioninfo.models.GameManifest
import com.movtery.zalithlauncher.utils.GSON
import java.io.File

/**
 * Converts the launcher's already-validated Zalith account/version state into
 * a versioned, engine-neutral request for the future Horizon Android host.
 *
 * This class deliberately does not start an Activity or persist credentials:
 * the Horizon host and its agreed IPC contract are not present in this source
 * tree yet. The existing Zalith launch path therefore remains authoritative.
 */
object HorizonLaunchAdapter {
    const val SCHEMA_VERSION = 1

    @Keep
    data class LaunchRequest(
        val schemaVersion: Int,
        val minecraftVersion: String,
        val gameHome: String,
        val gameDirectory: String,
        val versionDirectory: String,
        val clientJar: String,
        val manifestJson: String,
        val account: AccountSession,
        val renderer: String,
        val driver: String,
        val graphicsApi: String,
        val ramMb: Int,
        val controlLayout: String?,
        val offlineAccountLogin: Boolean,
        val touchProxyEnabled: Boolean,
        val fpeEmulatorModDetected: Boolean,
        val serverAddress: String?
    )

    @Keep
    data class AccountSession(
        val authType: String,
        val username: String,
        val profileId: String,
        val accessToken: String?,
        val expiresAt: Long,
        val authServerUrl: String?
    )

    /**
     * Build a request from the exact Version and Account selected by the
     * existing launcher flow. The access token is kept in memory only; refresh
     * tokens, passwords, and client secrets are never copied into this DTO.
     */
    fun createRequest(version: Version, account: Account): LaunchRequest {
        require(version.isValid()) { "Cannot prepare Horizon launch: selected version is invalid." }

        val manifestJson = requireNotNull(version.launchManifest) {
            "Cannot prepare Horizon launch before the launcher has built the game manifest."
        }
        val manifest = runCatching {
            GSON.fromJson(manifestJson, GameManifest::class.java)
        }.getOrElse {
            throw IllegalArgumentException("Cannot prepare Horizon launch: game manifest is invalid.", it)
        }

        val inheritedClientJar = manifest.inheritsFrom
            ?.let { version.getInheritedClientJar(it) }
        val clientJar = inheritedClientJar ?: version.getClientJar()
        require(clientJar.isFile) {
            "Cannot prepare Horizon launch: client JAR is missing (${clientJar.name})."
        }

        val offline = version.offlineAccountLogin || account.isLocalAccount()
        val authType = when {
            offline -> "offline"
            account.isMicrosoftAccount() -> "microsoft"
            else -> account.accountType?.lowercase() ?: "custom"
        }

        return LaunchRequest(
            schemaVersion = SCHEMA_VERSION,
            minecraftVersion = version.getVersionInfo()?.minecraftVersion ?: version.getVersionName(),
            gameHome = File(version.getGameHome()).absolutePath,
            gameDirectory = version.getGameDir().absolutePath,
            versionDirectory = version.getVersionPath().absolutePath,
            clientJar = clientJar.absolutePath,
            manifestJson = manifestJson,
            account = AccountSession(
                authType = authType,
                username = account.username,
                profileId = account.profileId,
                accessToken = account.accessToken.takeUnless { offline || it.isBlank() || it == "0" },
                expiresAt = account.expiresAt,
                authServerUrl = account.otherBaseUrl.takeUnless { offline || authType == "microsoft" }
            ),
            renderer = version.getRenderer(),
            driver = version.getDriver(),
            graphicsApi = version.getGraphicsApi().name,
            ramMb = version.getRamAllocation(),
            controlLayout = version.getControlPath()?.absolutePath,
            offlineAccountLogin = offline,
            touchProxyEnabled = version.enableTouchProxy,
            fpeEmulatorModDetected = version.fpeEmulatorModDetected,
            serverAddress = version.getServerIp()
        )
    }

    /**
     * Build Horizon's actual CLI arguments. Microsoft credentials are handed
     * over through a short-lived owner-only session file, never through Intent
     * extras, logs, or persistent launcher settings.
     */
    fun createNativeArguments(
        version: Version,
        account: Account,
        privateSessionDirectory: File
    ): List<String> {
        val offline = version.offlineAccountLogin || account.isLocalAccount()
        val args = mutableListOf("--name", account.username)

        if (!offline) {
            require(account.isMicrosoftAccount()) {
                "Horizon session handoff currently supports Microsoft accounts only."
            }
            val token = account.accessToken.takeUnless { it.isBlank() || it == "0" }
                ?: throw IllegalStateException("Microsoft session token is missing; refresh the account and retry.")

            require(privateSessionDirectory.isAbsolute) {
                "Horizon session directory must be an absolute app-private path."
            }
            check(privateSessionDirectory.exists() || privateSessionDirectory.mkdirs()) {
                "Could not create Horizon's private session directory."
            }
            val sessionFile = File.createTempFile("session-", ".json", privateSessionDirectory)
            try {
                check(sessionFile.setReadable(false, false) && sessionFile.setReadable(true, true)) {
                    "Could not restrict Horizon session file permissions."
                }
                check(sessionFile.setWritable(false, false) && sessionFile.setWritable(true, true)) {
                    "Could not restrict Horizon session file permissions."
                }
                val payload = mapOf(
                    "name" to account.username,
                    "uuid" to account.profileId,
                    "access_token" to token,
                    "user_type" to "msa",
                    "xuid" to account.xUid
                )
                sessionFile.writeText(GSON.toJson(payload), Charsets.UTF_8)
                args += listOf("--session-file", sessionFile.absolutePath)
            } catch (error: Throwable) {
                sessionFile.delete()
                throw error
            }
        }

        version.getServerIp()?.takeIf { it.isNotBlank() }?.let {
            args += listOf("--server", it)
        }
        return args
    }

    /** Serialize for diagnostics or a future private same-app handoff. Never log this string. */
    fun encode(request: LaunchRequest): String = GSON.toJson(request)
}
