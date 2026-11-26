package com.ipdial.data

import android.provider.CallLog
import com.ipdial.data.local.CallLogEntity
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.CallTransport
import com.ipdial.data.model.CodecInfo
import com.ipdial.data.model.CodecProfile
import com.ipdial.data.model.CodecQuality
import com.ipdial.data.model.DEFAULT_ENABLED_CODECS
import com.ipdial.data.model.PreferredCodec
import com.ipdial.data.model.SipAccount
import com.ipdial.data.model.withSanitizedCodecs
import com.ipdial.data.repository.SystemCallLogRepository
import com.ipdial.ui.mergeCallLogEntries
import com.ipdial.ui.screens.checklistCodecs
import com.ipdial.ui.screens.hasUniversalFallback
import com.ipdial.ui.screens.matchCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogHdAndCodecChecklistTest {

    @Test
    fun callLogEntity_roundTripPreservesHdAudioFlag() {
        val hdEntry = CallLogEntry(
            id = "hd-1",
            accountId = "acc-1",
            remoteUri = "+8801712345678",
            remoteDisplayName = "Alice",
            direction = CallDirection.OUTGOING,
            missed = false,
            timestampMs = 1_700_000_000_000L,
            durationSeconds = 42L,
            transport = CallTransport.SIM,
            lineLabel = "SIM 1",
            isHdAudio = true
        )
        val nonHdEntry = hdEntry.copy(id = "std-2", isHdAudio = false)

        val roundTripHd = CallLogEntity.fromCallLogEntry(hdEntry).toCallLogEntry()
        val roundTripNonHd = CallLogEntity.fromCallLogEntry(nonHdEntry).toCallLogEntry()

        assertTrue(roundTripHd.isHdAudio)
        assertFalse(roundTripNonHd.isHdAudio)
    }

    @Test
    fun systemCallLog_hasHdFeature_detectsHdBitmask() {
        assertTrue(SystemCallLogRepository.hasHdFeature(CallLog.Calls.FEATURES_HD_CALL))
        assertTrue(
            SystemCallLogRepository.hasHdFeature(
                CallLog.Calls.FEATURES_HD_CALL or CallLog.Calls.FEATURES_WIFI
            )
        )
        assertFalse(SystemCallLogRepository.hasHdFeature(0))
        assertFalse(SystemCallLogRepository.hasHdFeature(CallLog.Calls.FEATURES_WIFI))
    }

    @Test
    fun mergeCallLogEntries_propagatesHdFromSystemLogOntoMatchingAppEntry() {
        val t0 = 1_700_000_100_000L
        val appEntries = listOf(
            CallLogEntry(
                id = "app-sim-1",
                remoteUri = "01712345678",
                direction = CallDirection.OUTGOING,
                timestampMs = t0,
                durationSeconds = 30L,
                transport = CallTransport.SIM,
                isHdAudio = false
            )
        )
        val deviceEntries = listOf(
            CallLogEntry(
                id = "sys_99",
                remoteUri = "+8801712345678",
                direction = CallDirection.OUTGOING,
                timestampMs = t0 + 2_000L,
                durationSeconds = 30L,
                transport = CallTransport.SIM,
                isHdAudio = true
            ),
            CallLogEntry(
                id = "sys_100",
                remoteUri = "+8801911111111",
                direction = CallDirection.INCOMING,
                timestampMs = t0 - 60_000L,
                durationSeconds = 12L,
                transport = CallTransport.SIM,
                isHdAudio = true
            )
        )

        val merged = mergeCallLogEntries(appEntries, deviceEntries)

        assertEquals(2, merged.size)
        assertEquals("app-sim-1", merged[0].id)
        assertTrue(merged[0].isHdAudio)
        assertEquals("sys_100", merged[1].id)
        assertTrue(merged[1].isHdAudio)
    }

    @Test
    fun checklistCodecs_includesAllReadyCodecsIncludingGsmAndAnyReadyExtraCodec() {
        val engineCodecs = listOf(
            codecInfo("G722/16000/1", ready = true),
            codecInfo("PCMA/8000/1", ready = true),
            codecInfo("PCMU/8000/1", ready = true),
            codecInfo("G729/8000/1", ready = true),
            codecInfo("GSM/8000/1", ready = true),
            codecInfo("speex/16000/1", ready = true),
            codecInfo("iLBC/8000/1", ready = false)
        )

        val shown = checklistCodecs(engineCodecs, DEFAULT_ENABLED_CODECS)

        assertTrue(shown.contains(PreferredCodec.G711U))
        assertTrue(shown.contains(PreferredCodec.G711A))
        assertTrue(shown.contains(PreferredCodec.G722))
        assertTrue(shown.contains(PreferredCodec.G729))
        assertTrue(shown.contains(PreferredCodec.GSM))
        assertTrue(shown.contains(PreferredCodec.Speex))
        assertFalse(shown.contains(PreferredCodec.ILBC))
    }

    @Test
    fun checklistCodecs_keepsUncheckedDefaultCodecVisibleSoUserCanReEnable() {
        val engineCodecs = listOf(
            codecInfo("G722/16000/1", ready = true),
            codecInfo("PCMA/8000/1", ready = true),
            codecInfo("PCMU/8000/1", ready = true),
            codecInfo("G729/8000/1", ready = false),
            codecInfo("GSM/8000/1", ready = false)
        )

        val shown = checklistCodecs(engineCodecs, setOf(PreferredCodec.G711A, PreferredCodec.G711U, PreferredCodec.G722))

        assertTrue(shown.contains(PreferredCodec.G729))
        assertTrue(shown.contains(PreferredCodec.GSM))
        assertFalse(matchCodec(PreferredCodec.G722, "G7221/16000/1"))
    }

    @Test
    fun allCodecProfiles_includeUniversalFallbackForReliableCalling() {
        CodecProfile.entries.forEach { profile ->
            assertTrue(
                "Profile ${profile.name} must include a universal fallback codec",
                hasUniversalFallback(profile.enabled)
            )
            profile.preferred?.let { pref ->
                assertTrue(
                    "Profile ${profile.name} preferred codec must be in enabled set",
                    pref in profile.enabled
                )
            }
        }
    }

    @Test
    fun sanitizeCodecs_recoversFromEmptyOrRemovedLegacyEntries() {
        @Suppress("UNCHECKED_CAST")
        val legacySetWithNull = setOf<PreferredCodec?>(null) as Set<PreferredCodec>
        val migrated = SipAccount(codec = null, enabledCodecs = legacySetWithNull).withSanitizedCodecs()

        assertEquals(DEFAULT_ENABLED_CODECS, migrated.enabledCodecs)
        assertEquals(null, migrated.codec)
        assertTrue(hasUniversalFallback(migrated.enabledCodecs))
    }

    private fun codecInfo(id: String, ready: Boolean) = CodecInfo(
        id = id,
        name = id,
        priority = if (ready) 140 else 0,
        isAvailable = ready,
        quality = CodecQuality.Fair,
        clockRate = 8000,
        channelCount = 1,
        frameLength = 20
    )
}
