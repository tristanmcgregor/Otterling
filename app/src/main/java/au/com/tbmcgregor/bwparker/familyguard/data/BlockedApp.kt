package au.com.tbmcgregor.bwparker.familyguard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val blocked: Boolean = true,
)
