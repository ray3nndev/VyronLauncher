# Horizon Rust Android integration

## Current integration boundary

Vyron keeps the Zalith-derived launcher UI, account selection, version browsing, and existing installation flow. The original Horizon Rust engine is built from the pinned `ray3nndev/horizon-android` port; Starling is not used.

The ARM64 native bridge is packaged by the integration build workflow into:

`ZalithLauncher/src/main/jniLibs/arm64-v8a/libhorizon_android_bridge.so`

The Kotlin JNI names and Rust exports must stay aligned:

- `HorizonBridge.nativeConfigure(gameDir, homeDir, resourcesDir, launchArgsJson)`
- `HorizonBridge.nativePrepare()`

The launch-argument JSON is an array of strings passed to Horizon's `LaunchArguments` parser. Microsoft sessions use Horizon's read-once session-file format; the file is created in app-private storage with owner-only permissions. Passwords and refresh tokens are never passed to native code.

## Minecraft 26.3 preparation requirements

The pinned Horizon upstream revision targets Minecraft **26.3** and declares **Java major 25** in `research-lock.json`. The first preparation also requires the pinned server JAR, the version JSON, client JAR, libraries, and asset store. Horizon's `horizon-prepare` checks these inputs and writes a `horizon-resources.json` marker consumed by the native client.

This is a preparation-time dependency, not a reason to restore a persistent Java gameplay runtime. A temporary compatible Java 25 runtime may be needed for the vanilla exporters; it must be isolated to preparation and removed afterward if the Android runtime route supports that execution model. Do not mark the Rust version installed until the preparer completes and `horizon-prepare --check` passes.

## Not yet verified

- Vyron APK build and inclusion of the ARM64 bridge.
- Horizon 26.3 resource preparation on Android.
- Play-flow routing into `HorizonGameActivity`.
- Android surface creation, rendering, lifecycle, input, audio, and actual gameplay on a physical device.

A successful Rust cross-build or a packaged shared library is not proof of a playable game.
