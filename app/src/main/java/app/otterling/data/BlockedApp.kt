package app.otterling.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val blocked: Boolean = true,
    // Set only by AppSuspensionManager.blockTemporarily (e.g. a visual-filter NSFW detection) --
    // null for an ordinary guardian/dashboard block, which never expires on its own.
    val blockedUntilMillis: Long? = null,
)
