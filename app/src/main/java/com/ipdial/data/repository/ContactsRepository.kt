package com.ipdial.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.ContactsContract
import com.ipdial.data.local.AppDatabase
import com.ipdial.data.local.ContactEntity
import com.ipdial.data.model.Contact
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ContactPhoneNumber(
    val number: String,
    val label: String
)

data class ExternalAppAction(
    val dataId: Long,
    val mimeType: String,
    val packageName: String,
    val appLabel: String,
    val actionLabel: String,
    val summary: String,
    val icon: Drawable?
)

data class ContactDetailInfo(
    val contact: Contact,
    val phones: List<ContactPhoneNumber>,
    val externalActions: List<ExternalAppAction>
)

class ContactsRepository(private val context: Context) {

    private val contactDao = AppDatabase.getDatabase(context).contactDao()

    val allContacts: Flow<List<Contact>> = contactDao.getAllContacts().map { entities ->
        entities.map { it.toContact() }
    }

    val favoriteContacts: Flow<List<Contact>> = contactDao.getFavoriteContacts().map { entities ->
        entities.map { it.toContact() }
    }

    init {
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                buildNumberIndex()
            }
        } catch (_: Throwable) {}
    }

    companion object {
        // Pre-computed shared index for O(1) contact lookup across Service and ViewModel
        @Volatile
        private var normalizedNumberIndex: Map<String, Contact> = emptyMap()
        @Volatile
        private var cachedContactsList: List<Contact> = emptyList()
        @Volatile
        private var indexBuilt = false
        private val syncMutex = Mutex()

        internal fun classifyExternalMimeType(mimeType: String, summary: String = ""): String {
            val lower = mimeType.lowercase()
            val summaryLower = summary.lowercase()
            return when {
                lower.contains("video") || summaryLower.contains("video") -> "Video Call"
                lower.contains("voip") || lower.contains(".call") || lower.contains("voice") ||
                    lower.contains("tachyon") || lower.contains("meetings") -> "Voice / Video Call"
                lower.contains("profile") || lower.contains("message") || lower.contains("chat") -> "Message"
                else -> "Open in app"
            }
        }

        internal fun actionPriority(actionLabel: String): Int = when (actionLabel) {
            "Voice / Video Call" -> 0
            "Video Call" -> 1
            "Message" -> 2
            else -> 3
        }
    }

    /**
     * Build a lookup index from normalized (digits-only) phone numbers to Contact.
     * Stores multiple suffix lengths so that local numbers (e.g. 01729979896)
     * match international format (+8801729979896) and vice versa.
     */
    suspend fun buildNumberIndex() = withContext(Dispatchers.IO) {
        val contacts = contactDao.getAllContacts().first().map { it.toContact() }
        cachedContactsList = contacts
        val index = mutableMapOf<String, Contact>()
        for (contact in contacts) {
            for (number in contact.numbers) {
                val digits = number.filter { it.isDigit() }
                if (digits.length >= 3) {
                    index[digits] = contact
                    if (digits.length >= 10) index[digits.takeLast(10)] = contact
                    if (digits.length >= 8) index[digits.takeLast(8)] = contact
                    if (digits.length >= 6) index[digits.takeLast(6)] = contact
                }
            }
        }
        normalizedNumberIndex = index
        indexBuilt = true
    }

    /**
     * Fast contact lookup by phone number. Tries exact full digits, suffix lengths
     * (10, 8, 6 digits), and fallback iteration for format differences.
     */
    fun findContactByNumber(phoneNumber: String, fallbackContacts: List<Contact>? = null): Contact? {
        val cleanNum = com.ipdial.ui.screens.cleanUri(phoneNumber)
        val digits = cleanNum.filter { it.isDigit() }

        // 1. Try fast index lookup
        if (digits.isNotBlank()) {
            normalizedNumberIndex[digits]?.let { return it }
            if (digits.length >= 10) normalizedNumberIndex[digits.takeLast(10)]?.let { return it }
            if (digits.length >= 8) normalizedNumberIndex[digits.takeLast(8)]?.let { return it }
            if (digits.length >= 6) normalizedNumberIndex[digits.takeLast(6)]?.let { return it }
        }

        // 2. Fallback to searching the provided list or cached contacts
        val pool = fallbackContacts ?: cachedContactsList
        if (pool.isNotEmpty()) {
            // First pass: exact match on number or digits
            for (c in pool) {
                for (num in c.numbers) {
                    val cClean = com.ipdial.ui.screens.cleanUri(num)
                    val cDigits = cClean.filter { it.isDigit() }
                    if (cClean == cleanNum || (digits.isNotBlank() && cDigits == digits)) {
                        return c
                    }
                }
            }
            // Second pass: suffix matching
            if (digits.length >= 6) {
                val searchSuffix = digits.takeLast(minOf(digits.length, 10))
                for (c in pool) {
                    for (num in c.numbers) {
                        val cDigits = num.filter { it.isDigit() }
                        if (cDigits.length >= 6) {
                            val cSuffix = cDigits.takeLast(minOf(cDigits.length, 10))
                            if (searchSuffix == cSuffix || cDigits.endsWith(digits) || digits.endsWith(cDigits)) {
                                return c
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * Fallback lookup directly against Android ContactsContract.PhoneLookup
     * in case Room sync has not completed yet or a new contact was just saved.
     */
    suspend fun lookupDeviceContactByNumber(phoneNumber: String): Contact? = withContext(Dispatchers.IO) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext null
        }
        val cleanNum = com.ipdial.ui.screens.cleanUri(phoneNumber)
        if (cleanNum.isBlank()) return@withContext null
        try {
            val lookupUri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(cleanNum)
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup.CONTACT_ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.NUMBER,
                ContactsContract.PhoneLookup.PHOTO_URI,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
            )
            context.contentResolver.query(lookupUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.CONTACT_ID)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.NUMBER)
                    val photoIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)
                    val thumbIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI)

                    val id = if (idIdx >= 0) cursor.getString(idIdx) ?: cleanNum else cleanNum
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: cleanNum else cleanNum
                    val num = if (numIdx >= 0) cursor.getString(numIdx) ?: cleanNum else cleanNum
                    val photoStr = (if (photoIdx >= 0) cursor.getString(photoIdx) else null)
                        ?: (if (thumbIdx >= 0) cursor.getString(thumbIdx) else null)
                    return@withContext Contact(
                        id = id,
                        name = name,
                        numbers = listOf(num),
                        photoUri = photoStr?.toUri(),
                        isFavorite = false
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ContactsRepo", "PhoneLookup failed for $cleanNum: ${e.message}")
        }
        null
    }

    /**
     * Check if the number index is built and ready for fast lookups.
     */
    fun isIndexReady(): Boolean = indexBuilt
    suspend fun syncContacts() = withContext(Dispatchers.IO) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return@withContext
        }

        syncMutex.withLock {
            // Favorites are stored locally (Room) and are the source of truth.
            // Preserve them across refreshes so they never get lost on sync.
            val existingFavorites = contactDao.getFavoriteIds().toHashSet()
            val cachedEntities = contactDao.getContactsOnce().associateBy { it.id }
            val isFirstSync = cachedEntities.isEmpty()

            val contactsMap = mutableMapOf<String, Contact>()
            try {
                val contentResolver: ContentResolver = context.contentResolver
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                    ContactsContract.CommonDataKinds.Phone.STARRED
                )

                val cursor: Cursor? = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )

                cursor?.use {
                    val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val photoUriIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                    val photoThumbIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                    val starredIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)

                    while (it.moveToNext()) {
                        val id = it.getString(idIndex)
                        val name = it.getString(nameIndex) ?: "Unknown"
                        val number = it.getString(numberIndex) ?: ""
                        val photoUriStr = (if (photoUriIndex >= 0) it.getString(photoUriIndex) else null)
                            ?: (if (photoThumbIndex >= 0) it.getString(photoThumbIndex) else null)
                        // Local flag wins once cached; only seed from the device's
                        // STARRED flag on the very first sync.
                        val isFavorite = id in existingFavorites ||
                            (isFirstSync && it.getInt(starredIndex) == 1)

                        val photoUri = photoUriStr?.toUri()
                        val existingContact = contactsMap[id]
                        if (existingContact != null) {
                            val updatedPhoto = existingContact.photoUri ?: photoUri
                            if (number.isNotBlank()) {
                                val normalizedNew = number.filter { it.isDigit() }
                                val alreadyHas = existingContact.numbers.any { existingNum ->
                                    existingNum.filter { char -> char.isDigit() } == normalizedNew
                                }
                                val updatedNumbers = if (!alreadyHas) existingContact.numbers + number else existingContact.numbers
                                contactsMap[id] = existingContact.copy(numbers = updatedNumbers, photoUri = updatedPhoto)
                            } else if (updatedPhoto != existingContact.photoUri) {
                                contactsMap[id] = existingContact.copy(photoUri = updatedPhoto)
                            }
                        } else if (number.isNotBlank()) {
                            contactsMap[id] = Contact(id, name, listOf(number), photoUri, isFavorite)
                        }
                    }
                }

                val providerIds = contactsMap.keys

                // Favorites that no longer exist in the device provider are kept
                // cached locally so the user doesn't lose their favorites.
                val orphanedFavorites = existingFavorites
                    .filterNot { it in providerIds }
                    .mapNotNull { cachedEntities[it] }
                    .filter { it.isFavorite }

                val entities = (contactsMap.values.map { ContactEntity.fromContact(it) } + orphanedFavorites)
                    .distinctBy { it.id }
                contactDao.refreshContacts(entities)
                buildNumberIndex()

            } catch (e: Exception) {
                android.util.Log.e("ContactsRepo", "Failed to sync contacts: ${e.message}")
            }
        }
    }

    suspend fun getContacts(query: String? = null): List<Contact> = withContext(Dispatchers.IO) {
        // Read from the local cache (Room) instead of querying the provider,
        // to avoid loading from the contacts provider on every call/search.
        var entities = if (query.isNullOrBlank()) {
            contactDao.getAllContacts().first()
        } else {
            contactDao.searchContacts("%$query%").first()
        }
        if (entities.isEmpty() && query.isNullOrBlank()) {
            // Cold cache — populate once from the provider so lookups
            // (e.g. incoming call name resolution) still work on first run.
            syncContacts()
            entities = contactDao.getAllContacts().first()
        }
        entities.map { it.toContact() }
    }

    suspend fun toggleFavorite(contactId: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        // Local DB is the source of truth so favorites survive refreshes.
        contactDao.updateFavorite(contactId, isFavorite)
        // Best-effort mirror to the device contacts provider (WRITE_CONTACTS).
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CONTACTS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val values = android.content.ContentValues().apply {
                    put(ContactsContract.Contacts.STARRED, if (isFavorite) 1 else 0)
                }
                context.contentResolver.update(
                    ContactsContract.Contacts.CONTENT_URI,
                    values,
                    ContactsContract.Contacts._ID + " = ?",
                    arrayOf(contactId)
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("ContactsRepo", "Failed to mirror favorite to device: ${e.message}")
        }
    }

    suspend fun loadContactDetails(contact: Contact): ContactDetailInfo = withContext(Dispatchers.IO) {
        val fallbackPhones = contact.numbers.map { ContactPhoneNumber(number = it, label = "Phone") }
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext ContactDetailInfo(contact, fallbackPhones, emptyList())
        }

        val phones = mutableListOf<ContactPhoneNumber>()
        val seenDigits = HashSet<String>()
        try {
            val phoneProjection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneProjection,
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contact.id),
                null
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (cursor.moveToNext()) {
                    val number = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    if (number.isBlank()) continue
                    val digits = number.filter { it.isDigit() || it == '+' }
                    if (digits.isNotEmpty() && !seenDigits.add(digits)) continue
                    val type = if (typeIdx >= 0) cursor.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    val customLabel = if (labelIdx >= 0) cursor.getString(labelIdx) else null
                    val label = ContactsContract.CommonDataKinds.Phone
                        .getTypeLabel(context.resources, type, customLabel)
                        ?.toString()
                        ?.takeIf { it.isNotBlank() } ?: "Phone"
                    phones.add(ContactPhoneNumber(number = number, label = label))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ContactsRepo", "Phone query failed for ${contact.id}: ${e.message}")
        }

        val resolvedPhones = if (phones.isNotEmpty()) phones else fallbackPhones

        val externalActions = mutableListOf<ExternalAppAction>()
        val seenActions = HashSet<String>()
        val ignoredMimeTypes = setOf(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Identity.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE
        )

        try {
            val pm = context.packageManager
            val dataProjection = arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA3
            )
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                dataProjection,
                "${ContactsContract.Data.CONTACT_ID} = ?",
                arrayOf(contact.id),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Data._ID)
                val mimeIdx = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                val data1Idx = cursor.getColumnIndex(ContactsContract.Data.DATA1)
                val data3Idx = cursor.getColumnIndex(ContactsContract.Data.DATA3)

                while (cursor.moveToNext()) {
                    val dataId = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    val mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) else continue
                    if (mimeType.isNullOrBlank() || mimeType in ignoredMimeTypes) continue

                    val dataUri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(dataUri, mimeType)
                    }

                    val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.queryIntentActivities(
                            intent,
                            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    }

                    val resolveInfo = matches.firstOrNull { it.activityInfo != null } ?: continue
                    val pkgName = resolveInfo.activityInfo.packageName ?: continue
                    val key = "$pkgName|$mimeType"
                    if (!seenActions.add(key)) continue

                    val appLabel = resolveInfo.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkgName
                    val rawSummary = if (data3Idx >= 0) cursor.getString(data3Idx) else null
                    val rawData1 = if (data1Idx >= 0) cursor.getString(data1Idx) else null
                    val summary = rawSummary?.takeIf { it.isNotBlank() }
                        ?: rawData1?.takeIf { it.isNotBlank() }
                        ?: ""
                    val actionLabel = classifyExternalMimeType(mimeType, summary)
                    val icon = runCatching { pm.getApplicationIcon(pkgName) }.getOrNull()
                        ?: runCatching { resolveInfo.loadIcon(pm) }.getOrNull()

                    externalActions.add(
                        ExternalAppAction(
                            dataId = dataId,
                            mimeType = mimeType,
                            packageName = pkgName,
                            appLabel = appLabel,
                            actionLabel = actionLabel,
                            summary = summary,
                            icon = icon
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ContactsRepo", "External app action query failed for ${contact.id}: ${e.message}")
        }

        val sortedActions = externalActions.sortedWith(
            compareBy<ExternalAppAction>(
                { actionPriority(it.actionLabel) },
                { it.appLabel.lowercase() },
                { it.actionLabel.lowercase() }
            )
        )

        ContactDetailInfo(
            contact = contact,
            phones = resolvedPhones,
            externalActions = sortedActions
        )
    }

    suspend fun deleteContact(contactId: String): Boolean = withContext(Dispatchers.IO) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext false
        }
        val idLong = contactId.toLongOrNull() ?: return@withContext false
        return@withContext try {
            val baseUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, idLong)
            val lookupUri = ContactsContract.Contacts.getLookupUri(context.contentResolver, baseUri) ?: baseUri
            val deleted = context.contentResolver.delete(lookupUri, null, null)
            val removed = deleted > 0 || context.contentResolver.delete(baseUri, null, null) > 0
            if (removed) {
                contactDao.deleteById(contactId)
                buildNumberIndex()
            }
            removed
        } catch (e: Exception) {
            android.util.Log.e("ContactsRepo", "Failed to delete contact $contactId: ${e.message}")
            false
        }
    }
}
