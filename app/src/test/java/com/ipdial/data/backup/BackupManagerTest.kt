package com.ipdial.data.backup

import com.google.gson.JsonObject
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.CallTransport
import com.ipdial.data.model.PreferredCodec
import com.ipdial.data.model.SipAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupManagerTest {

    @Test
    fun localFolderBackup_writesSeparateJsonFilesAndRecordingsDirectly_andUpdatesIncrementally() {
        val backupDir = Files.createTempDirectory("ipdial_folder_backup_").toFile().apply { deleteOnExit() }
        val target = BackupManager.LocalDirTarget(backupDir)

        val settings = JsonObject().apply {
            addProperty("themeMode", "Obsidian")
            addProperty("autoRecordEnabled", true)
        }
        val accounts = listOf(
            SipAccount(
                id = "acc-1",
                label = "Work",
                username = "1001",
                password = "secret",
                domain = "sip.example.com",
                enabledCodecs = setOf(PreferredCodec.G722, PreferredCodec.G711A)
            )
        )
        val call1 = CallLogEntry(
            id = "c-1",
            remoteUri = "+8801712345678",
            direction = CallDirection.OUTGOING,
            durationSeconds = 90L,
            timestampMs = 1_700_000_000_000L,
            transport = CallTransport.SIM,
            isHdAudio = true
        )
        val rec1 = File.createTempFile("call_rec_1_", ".m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            deleteOnExit()
        }
        val rec2 = File.createTempFile("call_rec_2_", ".m4a").apply {
            writeBytes(byteArrayOf(5, 6, 7, 8, 9))
            deleteOnExit()
        }

        val options = BackupOptions(true, true, true, true)

        // 1. Initial backup writes separate JSONs and rec1 directly into backupDir
        val r1 = BackupManager.syncIncrementalToFolder(
            target = target,
            options = options,
            appVersion = "2.0.0",
            settingsJson = settings,
            accounts = accounts,
            callHistory = listOf(call1),
            recordings = listOf(rec1)
        )
        assertTrue(r1.success)
        assertFalse(r1.skippedNoChanges)
        assertTrue(File(backupDir, "settings.json").isFile)
        assertTrue(File(backupDir, "sip_accounts.json").isFile)
        assertTrue(File(backupDir, "call_history.json").isFile)
        assertTrue(File(backupDir, "manifest.json").isFile)
        assertTrue(File(backupDir, rec1.name).isFile)
        assertEquals(1, r1.recordingsCount)

        // 2. Identical second run skips completely (no new changes)
        val r2 = BackupManager.syncIncrementalToFolder(
            target = target,
            options = options,
            appVersion = "2.0.0",
            settingsJson = settings,
            accounts = accounts,
            callHistory = listOf(call1),
            recordings = listOf(rec1)
        )
        assertTrue(r2.success)
        assertTrue(r2.skippedNoChanges)

        // 3. Add 1 new call & 1 new recording -> updates only history & recordings, preserves rec1
        val call2 = CallLogEntry(
            id = "c-2",
            remoteUri = "1002",
            direction = CallDirection.INCOMING,
            durationSeconds = 20L,
            timestampMs = 1_700_000_100_000L,
            transport = CallTransport.SIP,
            isHdAudio = false
        )
        val r3 = BackupManager.syncIncrementalToFolder(
            target = target,
            options = options,
            appVersion = "2.0.0",
            settingsJson = settings,
            accounts = accounts,
            callHistory = listOf(call2),
            recordings = listOf(rec2)
        )
        assertTrue(r3.success)
        assertFalse(r3.skippedNoChanges)
        assertEquals(1, r3.newCallsCount)
        assertEquals(1, r3.newRecordingsCount)
        assertEquals(2, r3.callHistoryCount)
        assertEquals(2, r3.recordingsCount)
        assertTrue("history" in r3.updatedSections)
        assertTrue("recordings" in r3.updatedSections)
        assertFalse("settings" in r3.updatedSections)
        assertFalse("accounts" in r3.updatedSections)
        assertTrue(File(backupDir, rec1.name).isFile)
        assertTrue(File(backupDir, rec2.name).isFile)

        val preview = BackupManager.inspectFolder(target)
        assertNotNull(preview)
        assertTrue(preview!!.hasSettings)
        assertEquals(1, preview.sipAccountsCount)
        assertEquals(2, preview.callHistoryCount)
        assertEquals(2, preview.recordingsCount)
    }

    @Test
    fun localFolderBackup_respectsSelectedCheckboxes() {
        val partialDir = Files.createTempDirectory("ipdial_partial_backup_").toFile().apply { deleteOnExit() }
        val target = BackupManager.LocalDirTarget(partialDir)

        val call = CallLogEntry(
            id = "c-10",
            remoteUri = "5551234",
            direction = CallDirection.OUTGOING,
            durationSeconds = 12L,
            transport = CallTransport.SIP
        )
        val rec = File.createTempFile("rec_skip_", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }

        val res = BackupManager.syncIncrementalToFolder(
            target = target,
            options = BackupOptions(
                includeSettings = false,
                includeSipAccounts = false,
                includeCallHistory = true,
                includeRecordings = false
            ),
            appVersion = "2.0.0",
            settingsJson = JsonObject().apply { addProperty("a", 1) },
            accounts = listOf(SipAccount(id = "1", username = "u", domain = "d")),
            callHistory = listOf(call),
            recordings = listOf(rec)
        )
        assertTrue(res.success)
        assertFalse(File(partialDir, "settings.json").exists())
        assertFalse(File(partialDir, "sip_accounts.json").exists())
        assertTrue(File(partialDir, "call_history.json").isFile)
        assertFalse(File(partialDir, rec.name).exists())

        val preview = BackupManager.inspectFolder(target)
        assertNotNull(preview)
        assertFalse(preview!!.hasSettings)
        assertEquals(0, preview.sipAccountsCount)
        assertEquals(1, preview.callHistoryCount)
        assertEquals(0, preview.recordingsCount)
    }

    @Test
    fun sanitizeRecordingName_preventsTraversal_andDetectsAudioExtensions() {
        assertNull(BackupManager.sanitizeRecordingName(".."))
        assertNull(BackupManager.sanitizeRecordingName("."))
        assertNull(BackupManager.sanitizeRecordingName(""))
        assertEquals("clip.wav", BackupManager.sanitizeRecordingName("../../evil/clip.wav"))
        assertEquals("call_123.m4a", BackupManager.sanitizeRecordingName("call_123.m4a"))

        assertTrue(BackupManager.isRecordingFileName("call_001.m4a"))
        assertTrue(BackupManager.isRecordingFileName("call_002.WAV"))
        assertFalse(BackupManager.isRecordingFileName("settings.json"))
        assertFalse(BackupManager.isRecordingFileName("manifest.json"))
    }

    @Test
    fun backupFrequencyAndOptions_haveExpectedIntervalsAndSelectionCheck() {
        assertEquals(1L, BackupFrequency.HOURLY.intervalHours)
        assertEquals(24L, BackupFrequency.DAILY.intervalHours)
        assertEquals(168L, BackupFrequency.WEEKLY.intervalHours)
        assertEquals(720L, BackupFrequency.MONTHLY.intervalHours)
        assertEquals(BackupFrequency.MONTHLY, BackupFrequency.fromNameOrDefault("monthly"))
        assertEquals(BackupFrequency.DAILY, BackupFrequency.fromNameOrDefault("unknown"))

        assertFalse(
            BackupOptions(
                includeSettings = false,
                includeSipAccounts = false,
                includeCallHistory = false,
                includeRecordings = false
            ).hasAnySelected()
        )
        assertTrue(BackupOptions(includeSettings = false, includeSipAccounts = true, includeCallHistory = false, includeRecordings = false).hasAnySelected())
    }
}
