package com.ipdial.data.repository

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
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
     * First tries authorized accounts (auto sign-in), then falls back to all accounts.
     * Returns Result.success(idToken) on success.
     * @param activityContext Must be an Activity-based context (required by Credential Manager).
     */
    suspend fun signIn(activityContext: Context): Result<String> {
        // Step 1: Try authorized accounts (previously signed in users)
        val authorizedRequest = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(true)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(true)
                    .build()
            )
            .build()

        return try {
            val result = credentialManager.getCredential(
                request = authorizedRequest,
                context = activityContext
            )
            extractIdToken(result)
        } catch (e: NoCredentialException) {
            // Step 2: No authorized accounts, show all Google accounts on device
            signInWithAllAccounts(activityContext)
        } catch (e: GetCredentialException) {
            Log.e("AuthRepository", "Credential request failed", e)
            Result.failure(e)
        }
    }

    private suspend fun signInWithAllAccounts(activityContext: Context): Result<String> {
        val allAccountsRequest = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .build()
            )
            .build()

        return try {
            val result = credentialManager.getCredential(
                request = allAccountsRequest,
                context = activityContext
            )
            extractIdToken(result)
        } catch (e: GetCredentialException) {
            Log.e("AuthRepository", "All accounts request failed", e)
            Result.failure(e)
        }
    }

    private fun extractIdToken(result: androidx.credentials.GetCredentialResponse): Result<String> {
        val credential = result.credential
        if (credential is androidx.credentials.CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                Result.success(googleIdTokenCredential.idToken)
            } catch (e: GoogleIdTokenParsingException) {
                Log.e("AuthRepository", "Failed to parse Google ID token", e)
                Result.failure(e)
            }
        }
        return Result.failure(IllegalStateException("Unexpected credential type"))
    }

    suspend fun firebaseAuthWithGoogle(idToken: String): Result<Unit> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).await()
            _currentUser.value = auth.currentUser
            Result.success(Unit)
        } catch (e: Exception) {
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

    suspend fun deleteAccount(): Result<Unit> {
        return try {
            auth.currentUser?.delete()?.await()
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
}
