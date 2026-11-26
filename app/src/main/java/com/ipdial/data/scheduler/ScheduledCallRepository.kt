package com.ipdial.data.scheduler

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.scheduledCallsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ipdial_scheduled_calls"
)

class ScheduledCallRepository(private val context: Context) {

    private val callsKey = stringPreferencesKey("scheduled_calls_json")

    val scheduledCalls: Flow<List<ScheduledCall>> = context.scheduledCallsDataStore.data.map { prefs ->
        parseList(prefs[callsKey] ?: "[]").sortedWith(
            compareBy<ScheduledCall> { it.status != ScheduledCallStatus.PENDING && it.status != ScheduledCallStatus.TRIGGERED }
                .thenBy { it.scheduledAtMs }
        )
    }

    suspend fun getById(id: String): ScheduledCall? =
        scheduledCalls.first().firstOrNull { it.id == id }

    suspend fun pendingFutureCalls(nowMs: Long = System.currentTimeMillis()): List<ScheduledCall> =
        scheduledCalls.first().filter {
            it.status == ScheduledCallStatus.PENDING && it.scheduledAtMs > nowMs
        }

    suspend fun upsert(call: ScheduledCall) {
        context.scheduledCallsDataStore.edit { prefs ->
            val current = parseList(prefs[callsKey] ?: "[]").toMutableList()
            val idx = current.indexOfFirst { it.id == call.id }
            if (idx >= 0) {
                current[idx] = call
            } else {
                current.add(call)
            }
            prefs[callsKey] = serializeList(current)
        }
    }

    suspend fun updateStatus(id: String, status: ScheduledCallStatus) {
        context.scheduledCallsDataStore.edit { prefs ->
            val current = parseList(prefs[callsKey] ?: "[]").toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx >= 0) {
                current[idx] = current[idx].copy(status = status)
                prefs[callsKey] = serializeList(current)
            }
        }
    }

    suspend fun delete(id: String) {
        context.scheduledCallsDataStore.edit { prefs ->
            val current = parseList(prefs[callsKey] ?: "[]").filterNot { it.id == id }
            prefs[callsKey] = serializeList(current)
        }
    }

    companion object {
        private val gson = Gson()

        fun parseList(json: String): List<ScheduledCall> {
            val type = object : TypeToken<List<ScheduledCall>>() {}.type
            return runCatching { gson.fromJson<List<ScheduledCall>>(json, type) }
                .getOrNull()
                ?.filter { it.phoneNumber.isNotBlank() }
                ?: emptyList()
        }

        fun serializeList(list: List<ScheduledCall>): String = gson.toJson(list)
    }
}
