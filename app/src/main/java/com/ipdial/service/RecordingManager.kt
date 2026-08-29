package com.ipdial.service

import android.content.Context
import com.ipdial.data.model.CallSession
import java.io.File

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
        // Millisecond timestamp + short random suffix guarantees uniqueness
        // without exposing the dialed number in the filename (privacy).
        val name = "IPDial_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID().toString().take(4) + ".wav"
        return File(folder, name)
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
