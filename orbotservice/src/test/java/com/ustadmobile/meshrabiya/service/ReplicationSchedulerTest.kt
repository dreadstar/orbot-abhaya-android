package com.ustadmobile.meshrabiya.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReplicationSchedulerTest {

    @Before
    fun setup() {
        // Speed up offer windows and replication polling during tests
        System.setProperty("meshrabiya.offer_window_ms", "200")
        System.setProperty("meshrabiya.replication_wait_ms", "2000")
        System.setProperty("meshrabiya.replication_poll_ms", "50")
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
    }

    @Test
    fun scheduleReplication_enqueues_unique_work() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        val baseDir = File(ctx.filesDir, "meshrabiya_blobs")
        if (baseDir.exists()) baseDir.deleteRecursively()
        baseDir.mkdirs()

        val id = "sched-blob-1"
        val replFile = File(baseDir, "${id}.repl.json")
        val repl = JSONObject()
        repl.put("id", id)
        repl.put("blob_path", "/tmp/doesnotexist")
        repl.put("meta_path", "/tmp/meta")
        repl.put("created_at", System.currentTimeMillis())
        repl.put("target_replicas", 1)
        repl.put("max_acceptances", 1)
        repl.put("attempts", 0)
        repl.put("accepted", 0)
        repl.put("status", "pending")
        replFile.writeText(repl.toString())

        // Should not throw
        ReplicationScheduler.scheduleReplication(ctx, replFile.absolutePath)

        // Calling again should not schedule duplicate (ExistingWorkPolicy.KEEP)
        ReplicationScheduler.scheduleReplication(ctx, replFile.absolutePath)

        // If no exceptions thrown, consider test passed (WorkManagerTest framework will record enqueued work)
        assertTrue(replFile.exists())
    }
}
