package au.com.tbmcgregor.bwparker.familyguard.tamper

import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase

class TamperEventLogger(context: Context) {
    private val dao = AppDatabase.getInstance(context).tamperEventDao()

    suspend fun log(type: String, details: String) {
        dao.insert(TamperEvent(type = type, details = details))
    }

    suspend fun recent(limit: Int = 20): List<TamperEvent> = dao.recent(limit)

    suspend fun since(sinceMillis: Long): List<TamperEvent> = dao.since(sinceMillis)
}
