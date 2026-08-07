package app.otterling.alerts

enum class AlertSeverity {
    /** Immediate SMS (subject to debounce/cap). */
    CRITICAL,
    /** SMS for watched/trigger hits. */
    WARNING,
    /** Local log only unless settings say otherwise. */
    INFO,
}
