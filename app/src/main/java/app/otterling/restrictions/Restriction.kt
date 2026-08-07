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
}
