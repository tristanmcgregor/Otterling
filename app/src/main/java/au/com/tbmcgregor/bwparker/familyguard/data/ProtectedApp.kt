package au.com.tbmcgregor.bwparker.familyguard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "protected_apps")
data class ProtectedApp(
    @PrimaryKey val packageName: String,
)
