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
 * [ContactsContract.CommonDataKinds.Phone.PHOTO_URI] or
 * [ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI].
 * This helper pulls the provider's full-resolution "display photo" at call time,
 * falling back to thumbnail stream or [fallbackUri] if not available.
 */
object ContactPhotoUtil {

    private const val TAG = "ContactPhotoUtil"

    /**
     * Returns a loadable content/file Uri of the contact's hi-res photo, or [fallbackUri].
     * [contactId] is the provider's Contacts._ID (the app's [com.ipdial.data.model.Contact.id]).
     */
    fun resolveFullScreenUri(context: Context, contactId: String, fallbackUri: Uri? = null): Uri? {
        val id = contactId.toLongOrNull() ?: return fallbackUri
        val resolver = context.contentResolver
        val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)

        // 1. Fast path: a photo file id means the provider keeps a dedicated display photo.
        try {
            resolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts.PHOTO_FILE_ID),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val fileIdCol = c.getColumnIndex(ContactsContract.Contacts.PHOTO_FILE_ID)
                    if (fileIdCol >= 0 && !c.isNull(fileIdCol)) {
                        val fileId = c.getLong(fileIdCol)
                        if (fileId > 0) {
                            return ContentUris.withAppendedId(
                                ContactsContract.DisplayPhoto.CONTENT_URI,
                                fileId
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "PHOTO_FILE_ID query failed: ${e.message}")
        }

        // 2. Stream path: ask the provider to decode the photo.
        // Try preferHighres = true first, then preferHighres = false if null.
        try {
            var stream = ContactsContract.Contacts.openContactPhotoInputStream(
                resolver,
                contactUri,
                true
            )
            if (stream == null) {
                stream = ContactsContract.Contacts.openContactPhotoInputStream(
                    resolver,
                    contactUri,
                    false
                )
            }
            if (stream != null) {
                val file = File(context.cacheDir, "fullscreen_photos").apply { mkdirs() }
                val target = File(file, "$id.jpg")
                stream.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output, 8192)
                    }
                }
                return target.toUri()
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Contact photo stream unavailable: ${e.message}")
        }

        // 3. Fallback to existing contact photoUri if available
        return fallbackUri
    }
}
