package com.ray3nndev.vyron.nativebridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.annotation.Keep
import com.google.androidgamesdk.GameActivity

/**
 * Native entry surface for Horizon's winit GameActivity backend.
 * Horizon must be fully installed/prepared before this Activity is started.
 */
@Keep
class HorizonGameActivity : GameActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val gameDir = intent.getStringExtra(EXTRA_GAME_DIR)
        val homeDir = intent.getStringExtra(EXTRA_HOME_DIR)
        val resourcesDir = intent.getStringExtra(EXTRA_RESOURCES_DIR)
        val launchArgsJson = intent.getStringExtra(EXTRA_LAUNCH_ARGS) ?: "[]"

        val failure = when {
            gameDir.isNullOrBlank() || homeDir.isNullOrBlank() || resourcesDir.isNullOrBlank() ->
                "Horizon launch paths were not supplied by Vyron."
            else -> {
                val configure = runCatching {
                    HorizonBridge.nativeConfigure(gameDir, homeDir, resourcesDir, launchArgsJson)
                }.getOrElse { -100 }
                when {
                    configure != 0 -> "Horizon native configuration failed ($configure)."
                    else -> {
                        val prepare = runCatching { HorizonBridge.nativePrepare() }
                            .getOrElse { -101 }
                        if (prepare == 0) null
                        else "Horizon startup preparation failed ($prepare). Check the installed 26.3 resources."
                    }
                }
            }
        }

        // GameActivity starts android_main from its superclass onCreate.
        super.onCreate(savedInstanceState)
        failure?.let {
            setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, it))
            finish()
        }
    }

    companion object {
        const val EXTRA_GAME_DIR = "com.ray3nndev.vyron.horizon.GAME_DIR"
        const val EXTRA_HOME_DIR = "com.ray3nndev.vyron.horizon.HOME_DIR"
        const val EXTRA_RESOURCES_DIR = "com.ray3nndev.vyron.horizon.RESOURCES_DIR"
        const val EXTRA_LAUNCH_ARGS = "com.ray3nndev.vyron.horizon.LAUNCH_ARGS"
        const val EXTRA_ERROR = "com.ray3nndev.vyron.horizon.ERROR"
    }
}
