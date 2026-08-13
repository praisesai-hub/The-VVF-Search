package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.example.worker.CacheCleanupWorker
import java.util.concurrent.TimeUnit

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.MainViewModel
import com.example.ui.VVFSmartManagerApp
import com.example.ui.theme.VVFSmartManagerTheme

class MainActivity : FragmentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    private val openStorageTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let(mainViewModel::processPickedDirectoryUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val cacheCleanupRequest = PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(CacheCleanupWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, cacheCleanupRequest)


        setContent {
            VVFSmartManagerTheme {
                VVFSmartManagerApp(viewModel = mainViewModel)
            }
        }
    }

    /** Opens the Android Storage Access Framework folder picker. */
    fun pickStorageFolder() {
        openStorageTree.launch(null)
    }
}

// Retained for Screenshot Test compatibility
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
