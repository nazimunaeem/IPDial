package com.ipdial.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.CallTransport
import com.ipdial.service.sim.SimPhoneAccountHelper

/**
 * Reads the device call log through the platform provider. As the default dialer IPDial
 * has full access to this log, so history also includes calls placed before the app was
 * installed or by other apps.
 */
object SystemCallLogRepository {

    private const val TAG = "SystemCallLogRepository"
    private const val DEFAULT_LIMIT = 500
    private const val SIP_PHONE_ACCOUNT_ID = "IPDIAL_SIP_ACCOUNT"

    fun canRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    fun read(context: Context, limit: Int = DEFAULT_LIMIT): List<CallLogEntry> {
        if (!canRead(context)) return emptyList()

        val resolver = context.contentResolver
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.FEATURES
        )

        val simLabelsByHandleId = try {
            SimPhoneAccountHelper.getAvailableSimLines(context)
                .associate { it.handle.id to it.label }
        } catch (_: Throwable) {
            emptyMap()
        }

        val result = ArrayList<CallLogEntry>(limit)
        try {
            resolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val accountIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID)
                val featuresIdx = cursor.getColumnIndex(CallLog.Calls.FEATURES)

                while (cursor.moveToNext() && result.size < limit) {
                    val rowId = cursor.getLong(idIdx)
                    val number = cursor.getString(numberIdx)
                        ?.takeUnless { it.isBlank() || it == "-1" }
                        .orEmpty()
                    val cachedName = cursor.getString(nameIdx)
                        ?.takeUnless { it.isBlank() || it == "-1" }
                        .orEmpty()
                    val date = cursor.getLong(dateIdx)
                    val durationSec = cursor.getLong(durationIdx)
                    val type = cursor.getInt(typeIdx)
                    val accountId = cursor.getString(accountIdx).orEmpty()
                    val features = if (featuresIdx >= 0) cursor.getInt(featuresIdx) else 0

                    val isSip = accountId == SIP_PHONE_ACCOUNT_ID
                    val transport = if (isSip) CallTransport.SIP else CallTransport.SIM
                    val lineLabel = when {
                        isSip -> "SIP"
                        else -> simLabelsByHandleId[accountId] ?: "SIM"
                    }

                    result.add(
                        CallLogEntry(
                            id = "sys_$rowId",
                            accountId = accountId,
                            remoteUri = number,
                            remoteDisplayName = cachedName,
                            direction = directionForType(type),
                            missed = isMissedType(type),
                            timestampMs = date,
                            durationSeconds = durationSec.coerceAtLeast(0L),
                            transport = transport,
                            lineLabel = lineLabel,
                            isHdAudio = hasHdFeature(features)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read call log: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read system call log: ${e.message}")
        }
        return result
    }

    fun delete(context: Context, entryId: String): Boolean {
        if (!entryId.startsWith("sys_")) return false
        val rowId = entryId.removePrefix("sys_").toLongOrNull() ?: return false
        return try {
            context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} = ?",
                arrayOf(rowId.toString())
            ) > 0
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to delete call log entry: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete call log entry: ${e.message}")
            false
        }
    }

    fun clear(context: Context): Boolean {
        return try {
            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null) >= 0
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to clear call log: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear call log: ${e.message}")
            false
        }
    }

    internal fun hasHdFeature(features: Int): Boolean =
        (features and CallLog.Calls.FEATURES_HD_CALL) != 0

    private fun directionForType(type: Int): CallDirection =
        if (type == CallLog.Calls.OUTGOING_TYPE) CallDirection.OUTGOING else CallDirection.INCOMING

    private fun isMissedType(type: Int): Boolean = when (type) {
        CallLog.Calls.MISSED_TYPE,
        CallLog.Calls.REJECTED_TYPE,
        CallLog.Calls.BLOCKED_TYPE,
        CallLog.Calls.VOICEMAIL_TYPE -> true
        else -> false
    }
}
