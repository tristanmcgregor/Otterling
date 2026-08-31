package app.otterling.restrictions

import android.os.UserManager

/**
 * Stock Android Device Owner restrictions — no Knox SDK required.
 * See https://developer.android.com/reference/android/os/UserManager for each key.
 *
 * [dashboardKey], where non-null, is the matching field name in the dashboard's `protections`
 * object (see `filter-server/dashboard/SERVER_DRIVEN_CONFIG_PLAN.md`'s Phase 3) -- consumed by
 * [RestrictionPreferences.isDesired] to let the dashboard drive this restriction's desired state.
 * Null for the two restrictions below with no dashboard field yet (not part of the plan's mapped
 * schema).
 */
enum class Restriction(val userManagerKey: String, val displayName: String, val dashboardKey: String? = null) {
    SAFE_BOOT(UserManager.DISALLOW_SAFE_BOOT, "Block Safe Mode boot", "safeMode"),
    FACTORY_RESET(UserManager.DISALLOW_FACTORY_RESET, "Block factory reset", "factoryReset"),
    DEBUGGING_FEATURES(UserManager.DISALLOW_DEBUGGING_FEATURES, "Block USB debugging", "usbDebugging"),
    ADD_USER(UserManager.DISALLOW_ADD_USER, "Block guest mode / additional users", "guestMode"),

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

    // Greys out Settings > Network > VPN entirely (can't add/edit/remove a VPN config, and can't
    // flip another app's "Always-on VPN" toggle either) -- closes the bypass where the user leaves
    // this app's VPN filter running but switches the system's "always-on VPN" designation to a
    // different app (or to none), which silently stops routing traffic through the filter. This is
    // the one Canopy has that we didn't: it doesn't just re-assert *our* always-on VPN, it removes
    // the user's ability to touch VPN settings at all.
    CONFIG_VPN(UserManager.DISALLOW_CONFIG_VPN, "Lock VPN settings (prevent switching or disabling the always-on VPN)"),
}
