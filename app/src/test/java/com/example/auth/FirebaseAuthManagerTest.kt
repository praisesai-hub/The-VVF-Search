package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.os.Looper
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirebaseAuthManagerTest {

    private lateinit var context: Context
    private lateinit var resources: Resources
    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        resources = mockk(relaxed = true)
        auth = mockk(relaxed = true)
        credentialManager = mockk(relaxed = true)
        every { context.resources } returns resources
        every { context.packageName } returns "com.example"
        every { auth.currentUser } returns null
    }

    private fun configureWebClientId(value: String) {
        every { resources.getIdentifier("default_web_client_id", "string", "com.example") } returns 123
        every { context.getString(123) } returns value
    }

    @Test
    fun signOut_delegatesToFirebaseAndClearsPublishedUser() {
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        manager.signOut()

        assertEquals(null, manager.user.value)
        verify(exactly = 1) { auth.signOut() }
    }

    @Test
    fun dispose_removesTheRegisteredAuthStateListener() {
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        manager.dispose()

        verify(exactly = 1) { auth.removeAuthStateListener(any()) }
    }

    @Test
    fun signInWithGoogle_failsClosedWhenOAuthConfigurationIsMissing() = runBlocking {
        every { resources.getIdentifier("default_web_client_id", "string", "com.example") } returns 0
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not configured"))
        verify(exactly = 0) { auth.signInWithCredential(any()) }
    }

    @Test
    fun signInWithGoogle_failsClosedWhenGeneratedOAuthResourceIsBlank() = runBlocking {
        configureWebClientId("   ")
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not configured"))
        verify(exactly = 0) { auth.signInWithCredential(any()) }
    }

    @Test
    fun authStateListener_publishesTheCurrentFirebaseUser() {
        val listener = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listener)) } just Runs
        val authenticatedUser = mockk<FirebaseUser>()
        every { auth.currentUser } returns null
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        every { auth.currentUser } returns authenticatedUser
        listener.captured.onAuthStateChanged(auth)

        assertEquals(authenticatedUser, manager.user.value)
    }

    @Test
    fun signInWithGoogle_returnsFailureWhenCredentialManagerThrows() = runBlocking {
        configureWebClientId("configured-client-id")
        coEvery {
            credentialManager.getCredential(any(), any<GetCredentialRequest>())
        } throws IllegalStateException("credential lookup failed")
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertEquals("credential lookup failed", result.exceptionOrNull()?.message)
        verify(exactly = 0) { auth.signInWithCredential(any()) }
    }

    @Test
    fun signInWithGoogle_rejectsCredentialWithUnexpectedType() = runBlocking {
        configureWebClientId("configured-client-id")
        val credential = CustomCredential("com.example.unexpected", Bundle())
        coEvery {
            credentialManager.getCredential(any(), any<GetCredentialRequest>())
        } returns GetCredentialResponse(credential)
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertEquals("Unsupported Google credential type", result.exceptionOrNull()?.message)
        verify(exactly = 0) { auth.signInWithCredential(any()) }
    }

    @Test
    fun signInWithGoogle_preservesExpectedNoCredentialFailure() = runBlocking {
        configureWebClientId("configured-client-id")
        val noCredential = NoCredentialException("no credential available")
        coEvery {
            credentialManager.getCredential(any(), any<GetCredentialRequest>())
        } throws noCredential
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertSame(noCredential, result.exceptionOrNull())
        verify(exactly = 0) { auth.signInWithCredential(any()) }
    }

    @Test
    fun signInWithGoogle_rethrowsCancellationInsteadOfConvertingItToFailure() {
        configureWebClientId("configured-client-id")
        val cancellation = CancellationException("authentication cancelled")
        coEvery {
            credentialManager.getCredential(any(), any<GetCredentialRequest>())
        } throws cancellation
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        try {
            runBlocking { manager.signInWithGoogle() }
            fail("CancellationException should be rethrown")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun signInWithMicrosoft_returnsTheAuthenticatedUserOnSuccess() {
        val expectedUser = mockk<FirebaseUser>()
        val authResult = mockk<AuthResult> {
            every { user } returns expectedUser
        }
        every {
            auth.startActivityForSignInWithProvider(any(), any())
        } returns Tasks.forResult(authResult)
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithMicrosoft(mockk<Activity>(relaxed = true))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(result.isSuccessful)
        assertSame(expectedUser, result.result)
    }

    @Test
    fun signInWithMicrosoft_surfacesProviderFailure() {
        val failure = IllegalStateException("Microsoft provider failed")
        every {
            auth.startActivityForSignInWithProvider(any(), any())
        } returns Tasks.forException(failure)
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithMicrosoft(mockk<Activity>(relaxed = true))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(result.isSuccessful)
        assertEquals("Microsoft provider failed", result.exception?.message)
    }
}
