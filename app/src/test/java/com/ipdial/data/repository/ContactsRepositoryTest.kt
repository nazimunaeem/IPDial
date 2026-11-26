package com.ipdial.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsRepositoryTest {

    @Test
    fun classifyExternalMimeType_detectsVideoCalls() {
        assertEquals(
            "Video Call",
            ContactsRepository.classifyExternalMimeType(
                mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.video.call",
                summary = "Video call +15551234567"
            )
        )
        assertEquals(
            "Video Call",
            ContactsRepository.classifyExternalMimeType(
                mimeType = "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call.video"
            )
        )
    }

    @Test
    fun classifyExternalMimeType_detectsVoiceAndVoipCalls() {
        assertEquals(
            "Voice / Video Call",
            ContactsRepository.classifyExternalMimeType(
                mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
            )
        )
        assertEquals(
            "Voice / Video Call",
            ContactsRepository.classifyExternalMimeType(
                mimeType = "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call"
            )
        )
        assertEquals(
            "Voice / Video Call",
            ContactsRepository.classifyExternalMimeType(
                mimeType = "vnd.android.cursor.item/com.google.android.apps.tachyon.phone"
            )
        )
    }

    @Test
    fun classifyExternalMimeType_detectsMessagesAndFallback() {
        assertEquals(
            "Message",
            ContactsRepository.classifyExternalMimeType(
                mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
            )
        )
        assertEquals(
            "Open in app",
            ContactsRepository.classifyExternalMimeType(
                mimeType = "vnd.android.cursor.item/vnd.example.custom"
            )
        )
    }

    @Test
    fun actionPriority_ordersCallsBeforeMessages() {
        val callPriority = ContactsRepository.actionPriority("Voice / Video Call")
        val videoPriority = ContactsRepository.actionPriority("Video Call")
        val msgPriority = ContactsRepository.actionPriority("Message")
        val fallbackPriority = ContactsRepository.actionPriority("Open in app")

        assertTrue(callPriority < videoPriority)
        assertTrue(videoPriority < msgPriority)
        assertTrue(msgPriority < fallbackPriority)
    }
}
