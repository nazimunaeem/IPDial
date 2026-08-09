package com.ipdial.service

import android.content.Context
import com.ipdial.data.model.CallSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared helper for call recording so the UI (SipViewModel) and the foreground
 * service (SipService auto-record) produce recordings in the same place/format.
 */
object RecordingManager {

    fun recordingsFolder(context: Context): File {
        val baseDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        return File(baseDir, "IPDialRecordings").apply {
            if (!exists()) mkdirs()
        }
    }

    fun nextRecordingFile(context: Context, session: CallSession): File {
        val folder = recordingsFolder(context)
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        val dateStr = sdf.format(Date())
        val num = session.remoteUri
            .replace("<", "")
            .replace(">", "")
            .removePrefix("sip:")
            .substringBefore("@")
            .substringBefore(";")
        val cleanNum = num.filter { it.isLetterOrDigit() || it == '+' }
        return File(folder, "IPDial_${cleanNum}_${dateStr}.wav")
    }

    fun startRecording(context: Context, session: CallSession) {
        try {
            val recFile = nextRecordingFile(context, session)
            SipAudioController.startRecording(recFile.absolutePath)
        } catch (e: Exception) {
            android.util.Log.e("RecordingManager", "Failed to start recording: ${e.message}", e)
        }
    }

    fun stopRecording() {
        try {
            SipAudioController.stopRecording()
        } catch (e: Exception) {
            android.util.Log.e("RecordingManager", "Failed to stop recording: ${e.message}", e)
        }
    }
}
