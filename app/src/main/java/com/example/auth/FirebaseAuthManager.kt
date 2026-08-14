package com.example.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.OAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Security-first authentication boundary.
 *
 * OAuth credentials and Firebase ID tokens are never copied into app preferences.
 * FirebaseAuth owns the authenticated session and its token lifecycle.
 */
class FirebaseAuthManager(
    private val context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val credentialManager: CredentialManager = CredentialManager.create(context)
) {
    private val _user = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val user: StateFlow<FirebaseUser?> = _user.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        _user.value = firebaseAuth.currentUser
    }

    init {
        auth.addAuthStateListener(authStateListener)
    }

    /**
     * Converts recoverable Credential Manager and Firebase failures into the caller-visible
     * Result boundary. Coroutine cancellation is deliberately rethrown and must never be
     * mistaken for a failed authentication attempt.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun signInWithGoogle(): Result<FirebaseUser> = try {
        val webClientId = context.getString(R.string.default_web_client_id)
            .trim()
            .takeIf(String::isNotBlank)
            ?: error("Google OAuth is not configured: missing default_web_client_id")

        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(context, request)
        val credential = result.credential
        require(credential is androidx.credentials.CustomCredential) {
            "Unsupported Google credential type"
        }
        require(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unsupported Google credential type"
        }

        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
        val idToken = googleCredential.idToken
        require(idToken.isNotBlank()) { "Google returned an empty ID token" }

        val firebaseCredential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
        val authenticatedUser = auth.signInWithCredential(firebaseCredential).await().user
            ?: error("Firebase did not return an authenticated user")
        Result.success(authenticatedUser)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: NoCredentialException) {
        // This is an expected user/device state, not an unclassified authentication failure.
        Result.failure(exception)
    } catch (exception: Exception) {
        Result.failure(exception)
    }

    fun signInWithMicrosoft(activity: Activity): com.google.android.gms.tasks.Task<FirebaseUser> {
        val provider = OAuthProvider.newBuilder("microsoft.com", auth)
            .setScopes(listOf("openid", "profile", "email"))
            .build()

        return auth.startActivityForSignInWithProvider(activity, provider)
            .continueWith { task ->
                if (!task.isSuccessful) {
                    throw (task.exception ?: FirebaseAuthException("ERROR_MICROSOFT_SIGN_IN", "Microsoft sign-in failed"))
                }
                task.result?.user ?: error("Firebase did not return an authenticated user")
            }
    }

    fun signOut() {
        auth.signOut()
        _user.value = null
    }

    fun dispose() {
        auth.removeAuthStateListener(authStateListener)
    }
}
