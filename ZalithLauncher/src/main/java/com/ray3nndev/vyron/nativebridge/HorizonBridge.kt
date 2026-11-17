package com.ray3nndev.vyron.nativebridge

import androidx.annotation.Keep

/**
 * JNI boundary for the original Horizon Rust Android engine.
 *
 * The native library is packaged for arm64-v8a. Do not invoke nativePrepare
 * until the launcher has completed Horizon's version/resource preparation
 * and the GameActivity lifecycle handoff is implemented.
 */
@Keep
object HorizonBridge {
    init {
        System.loadLibrary("horizon_android_bridge")
    }

    /** Configure app-private paths. Returns 0 on success. */
    @JvmStatic
    external fun nativeConfigure(
        gameDir: String,
        homeDir: String,
        resourcesDir: String
    ): Int

    /**
     * Ask Horizon to validate and prepare the configured launch.
     * Returns 0 on success; negative values indicate a preparation failure.
     */
    @JvmStatic
    external fun nativePrepare(): Int
}
