package app.otterling.restrictions

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes device-restriction/DPM-touching reapply passes across their two independent
 * schedulers -- [app.otterling.monitoring.ProtectionEnforcementService]'s 5-minute foreground loop
 * and [RestrictionEnforcementWorker]'s 15-minute WorkManager backstop (see that class's doc
 * comment for why both exist). Both can otherwise trigger their own reapply pass for the same
 * manager at the same moment; each call today is independently idempotent, so that's harmless, but
 * nothing actually enforced that guarantee -- this makes "these two passes never truly overlap" a
 * real property instead of an assumption that could quietly stop holding as either side's reapply
 * logic grows more complex. A single companion-style Mutex, shared by every caller in the process.
 */
object EnforcementCoordinator {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
