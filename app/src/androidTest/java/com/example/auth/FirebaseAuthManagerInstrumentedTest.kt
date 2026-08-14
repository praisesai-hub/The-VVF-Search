package com.example.auth

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.R
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseAuthManagerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        auth = mockk(relaxed = true)
        credentialManager = mockk(relaxed = true)
        every { auth.currentUser } returns null
    }

    @Test
    fun signOut_clearsPublishedUserAndDelegatesToFirebase() {
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        manager.signOut()

        assertEquals(null, manager.user.value)
        verify(exactly = 1) { auth.signOut() }
    }

    @Test
    fun dispose_removesAuthStateListener() {
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        manager.dispose()

        verify(exactly = 1) { auth.removeAuthStateListener(any()) }
    }

    @Test
    fun authStateListener_publishesCurrentFirebaseUser() {
        val listener = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listener)) } just Runs
        val expectedUser = mockk<FirebaseUser>()
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        every { auth.currentUser } returns expectedUser
        listener.captured.onAuthStateChanged(auth)

        assertSame(expectedUser, manager.user.value)
    }

    @Test
    fun signInWithGoogle_failsClosedWhenConfigurationMissing() = runBlocking {
        every { context.getString(R.string.default_web_client_id) } returns "  "
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not configured"))
        verify(exactly = 0) { auth.signInWithCredential(any()) }
    }

    @Test
    fun signInWithGoogle_convertsCredentialManagerFailureToResult() = runBlocking {
        every { context.getString(R.string.default_web_client_id) } returns "configured-client-id"
        coEvery {
            credentialManager.getCredential(any(), any<GetCredentialRequest>())
        } throws IllegalStateException("credential lookup failed")
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertEquals("credential lookup failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun signInWithGoogle_preservesNoCredentialFailure() = runBlocking {
        every { context.getString(R.string.default_web_client_id) } returns "configured-client-id"
        val expected = NoCredentialException("no credential")
        coEvery {
            credentialManager.getCredential(any(), any<GetCredentialRequest>())
        } throws expected
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertSame(expected, result.exceptionOrNull())
    }

    @Test
    fun signInWithGoogle_rethrowsCancellation() {
        every { context.getString(R.string.default_web_client_id) } returns "configured-client-id"
        val expected = CancellationException("cancelled")
        coEvery {
            credentialManager.getCredential(any(), any<GetCredentialRequest>())
        } throws expected
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        try {
            runBlocking { manager.signInWithGoogle() }
            assertTrue("CancellationException should be rethrown", false)
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun signInWithGoogle_rejectsUnsupportedCredentialType() = runBlocking {
        every { context.getString(R.string.default_web_client_id) } returns "configured-client-id"
        val credential = CustomCredential("com.example.unexpected", Bundle())
        coEvery {
            credentialManager.getCredential(any(), any<GetCredentialRequest>())
        } returns GetCredentialResponse(credential)
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertEquals("Unsupported Google credential type", result.exceptionOrNull()?.message)
    }

    @Test
    fun signInWithMicrosoft_returnsAuthenticatedUser() {
        val expectedUser = mockk<FirebaseUser>()
        val authResult = mockk<AuthResult> {
            every { user } returns expectedUser
        }
        every {
            auth.startActivityForSignInWithProvider(any<Activity>(), any())
        } returns Tasks.forResult(authResult)
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val actualUser = Tasks.await(manager.signInWithMicrosoft(mockk(relaxed = true)))

        assertSame(expectedUser, actualUser)
    }

    @Test
    fun signInWithMicrosoft_surfacesProviderFailure() {
        val failure = IllegalStateException("Microsoft provider failed")
        every {
            auth.startActivityForSignInWithProvider(any<Activity>(), any())
        } returns Tasks.forException(failure)
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val task = manager.signInWithMicrosoft(mockk(relaxed = true))

        assertFalse(task.isSuccessful)
        assertEquals("Microsoft provider failed", task.exception?.message)
    }
}
