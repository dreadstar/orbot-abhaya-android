package com.ustadmobile.meshrabiya.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReplicationWorkerPersistenceTest {

    @Test
    fun worker_persists_attempts_and_accepts_in_test_mode() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        System.setProperty("meshrabiya.test_mode", "true")
        System.setProperty("meshrabiya.replication_wait_ms", "2000")
        System.setProperty("meshrabiya.replication_poll_ms", "50")
        System.setProperty("meshrabiya.offer_window_ms", "200")

        val baseDir = File(ctx.filesDir, "meshrabiya_blobs")
        if (baseDir.exists()) baseDir.deleteRecursively()
        baseDir.mkdirs()

        val id = "persistence-blob-1"
        val blobFile = File(baseDir, "${id}.blob")
        blobFile.writeText("data")

        val metaFile = File(baseDir, "${id}.json")
        val meta = JSONObject()
        meta.put("id", id)
        meta.put("size", blobFile.length())
        metaFile.writeText(meta.toString())

        val replFile = File(baseDir, "${id}.repl.json")
        val repl = JSONObject()
        repl.put("id", id)
        repl.put("blob_path", blobFile.absolutePath)
        repl.put("meta_path", metaFile.absolutePath)
        repl.put("created_at", System.currentTimeMillis())
        repl.put("target_replicas", 2)
        repl.put("max_acceptances", 5)
        repl.put("attempts", 0)
        repl.put("accepted", 0)
        repl.put("status", "pending")
        replFile.writeText(repl.toString())

        val worker = TestListenableWorkerBuilder<ReplicationWorker>(ctx)
            .setInputData(androidx.work.Data.Builder().putString("repl_job_path", replFile.absolutePath).build())
            .build()

        val result = worker.startWork().get()
        assertTrue(result is ListenableWorker.Result.Retry || result is ListenableWorker.Result.Success)

        val j1 = JSONObject(replFile.readText())
        // attempts should have been incremented at least once (delegation persisted)
        assertTrue(j1.getInt("attempts") >= 1)
        // accepted should have progressed in test mode to at least 1
        assertTrue(j1.getInt("accepted") >= 1)

        // run again to allow completion
        val worker2 = TestListenableWorkerBuilder<ReplicationWorker>(ctx)
            .setInputData(androidx.work.Data.Builder().putString("repl_job_path", replFile.absolutePath).build())
            .build()

        val result2 = worker2.startWork().get()
        assertTrue(result2 is ListenableWorker.Result.Success || result2 is ListenableWorker.Result.Retry)

        val j2 = JSONObject(replFile.readText())
        assertTrue(j2.getInt("accepted") >= j2.getInt("target_replicas"))
        if (j2.getInt("accepted") >= j2.getInt("target_replicas")) {
            assertEquals("complete", j2.getString("status"))
        }
    }
}
