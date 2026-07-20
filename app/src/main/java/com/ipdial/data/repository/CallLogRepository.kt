package com.ipdial.data.repository

import android.content.Context
import com.ipdial.data.local.AppDatabase
import com.ipdial.data.local.CallLogEntity
import com.ipdial.data.model.CallLogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed call-log repository.
 */
class CallLogRepository private constructor(context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val dao = db.callLogDao()

    val entries: Flow<List<CallLogEntry>> = dao.getAll().map { list ->
        list.map { it.toCallLogEntry() }
    }

    suspend fun insert(entry: CallLogEntry) {
        dao.insert(CallLogEntity.fromCallLogEntry(entry))
        dao.trim(200) // Keep last 200 entries
    }

    suspend fun delete(entry: CallLogEntry) {
        dao.delete(CallLogEntity.fromCallLogEntry(entry))
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    companion object {
        @Volatile
        private var INSTANCE: CallLogRepository? = null

        fun getInstance(context: Context): CallLogRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CallLogRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
