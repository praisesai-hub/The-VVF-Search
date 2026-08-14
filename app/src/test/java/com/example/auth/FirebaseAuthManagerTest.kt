package com.example.auth

import android.content.Context
import androidx.credentials.CredentialManager
import com.example.R
import com.google.firebase.auth.FirebaseAuth
import io.mockk.every
import io.mockk.mockk
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
}
