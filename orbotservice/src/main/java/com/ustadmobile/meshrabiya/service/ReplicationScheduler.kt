package com.ustadmobile.meshrabiya.service

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

/**
 * ReplicationScheduler provides helpers to schedule replication work either by blobId
 * (convenience) or by explicit repl job path (the `.repl.json` file).
 */
object ReplicationScheduler {
    private const val TAG = "ReplicationScheduler"
    private const val WORK_NAME_PREFIX = "meshrabiya_repl_"

    fun scheduleReplicationForBlob(context: Context, blobId: String) {
        try {
            val workRequest = OneTimeWorkRequestBuilder<ReplicationWorker>()
                .addTag("replicate-$blobId")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("replicate-$blobId", ExistingWorkPolicy.KEEP, workRequest)

            Log.i(TAG, "Scheduled replication work for blob $blobId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule replication for $blobId", e)
        }
    }

    /** Enqueue a replication worker for the given repl job file path. */
    fun scheduleReplication(context: Context, replJobPath: String) {
        val jobFile = File(replJobPath)
        if (!jobFile.exists()) throw IllegalArgumentException("Repl job file does not exist: $replJobPath")

        val data = Data.Builder().putString("repl_job_path", replJobPath).build()

        val work = OneTimeWorkRequestBuilder<ReplicationWorker>()
            .setInputData(data)
            .build()

        // Use a unique name per job file to avoid duplicate scheduling
        val uniqueName = WORK_NAME_PREFIX + jobFile.nameWithoutExtension
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, work)

        Log.i(TAG, "Scheduled replication for ${jobFile.name}")
    }

    fun findPendingReplicationJobs(filesDir: File): List<String> {
        val jobs = mutableListOf<String>()
        val dir = File(filesDir, "meshrabiya_blobs")
        if (!dir.exists() || !dir.isDirectory) return jobs

        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".repl.json")) {
                val blobId = f.name.removeSuffix(".repl.json")
                jobs += blobId
            }
        }
        return jobs
    }
}
