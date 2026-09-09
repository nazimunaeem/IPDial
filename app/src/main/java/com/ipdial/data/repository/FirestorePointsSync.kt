package com.ipdial.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Firestore sync helper for pro points and expiration.
 * Document path: users/{userId} (Firebase Auth UID or deviceId fallback)
 * Fields: userId, shortId (6-char prefix), name, points (number), expiration (long), referredBy (string?)
 */
class FirestorePointsSync(private val repo: AccountRepository) {

    private val firestore = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val auth = FirebaseAuth.getInstance()

    private val deviceName: String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    // Tracks the live listener so stopListening() can remove it on sign-out.
    @Volatile private var registration: ListenerRegistration? = null

    // Guards against the snapshot listener overwriting a locally optimistic points
    // value while a Firestore increment is still in flight. Incremented before the
    // write, decremented after. The snapshot listener skips updates while > 0.
    @Volatile private var pendingIncrements = 0

    // Monotonic timestamp of the most recent local points write (optimistic set in
    // grantRewardedAdPoint, transaction result, or purchase). Used by the snapshot
    // listener as a last-write-wins guard so a stale server snapshot (one generated
    // before our local write committed) can never clobber the newer local value.
    @Volatile private var lastLocalPointsWriteAt: Long = 0L

    // Fired live (on the snapshot listener thread) whenever the account's
    // authorizedDevices / pendingDevices lists change — kept null until a consumer
    // (the ViewModel) registers. Lets the UI drop the "buy a slot" row the instant
    // a pending device signs out, without waiting for a manual refresh.
    @Volatile var onDeviceSlotsChanged: ((authorized: List<String>, pending: List<String>) -> Unit)? = null

    private suspend fun getEffectiveUserId(): String? {
        return auth.currentUser?.uid
    }

    fun startListening() {
        scope.launch {
            try {
                val userId = getEffectiveUserId() ?: run {
                    Log.w("FirestorePointsSync", "startListening: userId is null, not signed in")
                    return@launch
                }
                val docRef = firestore.collection("users").document(userId)
                Log.d("FirestorePointsSync", "startListening: reading from users/$userId")

                // 1. Initial fetch to sync FROM server if data exists
                try {
                    val task = docRef.get()
                    val snapshot = com.google.android.gms.tasks.Tasks.await(task)
                    if (snapshot.exists()) {
                        val data = snapshot.data
                        val sPoints = (data?.get("points") as? Number)?.toInt()
                        val sExp = (data?.get("expiration") as? Number)?.toLong()
                        Log.d("FirestorePointsSync", "startListening: server has points=$sPoints, expiration=$sExp")

                        // Push the initial device-slot state so the UI is correct even
                        // before the continuous listener delivers its first snapshot.
                        val authorized = (data?.get("authorizedDevices") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        val pending = (data?.get("pendingDevices") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        onDeviceSlotsChanged?.invoke(authorized, pending)

                        val local = repo.proPoints.first()
                        val serverUpdatedAt = (data?.get("updatedAt") as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L
                        // Same last-write-wins guard as the snapshot listener: a slow
                        // initial fetch (e.g. started before a rewarded-ad increment) must
                        // not clobber a newer local value with a stale server read.
                        if (sPoints != null) {
                            val localWroteAfterServer = lastLocalPointsWriteAt != 0L &&
                                (serverUpdatedAt == 0L || lastLocalPointsWriteAt > serverUpdatedAt)
                            if (localWroteAfterServer) {
                                Log.d("FirestorePointsSync", "initial fetch skipped (stale: local=$local wrote at $lastLocalPointsWriteAt > server $serverUpdatedAt, server points=$sPoints)")
                            } else {
                                repo.setProPoints(maxOf(0, sPoints))
                            }
                        }
                        if (sExp != null) repo.setProExpiration(sExp)
                    } else {
                        Log.d("FirestorePointsSync", "startListening: doc users/$userId does not exist, pushing local data")
                        // Document doesn't exist, register local info
                        val currentPoints = repo.proPoints.first()
                        val currentExpiration = repo.proExpiration.first()
                        pushUpdate(currentPoints, currentExpiration)
                    }
                } catch (e: Exception) {
                    Log.e("FirestorePointsSync", "initial sync failed", e)
                }

                // 2. Continuous listening for changes
                registration?.remove()
                registration = docRef.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("FirestorePointsSync", "listen error", error)
                        return@addSnapshotListener
                    }
                    // Skip while a local increment is in flight to avoid overwriting
                    // the optimistic N+1 value with a stale server read.
                    if (pendingIncrements > 0) {
                        Log.d("FirestorePointsSync", "snapshot skipped (pending increment=$pendingIncrements)")
                        return@addSnapshotListener
                    }
                    // Acknowledged high-water mark of locally known points. Once we've
                    // successfully written (or seen the server confirm) a value, a later
                    // stale snapshot reporting a LOWER value is normally impossible unless
                    // another device redeemed/decided. But we must never let a stale read
                    // roll back a locally-awarded point before the server snapshot with the
                    // new value arrives. external = the value written to Firestore.
                    if (snapshot != null && snapshot.exists()) {
                        val data = snapshot.data ?: return@addSnapshotListener
                        val points = (data["points"] as? Number)?.toInt() ?: return@addSnapshotListener
                        val expiration = (data["expiration"] as? Number)?.toLong() ?: 0L
                        // Live device-slot state: lets the UI react instantly when
                        // another device joins (pending) or signs out (pending removed).
                        val authorized = (data["authorizedDevices"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        val pending = (data["pendingDevices"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        onDeviceSlotsChanged?.invoke(authorized, pending)
                        // push to DataStore via repo
                        scope.launch {
                            try {
                                val local = repo.proPoints.first()
                                Log.d("FirestorePointsSync", "snapshot listener: server points=$points, local=$local, pending=$pendingIncrements")
                                // Last-write-wins: if we wrote the local points more recently
                                // than this snapshot was emitted, the snapshot is stale and
                                // must not clobber our newer local value.
                                val serverUpdatedAt = (data["updatedAt"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L
                                val localWroteAfterServer = lastLocalPointsWriteAt != 0L &&
                                    (serverUpdatedAt == 0L || lastLocalPointsWriteAt > serverUpdatedAt)
                                if (localWroteAfterServer) {
                                    // The server snapshot was emitted before our most recent
                                    // local write (redeem/points increment) committed. It is
                                    // stale and must not clobber the newer local points OR pro
                                    // expiration. Confirm by awaiting the post-commit snapshot.
                                    Log.d("FirestorePointsSync", "snapshot skipped (stale: local=$local wrote at $lastLocalPointsWriteAt > server $serverUpdatedAt, server points=$points)")
                                    return@launch
                                }
                                // Never sync a negative balance to the local cache.
                                repo.setProPoints(maxOf(0, points))
                                repo.setProExpiration(expiration)
                            } catch (e: Exception) {
                                Log.e("FirestorePointsSync", "failed to write to DataStore", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FirestorePointsSync", "startListening failed", e)
            }
        }
    }

    fun stopListening() {
        registration?.remove()
        registration = null
    }

    fun incrementPoints(amount: Int) {
        // Increment the guard synchronously so there is no window where the
        // snapshot listener can overwrite our optimistic local value while the
        // Firestore write is still queued on the IO dispatcher.
        pendingIncrements++
        // Stamp the local-write watermark synchronously, BEFORE any snapshot can be
        // processed, so a stale server read (lower points, older updatedAt) can never
        // clobber the value we are about to persist optimistically.
        lastLocalPointsWriteAt = System.currentTimeMillis()
        scope.launch {
            val userId = getEffectiveUserId()
            if (userId == null) {
                pendingIncrements--
                Log.e("FirestorePointsSync", "incrementPoints: userId is null, skipping")
                return@launch
            }
            try {
                try {
                    // Optimistic local write, consistent with what the server transaction
                    // will produce. Kept inside the guard so no snapshot can race between
                    // the DataStore write and the watermark stamp.
                    val currentLocal = repo.proPoints.first()
                    repo.setProPoints(maxOf(0, currentLocal + amount))
                } catch (e: Exception) {
                    Log.w("FirestorePointsSync", "incrementPoints: optimistic local write failed", e)
                }
                val ref = firestore.collection("users").document(userId)
                Log.d("FirestorePointsSync", "incrementPoints($amount): writing to users/$userId")

                // IMPORTANT: We intentionally do NOT use FieldValue.increment() here.
                // Under the deployed security rules, a doc.update() carrying an
                // increment transform is evaluated as a different write type and is
                // rejected with PERMISSION_DENIED. Instead we read the current server
                // points in a transaction and write the absolute value back, which
                // the rules allow via the profile-write path.
                val task = firestore.runTransaction(
                    object : com.google.firebase.firestore.Transaction.Function<Int> {
                        override fun apply(transaction: com.google.firebase.firestore.Transaction): Int {
                            val snapshot = transaction.get(ref)
                            val current = if (snapshot.exists()) {
                                (snapshot.get("points") as? Number)?.toInt() ?: 0
                            } else {
                                0
                            }
                            // Floor the existing balance at 0 BEFORE adding, so a
                            // legacy negative balance (e.g. from an unclamped
                            // redemption) cannot swallow a positive award.
                            val updated = maxOf(0, maxOf(0, current) + amount)
                            transaction.set(
                                ref,
                                mapOf(
                                    "userId" to userId,
                                    "uid" to userId,
                                    "shortId" to userId.take(6),
                                    "name" to profileName,
                                    "email" to profileEmail,
                                    "points" to updated.toLong(),
                                    "expiration" to existingExpiration(snapshot),
                                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                            return updated
                        }
                    }
                )
                com.google.android.gms.tasks.Tasks.await(task)
                pendingIncrements--
                Log.d("FirestorePointsSync", "incrementPoints($amount): transaction SUCCEEDED for users/$userId")
            } catch (e: Exception) {
                pendingIncrements--
                Log.e("FirestorePointsSync", "incrementPoints($amount): Firestore transaction FAILED for users/$userId", e)
                // If the transaction fails, fall back to a full pushUpdate.
                scope.launch {
                    val currentExpiration = repo.proExpiration.first()
                    val currentPoints = repo.proPoints.first()
                    Log.d("FirestorePointsSync", "incrementPoints: falling back to pushUpdate($currentPoints, $currentExpiration)")
                    pushUpdate(currentPoints, currentExpiration)
                }
            }
        }
    }

    // Returns the existing expiration from a snapshot, or 0 if absent, so a
    // points increment does not clobber a user's granted Pro expiration.
    private fun existingExpiration(snapshot: com.google.firebase.firestore.DocumentSnapshot): Long {
        return if (snapshot.exists()) {
            (snapshot.get("expiration") as? Number)?.toLong() ?: 0L
        } else {
            0L
        }
    }

    // Signed-in Google account's display name, falling back to the device's
    // hardware name when no profile name is available.
    private val profileName: String
        get() = auth.currentUser?.displayName?.takeIf { it.isNotBlank() } ?: deviceName

    // Signed-in Google account's email (empty string when not signed in).
    private val profileEmail: String
        get() = auth.currentUser?.email ?: ""

    /**
     * Builds the account identity fields that must always be mirrored on the
     * user document. The document is keyed by the Firebase Auth UID (the "new
     * id"), so a fresh sign-in creates `users/{uid}` containing the id, the
     * account email and the user's name.
     *
     * Identity fields only. Device-slot state (deviceSlot, allowedDevices,
     * authorizedDevices) is deliberately NOT written through the generic
     * profile/points paths: those fields are managed exclusively inside the
     * device-slot transactions (ensureFreeFirstDevice / purchaseDeviceSlot),
     * which the security rules restrict based on how the slot list changes.
     * Sending them through the points/profile writes would be rejected by the
     * rules' key allow-lists.
     */
    private fun accountIdentityFields(userId: String): Map<String, Any> = mapOf(
        "userId" to userId,
        "uid" to userId,
        "shortId" to userId.take(6),
        "name" to profileName,
        "email" to profileEmail
    )

    /**
     * Generates (or retrieves) the account-bound 6-char user code and, on the
     * first sign-in of a new account, creates the `users/{newId}` document with
     * the new id, device slot number, email and user name.
     *
     * @return the account-bound 6-char code, or null on failure.
     */
    suspend fun getOrCreateUserCode(uid: String): String? = withContext(Dispatchers.IO) {
        val ref = firestore.collection("users").document(uid)
        try {
            val snapshot = com.google.android.gms.tasks.Tasks.await(ref.get())
            val existingCode = (snapshot.get("shortId") as? String)?.takeIf { it.isNotBlank() }
            val code = existingCode ?: generateUserCode()

            val fields = accountIdentityFields(uid)
            com.google.android.gms.tasks.Tasks.await(
                ref.set(
                    fields + mapOf("shortId" to code, "updatedAt" to FieldValue.serverTimestamp()),
                    com.google.firebase.firestore.SetOptions.merge()
                )
            )
            Log.d("FirestorePointsSync", "getOrCreateUserCode: upserted users/$uid with id/deviceSlot/email/name")
            code
        } catch (e: Exception) {
            Log.e("FirestorePointsSync", "getOrCreateUserCode failed", e)
            null
        }
    }

    fun pushUpdate(points: Int, expiration: Long) {
        scope.launch {
            try {
                val userId = getEffectiveUserId() ?: return@launch // No registration without Google sign-in
                val doc = firestore.collection("users").document(userId)
                val map = accountIdentityFields(userId) + mapOf(
                    "shortId" to userId.take(6),
                    "points" to points,
                    "expiration" to expiration,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                Log.d("FirestorePointsSync", "pushUpdate: writing points=$points, expiration=$expiration to users/$userId")
                doc.set(map, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener { Log.d("FirestorePointsSync", "pushUpdate SUCCEEDED: points=$points for users/$userId") }
                    .addOnFailureListener { e -> Log.e("FirestorePointsSync", "pushUpdate FAILED: points=$points for users/$userId", e) }
            } catch (e: Exception) {
                Log.e("FirestorePointsSync", "pushUpdate failed", e)
            }
        }
    }

    /**
     * Migrate device-based data to Firebase Auth UID on first sign-in.
     */
    suspend fun migrateFromDeviceId(deviceId: String, firebaseUid: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val oldDoc = firestore.collection("users").document(deviceId)
                val newDoc = firestore.collection("users").document(firebaseUid)

                val oldSnapshot = com.google.android.gms.tasks.Tasks.await(oldDoc.get())
                if (!oldSnapshot.exists()) return@withContext true // Nothing to migrate

                val data = oldSnapshot.data ?: return@withContext true

                // Copy data to new doc with updated IDs
                val migratedData = data.toMutableMap().apply {
                    put("userId", firebaseUid)
                    put("uid", firebaseUid)
                    put("shortId", firebaseUid.take(6))
                    put("name", profileName)
                    put("email", profileEmail)
                    val authorized = (data["authorizedDevices"] as? List<*>)?.filterIsInstance<String>()
                    put("deviceSlot", authorized?.size ?: 0)
                    remove("deviceId")
                }
                com.google.android.gms.tasks.Tasks.await(
                    newDoc.set(migratedData, com.google.firebase.firestore.SetOptions.merge())
                )

                // Delete old doc
                com.google.android.gms.tasks.Tasks.await(oldDoc.delete())

                Log.d("FirestorePointsSync", "Migration complete: $deviceId -> $firebaseUid")
                true
            }
        } catch (e: Exception) {
            Log.e("FirestorePointsSync", "Migration failed", e)
            false
        }
    }

    /**
     * Attempt to claim a referral code. The code is expected to be a 6-char shortId.
     * If the referral doc exists and hasn't been used by this user, we increment both parties by 50 points.
     */
    fun claimReferral(refCode: String, onComplete: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val userId = getEffectiveUserId() ?: run {
                    withContext(Dispatchers.Main) { onComplete(false, "Sign in to claim a referral") }
                    return@launch
                }
                val shortId = userId.take(6)

                if (refCode.isBlank() || refCode == shortId) {
                    withContext(Dispatchers.Main) { onComplete(false, "Invalid code") }
                    return@launch
                }

                // 1. Check if I have already claimed a referral
                val meDoc = firestore.collection("users").document(userId)
                val meSnapshot = com.google.android.gms.tasks.Tasks.await(meDoc.get())
                if (meSnapshot.exists() && meSnapshot.contains("referredBy")) {
                    withContext(Dispatchers.Main) { onComplete(false, "Referral already claimed") }
                    return@launch
                }

                // 2. Search by shortId field
                val query = firestore.collection("users")
                    .whereEqualTo("shortId", refCode)
                    .limit(1)
                    .get()
                val querySnapshot = com.google.android.gms.tasks.Tasks.await(query)

                if (querySnapshot.isEmpty) {
                    withContext(Dispatchers.Main) { onComplete(false, "Referral code not found") }
                    return@launch
                }

                val refDoc = querySnapshot.documents[0].reference

                // 3. Atomically award +50 points to both docs in a transaction and
                // write absolute values. FieldValue.increment() is rejected by the
                // security rules (evaluated as a different write type), so we read
                // both balances first and store the summed result via set+merge.
                val task = firestore.runTransaction(
                    object : com.google.firebase.firestore.Transaction.Function<Long> {
                        override fun apply(transaction: com.google.firebase.firestore.Transaction): Long {
                            val refSnap = transaction.get(refDoc)
                            val meSnap = transaction.get(meDoc)
                            val refPoints = ((refSnap.get("points") as? Number)?.toLong() ?: 0L) + 50L
                            val myPoints = ((meSnap.get("points") as? Number)?.toLong() ?: 0L) + 50L

                            transaction.set(
                                refDoc,
                                mapOf(
                                    "shortId" to refCode,
                                    "points" to refPoints,
                                    "updatedAt" to FieldValue.serverTimestamp()
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )

                            transaction.set(
                                meDoc,
                                mapOf(
                                    "shortId" to shortId,
                                    "points" to myPoints,
                                    "referredBy" to refDoc.id,
                                    "userId" to userId,
                                    "uid" to userId,
                                    "name" to profileName,
                                    "email" to profileEmail,
                                    "updatedAt" to FieldValue.serverTimestamp()
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                            return myPoints
                        }
                    }
                )
                val myPoints = com.google.android.gms.tasks.Tasks.await(task)

                // Read fresh values from server
                val updatedMe = com.google.android.gms.tasks.Tasks.await(meDoc.get())
                val points = (updatedMe.data?.get("points") as? Number)?.toInt() ?: myPoints.toInt()
                val expiration = (updatedMe.data?.get("expiration") as? Number)?.toLong() ?: 0L
                repo.setProPoints(points)
                repo.setProExpiration(expiration)

                withContext(Dispatchers.Main) { onComplete(true, "Referral applied: +50 points") }
            } catch (e: Exception) {
                Log.e("FirestorePointsSync", "claimReferral failed", e)
                withContext(Dispatchers.Main) { onComplete(false, "Error: ${e.message}") }
            }
        }
    }

    companion object {
        /** Cost in points to authorize one additional device beyond the free slot. */
        const val DEVICE_SLOT_COST = 100

        /** Characters allowed in a 6-char user code: uppercase letters + digits. */
        private const val CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        /** Generate a random 6-char uppercase alphanumeric code. */
        fun generateUserCode(): String = buildString(6) {
            repeat(6) { append(CODE_CHARS[Random.nextInt(CODE_CHARS.length)]) }
        }
    }

    /**
     * Authorizes the current device if a slot is available.
     */
    suspend fun tryAuthorizeThisDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId() ?: return@withContext false
        val ref = firestore.collection("users").document(userId)
        
        try {
            com.google.android.gms.tasks.Tasks.await(firestore.runTransaction { transaction ->
                val snapshot = transaction.get(ref)
                val allowed = (snapshot.get("allowedDevices") as? Number)?.toInt() ?: 1
                val authorized = (snapshot.get("authorizedDevices") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                
                if (authorized.contains(deviceId)) return@runTransaction true
                if (authorized.size < allowed) {
                    transaction.update(ref, "authorizedDevices", FieldValue.arrayUnion(deviceId))
                    return@runTransaction true
                }
                false
            })
            true
        } catch (e: Exception) {
            Log.e("FirestorePointsSync", "tryAuthorizeThisDevice failed", e)
            false
        }
    }

    /**
     * Returns how many devices are currently whitelisted for this account.
     * 0 when not signed in, the doc is missing, or no devices are authorized.
     */
    suspend fun getAuthorizedDeviceCount(): Int = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId() ?: return@withContext 0
        try {
            val snapshot = com.google.android.gms.tasks.Tasks.await(
                firestore.collection("users").document(userId).get()
            )
            if (!snapshot.exists()) return@withContext 0
            ((snapshot.get("authorizedDevices") as? List<*>)?.size ?: 0).coerceAtLeast(0)
        } catch (e: Exception) {
            Log.e("FirestorePointsSync", "getAuthorizedDeviceCount failed", e)
            0
        }
    }

    /**
     * Returns how many devices have requested (but not yet obtained) a slot for
     * this account. Used to show the "buy a device slot" row on the authorized
     * device(s) whenever another device wants access.
     */
    suspend fun getPendingDeviceCount(): Int = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId() ?: return@withContext 0
        try {
            val snapshot = com.google.android.gms.tasks.Tasks.await(
                firestore.collection("users").document(userId).get()
            )
            if (!snapshot.exists()) return@withContext 0
            ((snapshot.get("pendingDevices") as? List<*>)?.size ?: 0).coerceAtLeast(0)
        } catch (e: Exception) {
            Log.e("FirestorePointsSync", "getPendingDeviceCount failed", e)
            0
        }
    }

    /**
     * Returns true if [deviceId] is already whitelisted on the signed-in account.
     */
    suspend fun isDeviceAuthorized(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val userId = getEffectiveUserId() ?: return@withContext false
        try {
            val snapshot = com.google.android.gms.tasks.Tasks.await(
                firestore.collection("users").document(userId).get()
            )
            if (!snapshot.exists()) return@withContext false
            (snapshot.get("authorizedDevices") as? List<*>)?.map { it.toString() }?.contains(deviceId) ?: false
        } catch (e: Exception) {
            Log.e("FirestorePointsSync", "isDeviceAuthorized failed", e)
            false
        }
    }

    /**
     * Claims the single free first device slot for `deviceId` if the account has
     * not yet used it. Atomic (transaction). Returns true if this device is
     * authorized afterwards (already authorized, or free claim succeeded).
     */
    suspend fun ensureFreeFirstDevice(deviceId: String): Boolean {
        val userId = getEffectiveUserId() ?: return false
        return try {
            withContext(Dispatchers.IO) {
                val task = firestore.runTransaction(
                    object : com.google.firebase.firestore.Transaction.Function<Boolean> {
                        override fun apply(transaction: com.google.firebase.firestore.Transaction): Boolean {
                            val ref = firestore.collection("users").document(userId)
                            val snapshot = transaction.get(ref)
                            val existing = if (snapshot.exists()) {
                                (snapshot.get("authorizedDevices") as? List<*>)?.map { it.toString() } ?: emptyList()
                            } else {
                                emptyList()
                            }
                            if (existing.contains(deviceId)) {
                                // Already authorized; make sure it is not pending anymore.
                                transaction.update(ref, "pendingDevices", FieldValue.arrayRemove(deviceId))
                                return true
                            }
                            val freeUsed = if (snapshot.exists()) {
                                (snapshot.get("freeSlotsUsed") as? Number)?.toInt() ?: 0
                            } else {
                                0
                            }
                            if (freeUsed > 0) {
                                // Free slot already consumed -> this device is NOT authorized.
                                // Record it as pending so all the account's devices (including the
                                // authorized one) know another device wants a slot.
                                transaction.update(ref, "pendingDevices", FieldValue.arrayUnion(deviceId))
                                return false
                            }
                            val newList = existing + deviceId
                            transaction.set(
                                ref,
                                mapOf(
                                    "authorizedDevices" to newList,
                                    "freeSlotsUsed" to 1,
                                    "pendingDevices" to FieldValue.arrayRemove(deviceId),
                                    // Slot counts are written here (and in
                                    // purchaseDeviceSlot) because the generic
                                    // profile/points writes are not allowed to
                                    // touch these fields by the security rules.
                                    "deviceSlot" to newList.size,
                                    "allowedDevices" to 1,
                                    "updatedAt" to FieldValue.serverTimestamp()
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                            return true
                        }
                    }
                )
                com.google.android.gms.tasks.Tasks.await(task)
            }
        } catch (e: Exception) {
            Log.e("FirestorePointsSync", "ensureFreeFirstDevice failed", e)
            false
        }
    }

    /**
     * Purchase a device slot for `deviceId` at [DEVICE_SLOT_COST] points. Atomic
     * transaction: aborts unless the account has >= cost points and the device is
     * not already authorized; otherwise appends the device and deducts exactly
     * [DEVICE_SLOT_COST] points.
     *
     * @return a [SlotPurchaseResult] with the remaining points on success and a
     *         human-readable reason on failure (no more blind "could not buy").
     */
    suspend fun purchaseDeviceSlot(deviceId: String): SlotPurchaseResult {
        val userId = getEffectiveUserId()
            ?: return SlotPurchaseResult(false, message = "Sign in to buy a device slot")
        return try {
            withContext(Dispatchers.IO) {
                val task = firestore.runTransaction(
                    object : com.google.firebase.firestore.Transaction.Function<Long> {
                        override fun apply(transaction: com.google.firebase.firestore.Transaction): Long {
                            val ref = firestore.collection("users").document(userId)
                            val snapshot = transaction.get(ref)
                            if (!snapshot.exists()) {
                                throw com.google.firebase.firestore.FirebaseFirestoreException(
                                    "Your account record doesn't exist in Firestore yet. Sign out and sign in again.",
                                    com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED
                                )
                            }
                            val existing = (snapshot.get("authorizedDevices") as? List<*>)?.map { it.toString() } ?: emptyList()
                            val allowedSoFar = (snapshot.get("allowedDevices") as? Number)?.toInt() ?: 1
                            if (existing.contains(deviceId)) {
                                // Already authorized; no charge needed.
                                transaction.update(ref, "pendingDevices", FieldValue.arrayRemove(deviceId))
                                return (snapshot.get("points") as? Number)?.toLong() ?: 0L
                            }
                            val points = (snapshot.get("points") as? Number)?.toLong() ?: 0L
                            if (points < DEVICE_SLOT_COST) {
                                throw com.google.firebase.firestore.FirebaseFirestoreException(
                                    "Not enough points (you have $points, need $DEVICE_SLOT_COST)",
                                    com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED
                                )
                            }
                            val remaining = points - DEVICE_SLOT_COST
                            // IMPORTANT: write the absolute points value via set+merge.
                            // FieldValue.increment() is evaluated as a different write
                            // type by the security rules and rejected with
                            // PERMISSION_DENIED (see incrementPoints()). The device-slot
                            // keys are allowed only through this transaction path.
                            transaction.set(
                                ref,
                                accountIdentityFields(userId) + mapOf(
                                    "authorizedDevices" to (existing + deviceId),
                                    "points" to remaining,
                                    "pendingDevices" to FieldValue.arrayRemove(deviceId),
                                    "deviceSlot" to existing.size + 1,
                                    "allowedDevices" to (allowedSoFar + 1).coerceAtLeast(existing.size + 1),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                            return remaining
                        }
                    }
                )
                val remaining = com.google.android.gms.tasks.Tasks.await(task)
                // Reflect the new points locally after a successful purchase.
                repo.setProPoints(remaining.toInt())
                // Stamp the watermark so a stale (pre-deduction) snapshot cannot roll
                // the balance back up.
                lastLocalPointsWriteAt = System.currentTimeMillis()
                SlotPurchaseResult(true, remainingPoints = remaining.toInt(), message = "Device authorized")
            }
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            Log.w("FirestorePointsSync", "purchaseDeviceSlot failed", e)
            val reason = when (e.code) {
                com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "Permission denied — Firestore rules need to be deployed"
                else -> e.message ?: "Purchase failed"
            }
            SlotPurchaseResult(false, message = reason)
        } catch (e: Exception) {
            Log.w("FirestorePointsSync", "purchaseDeviceSlot failed", e)
            SlotPurchaseResult(false, message = e.message ?: "Purchase failed")
        }
    }

    /**
     * Removes `deviceId` from the account's pendingDevices list. Called when a
     * device signs out without ever buying a slot, so the remaining authorized
     * device(s) stop showing the "buy a slot" row immediately. [userId] is passed
     * in explicitly because sign-out clears the auth user right after this fires,
     * so we can't rely on getEffectiveUserId() inside the coroutine.
     */
    fun removePendingDevice(userId: String, deviceId: String) {
        if (userId.isBlank() || deviceId.isBlank()) return
        scope.launch {
            try {
                val ref = firestore.collection("users").document(userId)
                Log.d("FirestorePointsSync", "removePendingDevice: removing $deviceId for users/$userId")
                com.google.android.gms.tasks.Tasks.await(
                    ref.update(
                        "pendingDevices", FieldValue.arrayRemove(deviceId),
                        "updatedAt", FieldValue.serverTimestamp()
                    )
                )
            } catch (e: Exception) {
                Log.w("FirestorePointsSync", "removePendingDevice failed", e)
            }
        }
    }

    fun redeemPoints(cost: Int, newExpiration: Long) {
        // Stamp the local-write watermark synchronously, before the async server
        // transaction, so a stale server snapshot (fired before the redeem commits)
        // can never clobber the locally-increased pro expiration. The confirm snapshot
        // (written after commit) carries a newer updatedAt and still applies.
        lastLocalPointsWriteAt = System.currentTimeMillis()
        scope.launch {
            try {
                val userId = getEffectiveUserId() ?: return@launch // No redemption without Google sign-in
                val doc = firestore.collection("users").document(userId)
                Log.d("FirestorePointsSync", "redeemPoints: cost=$cost, newExpiration=$newExpiration for users/$userId")
                // Read-modify-write in a transaction so the balance is clamped at 0
                // and can never go negative server-side.
                val task = firestore.runTransaction(
                    object : com.google.firebase.firestore.Transaction.Function<Int> {
                        override fun apply(transaction: com.google.firebase.firestore.Transaction): Int {
                            val snapshot = transaction.get(doc)
                            val current = if (snapshot.exists()) {
                                (snapshot.get("points") as? Number)?.toInt() ?: 0
                            } else {
                                0
                            }
                            val updated = maxOf(0, current - cost)
                            transaction.set(
                                doc,
                                mapOf(
                                    "points" to updated.toLong(),
                                    "expiration" to newExpiration,
                                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                            return updated
                        }
                    }
                )
                val remaining = com.google.android.gms.tasks.Tasks.await(task)
                // Sync the confirmed server values back to local DataStore so the UI
                // immediately reflects the new points balance and pro expiration,
                // matching the pattern used by purchaseDeviceSlot().
                repo.setProPoints(remaining.toInt())
                repo.setProExpiration(newExpiration)
                // Stamp the watermark so a stale (pre-redeem) snapshot cannot roll
                // the balance back up.
                lastLocalPointsWriteAt = System.currentTimeMillis()
                Log.d("FirestorePointsSync", "redeemPoints SUCCEEDED: cost=$cost, remaining=$remaining for users/$userId")
            } catch (e: Exception) {
                Log.e("FirestorePointsSync", "redeemPoints failed", e)
            }
        }
    }

    /**
     * Update the pro expiration only (e.g. rewarded ad granting free Pro days).
     * No points are deducted or added.
     */
    fun updateExpiration(newExpiration: Long) {
        // Stamp the local-write watermark too so a stale snapshot cannot overwrite
        // the locally-extended pro expiration.
        lastLocalPointsWriteAt = System.currentTimeMillis()
        scope.launch {
            try {
                val userId = getEffectiveUserId() ?: return@launch // No registration without Google sign-in
                val doc = firestore.collection("users").document(userId)
                doc.update(
                    "expiration", newExpiration,
                    "updatedAt", FieldValue.serverTimestamp()
                )
            } catch (e: Exception) {
                Log.e("FirestorePointsSync", "updateExpiration failed", e)
            }
        }
    }
}

/**
 * Outcome of a device-slot purchase. [success] is false when the device could
 * not be authorized; [message] then carries the exact reason (e.g. not enough
 * points, permission denied) instead of a generic failure.
 */
data class SlotPurchaseResult(
    val success: Boolean,
    val remainingPoints: Int? = null,
    val message: String? = null
)
