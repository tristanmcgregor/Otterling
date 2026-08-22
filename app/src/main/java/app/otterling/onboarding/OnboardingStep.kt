package app.otterling.onboarding

import android.content.Context
import app.otterling.admin.DeviceOwnerManager
import app.otterling.alerts.AccountabilityPartnerSettings
import app.otterling.content.VpnFilterManager
import app.otterling.restrictions.AccessibilityGuard
import app.otterling.restrictions.DeviceRestrictionsManager
import app.otterling.restrictions.Restriction

enum class OnboardingStep { Welcome, DeviceOwner, Restrictions, ContentFilter, AccountabilityPartnerSms, Accessibility, Done }

/**
 * Live, side-effect-free scan of every manager's own persisted/system state, first-incomplete-wins.
 * Deliberately excludes [OnboardingStep.Welcome] -- callers check [OnboardingState.hasSeenWelcome]
 * for that separately, since it isn't a real protection to verify, just a one-time intro screen.
 * Also excludes the Guardian PIN entirely -- it's set exclusively from the dashboard now (see
 * [app.otterling.ui.PinLockScreen]'s doc comment), so there's nothing for onboarding to gate on;
 * a fresh install simply has no PIN until the guardian sets one on the website and it syncs down.
 * Cheap (no network calls); safe to call on the main thread, and safe to call repeatedly (e.g. once
 * per app cold start) to resume the wizard at the right place after a relaunch.
 */
fun resolveOnboardingStep(context: Context): OnboardingStep {
    if (!DeviceOwnerManager(context).currentStatus().isDeviceOwner) return OnboardingStep.DeviceOwner

    val restrictionsManager = DeviceRestrictionsManager(context)
    val restrictionsDone = Restriction.entries.all { restrictionsManager.isEnabled(it) } &&
        restrictionsManager.isUninstallBlocked()
    if (!restrictionsDone) return OnboardingStep.Restrictions

    if (!VpnFilterManager(context).wasEnabledByUser()) return OnboardingStep.ContentFilter

    val alerts = AccountabilityPartnerSettings(context)
    if (!(alerts.isEnabled() && alerts.partnerNumbers().isNotEmpty())) return OnboardingStep.AccountabilityPartnerSms

    if (!AccessibilityGuard.isEnabled(context)) return OnboardingStep.Accessibility

    return OnboardingStep.Done
}
