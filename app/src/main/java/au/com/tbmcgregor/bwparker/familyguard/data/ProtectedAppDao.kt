package au.com.tbmcgregor.bwparker.familyguard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProtectedAppDao {
    @Query("SELECT * FROM protected_apps ORDER BY packageName")
    suspend fun getAll(): List<ProtectedApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: ProtectedApp)

    @Query("DELETE FROM protected_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
