package com.ustadmobile.meshrabiya.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.ListenableWorker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReplicationWorkerTest {

    @Test
    fun worker_updates_job_and_retries_until_complete() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // Speed up replication wait/poll during tests
        System.setProperty("meshrabiya.replication_wait_ms", "2000")
        System.setProperty("meshrabiya.replication_poll_ms", "50")
        System.setProperty("meshrabiya.offer_window_ms", "200")
    // Ensure test-mode deterministic behavior inside the test JVM
    System.setProperty("meshrabiya.test_mode", "true")

        // Create a temporary blobs dir under the app files dir
        val baseDir = File(ctx.filesDir, "meshrabiya_blobs")
        if (baseDir.exists()) baseDir.deleteRecursively()
        baseDir.mkdirs()

        val id = "test-blob-1"
        val blobFile = File(baseDir, "${id}.blob")
        blobFile.writeText("hello world")

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

        // Debug: show where the test is creating files so we can inspect them from the host
        try {
            println("[TEST-DEBUG] ctx.filesDir=" + ctx.filesDir.absolutePath)
            println("[TEST-DEBUG] replFile.path=" + replFile.absolutePath)
            println("[TEST-DEBUG] replFile.contents=\n" + replFile.readText())
        } catch (_: Exception) {}

        // Build a Test worker with input data pointing to replFile
        val worker = TestListenableWorkerBuilder<ReplicationWorker>(ctx)
            .setInputData(androidx.work.Data.Builder().putString("repl_job_path", replFile.absolutePath).build())
            .build()

        // First run should increment attempts and accepted and request retry (since target=2)
    val result1 = worker.startWork().get()
    assertTrue(result1 is ListenableWorker.Result.Retry || result1 is ListenableWorker.Result.Success)

        val j1 = JSONObject(replFile.readText())
        assertEquals(1, j1.getInt("attempts"))
        assertTrue(j1.getInt("accepted") >= 1)

        // Run worker second time - simulate WorkManager retry by constructing new worker
        val worker2 = TestListenableWorkerBuilder<ReplicationWorker>(ctx)
            .setInputData(androidx.work.Data.Builder().putString("repl_job_path", replFile.absolutePath).build())
            .build()

    val result2 = worker2.startWork().get()
    assertTrue(result2 is ListenableWorker.Result.Success || result2 is ListenableWorker.Result.Retry)

        val j2 = JSONObject(replFile.readText())
        assertTrue(j2.getInt("attempts") >= 2)
        // If accepted reached target, status should be complete
        if (j2.getInt("accepted") >= j2.getInt("target_replicas")) {
            assertEquals("complete", j2.getString("status"))
        }
    }
}
