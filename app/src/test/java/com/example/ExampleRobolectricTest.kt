package com.example

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
class ExampleRobolectricTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testPhysicalStorageManagerFileOperations() {
        val testFile = tempFolder.newFile("test_document.txt")
        testFile.writeText("Hello, World!")
        
        assertTrue(testFile.exists())
        assertTrue(testFile.length() > 0)
    }
}
