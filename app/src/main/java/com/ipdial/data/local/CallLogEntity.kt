package com.ipdial.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry

@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val remoteUri: String,
    val remoteDisplayName: String,
    val direction: CallDirection,
    val missed: Boolean,
    val timestampMs: Long,
    val durationSeconds: Long,
    val disconnectCode: Int?,
    val disconnectReason: String?
) {
    fun toCallLogEntry(): CallLogEntry = CallLogEntry(
        id = id,
        accountId = accountId,
        remoteUri = remoteUri,
        remoteDisplayName = remoteDisplayName,
        direction = direction,
        missed = missed,
        timestampMs = timestampMs,
        durationSeconds = durationSeconds,
        disconnectCode = disconnectCode,
        disconnectReason = disconnectReason
    )

    companion object {
        fun fromCallLogEntry(entry: CallLogEntry): CallLogEntity = CallLogEntity(
            id = entry.id,
            accountId = entry.accountId,
            remoteUri = entry.remoteUri,
            remoteDisplayName = entry.remoteDisplayName,
            direction = entry.direction,
            missed = entry.missed,
            timestampMs = entry.timestampMs,
            durationSeconds = entry.durationSeconds,
            disconnectCode = entry.disconnectCode,
            disconnectReason = entry.disconnectReason
        )
    }
}
