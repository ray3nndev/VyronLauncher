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
}
