package com.example.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.firebase.auth.FirebaseAuth
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseAuthManagerPlainJvmTest {
    private val context: Context = mockk(relaxed = true)
    private val auth: FirebaseAuth = mockk(relaxed = true)
    private val credentialManager: CredentialManager = mockk(relaxed = true)

    @Test
    fun signOut_clearsPublishedUser_andDisposesListener() {
        every { auth.currentUser } returns null
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        manager.signOut()
        manager.dispose()

        assertEquals(null, manager.user.value)
        verify(exactly = 1) { auth.signOut() }
        verify(exactly = 1) { auth.removeAuthStateListener(any()) }
    }

    @Test
    fun authStateListener_publishesCurrentFirebaseUser() {
        val listener = slot<FirebaseAuth.AuthStateListener>()
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { auth.currentUser } returns null
        every { auth.addAuthStateListener(capture(listener)) } just Runs
        val manager = FirebaseAuthManager(context, auth, credentialManager)
        every { auth.currentUser } returns user

        listener.captured.onAuthStateChanged(auth)

        assertSame(user, manager.user.value)
        manager.dispose()
    }

    @Test
    fun signInWithGoogle_failsBeforeCredentialLookupWhenClientIdMissing() = runBlocking {
        every { context.getString(com.example.R.string.default_web_client_id) } returns "  "
        val manager = FirebaseAuthManager(context, auth, credentialManager)

        val result = manager.signInWithGoogle()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("not configured"))
        coVerify(exactly = 0) {
            credentialManager.getCredential(any<Context>(), any<GetCredentialRequest>())
        }
        manager.dispose()
    }
}
