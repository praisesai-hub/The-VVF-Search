// VVF Smart Manager Application - Phase 6 Step 7 Complete
package com.example

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.AppDatabase
import com.example.data.SmartManagerRepository
import com.example.worker.BackgroundIndexWorker
import com.example.worker.CacheCleanupWorker
import com.example.worker.CloudSyncWorker
import com.example.worker.DuplicateCleanupWorker
import java.util.concurrent.TimeUnit

import androidx.work.Configuration

class VVFApplication : Application(), Configuration.Provider {

    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    private var _repository: SmartManagerRepository? = null
    var repository: SmartManagerRepository
        get() = _repository ?: SmartManagerRepository(this, database.fileDao()).also { _repository = it }
        set(value) { _repository = value }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        if (com.example.BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
        super.onCreate()
        instance = this
        Log.i("VVFApplication", "Initializing VVF Smart Manager Application Foundation...")

        initCrashlytics()

        setupBackgroundFileManagementTasks()
    }

    private fun setupBackgroundFileManagementTasks() {
        try {
            val wm = WorkManager.getInstance(this)

            // 1. Background File Storage Indexing
            val indexConstraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val indexWork = PeriodicWorkRequestBuilder<BackgroundIndexWorker>(
                24, TimeUnit.HOURS
            ).setConstraints(indexConstraints).build()
            wm.enqueueUniquePeriodicWork(
                BackgroundIndexWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                indexWork
            )

            // 2. Cache Cleanup
            val cacheConstraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val cacheCleanupWork = PeriodicWorkRequestBuilder<CacheCleanupWorker>(
                24, TimeUnit.HOURS
            ).setConstraints(cacheConstraints).build()
            wm.enqueueUniquePeriodicWork(
                CacheCleanupWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                cacheCleanupWork
            )

            // 3. Duplicate Cleanup
            val duplicateConstraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val duplicateCleanupWork = PeriodicWorkRequestBuilder<DuplicateCleanupWorker>(
                24, TimeUnit.HOURS
            ).setConstraints(duplicateConstraints).build()
            wm.enqueueUniquePeriodicWork(
                DuplicateCleanupWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                duplicateCleanupWork
            )

            // 4. Cloud Synchronization
            val cloudConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val cloudSyncWork = PeriodicWorkRequestBuilder<CloudSyncWorker>(
                12, TimeUnit.HOURS
            ).setConstraints(cloudConstraints).build()
            wm.enqueueUniquePeriodicWork(
                CloudSyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                cloudSyncWork
            )

            Log.i("VVFApplication", "All WorkManager background file management tasks successfully enqueued.")
        } catch (e: Exception) {
            Log.e("VVFApplication", "Failed to enqueue WorkManager background tasks: ${e.message}", e)
        }
    }

    private fun initCrashlytics() {
        try {
            com.google.firebase.FirebaseApp.initializeApp(this)
            val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
            crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
            Log.i("VVFApplication", "Firebase Crashlytics initialized successfully.")
        } catch (e: Exception) {
            Log.w("VVFApplication", "Firebase Crashlytics initialization pending (app/google-services.json required from Firebase Console): ${e.message}")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i("VVFApplication", "onTrimMemory called with level: $level. Delegating release of resources...")
        try {
            repository.trimMemory()
        } catch (e: Exception) {
            Log.e("VVFApplication", "Error during repository trimMemory: ${e.message}")
        }
    }

    companion object {
        lateinit var instance: VVFApplication
            private set
    }

}
