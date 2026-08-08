package app.otterling.restrictions

import android.os.UserManager

/**
 * Stock Android Device Owner restrictions — no Knox SDK required.
 * See https://developer.android.com/reference/android/os/UserManager for each key.
 */
enum class Restriction(val userManagerKey: String, val displayName: String) {
    SAFE_BOOT(UserManager.DISALLOW_SAFE_BOOT, "Block Safe Mode boot"),
    FACTORY_RESET(UserManager.DISALLOW_FACTORY_RESET, "Block factory reset"),
    DEBUGGING_FEATURES(UserManager.DISALLOW_DEBUGGING_FEATURES, "Block USB debugging"),
    ADD_USER(UserManager.DISALLOW_ADD_USER, "Block guest mode / additional users"),

    // Closes the "build a custom APK to game the MITM-exemption heuristic" gap: the auto-exemption
    // heuristic (PinningFailureTracker) reacts to *observed connection shape*, not real cryptographic
    // pinning, so a deliberately-crafted sideloaded app could mimic it. Blocking installs outside
    // Play Store removes the delivery mechanism for that entirely, regardless of how convincing the
    // app's own traffic pattern is.
    INSTALL_UNKNOWN_SOURCES(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, "Block installing apps from unknown sources"),

    // Stronger companion to the above (API 30+; a restriction key an older OS doesn't recognize is
    // safely inert, same as every other UserManager restriction on a device below its minimum API --
    // no version gating needed): also stops an *already-installed* app from installing another app
    // on the user's behalf without going through Play Store's own install flow.
    INSTALL_UNKNOWN_SOURCES_GLOBALLY(
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        "Block other apps from installing apps on your behalf",
    ),
}
