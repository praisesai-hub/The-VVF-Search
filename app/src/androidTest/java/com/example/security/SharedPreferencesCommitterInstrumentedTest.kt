package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesCommitterInstrumentedTest {
    @Test
    fun commit_appliesRemovalsAndStringUpdatesSynchronously(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(
            "shared-preferences-committer-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        assertTrue(
            preferences.edit()
                .putString("remove-me", "old")
                .putString("keep-me", "before")
                .commit(),
        )

        val committed = SharedPreferencesCommitter.commit(
            preferences,
            linkedMapOf("keep-me" to "after", "new-value" to "created"),
            listOf("remove-me"),
        )

        assertTrue(committed)
        assertFalse(preferences.contains("remove-me"))
        assertEquals("after", preferences.getString("keep-me", null))
        assertEquals("created", preferences.getString("new-value", null))
        preferences.edit().clear().commit()
    }
}
