package app.otterling.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "protected_apps")
data class ProtectedApp(
    @PrimaryKey val packageName: String,
)
