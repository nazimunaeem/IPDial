package com.ipdial.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.net.toUri
import java.io.File

/**
 * Resolves a contact's high-resolution photo for full-screen display.
 *
 * The photos synced into the contact index come from
 * [ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI] (~96px thumbnails),
 * which are far too small to stretch across the whole call screen. This helper
 * pulls the provider's full-resolution "display photo" at call time instead.
 *
 * It returns `null` (-> the caller keeps the normal circular avatar) whenever no
 * hi-res photo exists or READ_CONTACTS is missing, so the feature degrades safely.
 */
object ContactPhotoUtil {

    private const val TAG = "ContactPhotoUtil"

    /**
     * Returns a loadable content/file Uri of the contact's hi-res photo, or null.
     * [contactId] is the provider's Contacts._ID (the app's [com.ipdial.data.model.Contact.id]).
     */
    fun resolveFullScreenUri(context: Context, contactId: String): Uri? {
        val id = contactId.toLongOrNull() ?: return null
        val resolver = context.contentResolver
        val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)

        // Fast path: a photo file id means the provider keeps a dedicated display
        // photo. It is served through DisplayPhoto.CONTENT_URI at a higher
        // resolution than the thumbnail, and Coil can load it directly.
        try {
            resolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts.PHOTO_FILE_ID),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val fileId = c.getInt(0)
                    if (fileId > 0) {
                        return ContentUris.withAppendedId(
                            ContactsContract.DisplayPhoto.CONTENT_URI,
                            fileId.toLong()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "PHOTO_FILE_ID query failed: ${e.message}")
        }

        // Fallback: ask the provider to decode the highest available version of
        // the photo for us. It arrives as a stream, so cache it to a file that
        // Coil (and gliding) can load from, reusing it across calls.
        return try {
            val stream = ContactsContract.Contacts.openContactPhotoInputStream(
                resolver,
                contactUri,
                true
            ) ?: return null

            val file = File(context.cacheDir, "fullscreen_photos").apply { mkdirs() }
            val target = File(file, "$id.jpg")
            stream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, 8192)
                }
            }
            target.toUri()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Hi-res contact photo unavailable: ${e.message}")
            null
        }
    }
}