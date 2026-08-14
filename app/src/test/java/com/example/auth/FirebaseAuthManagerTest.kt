package com.example.auth

import android.content.Context
import androidx.credentials.CredentialManager
import com.example.R
import com.google.firebase.auth.FirebaseAuth
import androidx.credentials.GetCredentialRequest
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.Runs
import io.mockk.just
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirebaseAuthManagerTest {

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
        every { context.getString(R.string.default_web_client_id) } returns "   "
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
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
        every { context.getString(R.string.default_web_client_id) } returns "configured-client-id"
        coEvery {
            credentialManager.getCredential(any(), any<GetCredentialRequest>())
        } throws IllegalStateException("credential lookup failed")
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertEquals("credential lookup failed", result.exceptionOrNull()?.message)
        verify(exactly = 0) { auth.signInWithCredential(any()) }
    }
}
