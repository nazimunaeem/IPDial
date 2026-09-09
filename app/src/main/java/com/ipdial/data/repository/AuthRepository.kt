package com.ipdial.data.repository

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

class AuthRepository(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(context)

    private val webClientId: String = context.getString(com.ipdial.R.string.default_web_client_id)

    private val _currentUser = MutableStateFlow(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser

    val isSignedIn: Boolean get() = auth.currentUser != null
    val userId: String? get() = auth.currentUser?.uid
    val userName: String? get() = auth.currentUser?.displayName
    val userEmail: String? get() = auth.currentUser?.email
    val userPhotoUrl: String? get() = auth.currentUser?.photoUrl?.toString()

    val referralCode: String get() = auth.currentUser?.uid?.take(6) ?: ""

    /**
     * Attempt to sign in using Credential Manager.
     * Returns Result.success(idToken) on success.
     * @param activityContext Must be an Activity-based context (required by Credential Manager).
     */
    suspend fun signIn(activityContext: Context): Result<String> {
        // GetSignInWithGoogleOption is the current, supported Google sign-in API.
        // The legacy GetGoogleIdOption (with account filtering) is deprecated and
        // silently stops offering Google on newer Play Services builds, which is
        // why Google sign-in disappeared on some Android 15+ devices (e.g. OnePlus).
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(webClientId)
                    .build()
            )
            .build()

        return try {
            val result = credentialManager.getCredential(
                request = request,
                context = activityContext
            )
            extractIdToken(result)
        } catch (e: GetCredentialException) {
            val msg = e.message ?: ""
            // GMS on OnePlus (OxygenOS) throws code 16 "Account reauth failed" on
            // the first sign-in attempt — the internal re-auth step fails transiently.
            // Retry once after a short delay.
            if (msg.contains("16", ignoreCase = false) || msg.contains("Account reauth", ignoreCase = true)) {
                Log.w("AuthRepository", "GMS reauth error (transient) — retrying once", e)
                kotlinx.coroutines.delay(800)
                try {
                    val retry = credentialManager.getCredential(request = request, context = activityContext)
                    extractIdToken(retry)
                } catch (e2: GetCredentialException) {
                    Result.failure(IllegalStateException("Google sign-in failed — please close and reopen the app, then try again"))
                }
            } else {
                Log.e("AuthRepository", "Google sign-in request failed", e)
                Result.failure(e)
            }
        }
    }

    private fun extractIdToken(result: androidx.credentials.GetCredentialResponse): Result<String> {
        val credential = result.credential
        if (credential is androidx.credentials.CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                if (idToken.isNullOrBlank()) {
                    Log.e("AuthRepository", "Google ID token is null/blank after account selection — this is usually caused by an incompatible OAuth client ID (webClientId) or a missing SHA-1 fingerprint in the Firebase/Google Cloud console.")
                    Result.failure(IllegalStateException("Google returned an empty ID token"))
                } else {
                    Result.success(idToken)
                }
            } catch (e: GoogleIdTokenParsingException) {
                Log.e("AuthRepository", "Failed to parse Google ID token", e)
                Result.failure(e)
            }
        }
        return Result.failure(IllegalStateException("Unexpected credential type: ${credential.type}"))
    }

    suspend fun firebaseAuthWithGoogle(idToken: String): Result<Unit> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).await()
            _currentUser.value = auth.currentUser
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AuthRepository", "firebaseAuthWithGoogle failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        auth.signOut()
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: ClearCredentialException) {
            Log.e("AuthRepository", "Failed to clear credential state", e)
        }
        _currentUser.value = null
    }

    suspend fun deleteAccount(activityContext: Context): Result<Unit> {
        return try {
            // Firebase delete() requires a recent login. If the session is stale,
            // re-authenticate via Credential Manager first, then retry.
            try {
                auth.currentUser?.delete()?.await()
            } catch (e: FirebaseAuthRecentLoginRequiredException) {
                val reauthenticated = reauthenticate(activityContext)
                if (reauthenticated.isFailure) return Result.failure(e)
                auth.currentUser?.delete()?.await()
            }
            auth.signOut()
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (_: ClearCredentialException) {}
            _currentUser.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fallback for callers without an Activity context — reauth is skipped. */
    suspend fun deleteAccount(): Result<Unit> {
        return try {
            try {
                auth.currentUser?.delete()?.await()
            } catch (e: FirebaseAuthRecentLoginRequiredException) {
                return Result.failure(IllegalStateException("Re-authentication required — please try again from the profile screen"))
            }
            auth.signOut()
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (_: ClearCredentialException) {}
            _currentUser.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Re-authenticate the currently signed-in user via Credential Manager using a
     * fresh Google ID token (required to unblock sensitive operations such as
     * account deletion after the session becomes stale).
     */
    suspend fun reauthenticate(activityContext: Context): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
        if (user.providerData.none { it.providerId == GoogleAuthProvider.PROVIDER_ID }) {
            // Not a Google account — nothing we can re-authenticate via Google.
            return Result.failure(IllegalStateException("Account is not linked to Google"))
        }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(webClientId).build()
            )
            .build()
        return try {
            val result = credentialManager.getCredential(request = request, context = activityContext)
            val tokenResult = extractIdToken(result)
            if (tokenResult.isFailure) return tokenResult.map { Unit }
            val idToken = tokenResult.getOrNull() ?: return Result.failure(IllegalStateException("No ID token"))
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            user.reauthenticate(credential).await()
            _currentUser.value = auth.currentUser
            Result.success(Unit)
        } catch (e: GetCredentialException) {
            Log.e("AuthRepository", "Re-authentication failed", e)
            Result.failure(e)
        }
    }
}
