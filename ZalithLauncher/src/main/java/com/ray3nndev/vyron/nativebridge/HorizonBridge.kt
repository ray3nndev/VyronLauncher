package com.ray3nndev.vyron.nativebridge

import androidx.annotation.Keep

/** JNI boundary for the original Horizon Rust Android engine. */
@Keep
object HorizonBridge {
    init { System.loadLibrary("horizon_android_bridge") }

    /** Configure private paths and a JSON array of Horizon CLI arguments. */
    @JvmStatic
    external fun nativeConfigure(
        gameDir: String,
        homeDir: String,
        resourcesDir: String,
        launchArgsJson: String
    ): Int

    /** Validate and prepare the configured Horizon launch. */
    @JvmStatic
    external fun nativePrepare(): Int

    /**
     * Run Horizon's upstream resource preparer. The root must contain the pinned
     * research-lock.json, parity Java sources and original precompiled exporters.
     * javaHome must expose a runnable Java 25 executable at bin/java.
     * Returns a JSON result; never log it because future error payloads may contain paths.
     */
    @JvmStatic
    external fun nativePrepareResources(
        root: String,
        minecraftDir: String,
        serverJar: String,
        javaHome: String,
        output: String
    ): String

    /** Run the exact checker shipped by the pinned Horizon source revision. */
    @JvmStatic
    external fun nativeCheckResources(root: String, resources: String): String
}
