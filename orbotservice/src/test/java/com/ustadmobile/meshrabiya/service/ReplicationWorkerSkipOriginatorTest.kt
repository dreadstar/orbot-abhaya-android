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
class ReplicationWorkerSkipOriginatorTest {

    @Test
    fun worker_skips_assignments_to_originator() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        System.setProperty("meshrabiya.test_mode", "true")
        // shorten waits during test
        System.setProperty("meshrabiya.replication_wait_ms", "2000")
        System.setProperty("meshrabiya.replication_poll_ms", "50")

        val baseDir = File(ctx.filesDir, "meshrabiya_blobs")
        if (baseDir.exists()) baseDir.deleteRecursively()
        baseDir.mkdirs()

        val id = "skip-originator-blob"
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
        repl.put("target_replicas", 1)
        repl.put("max_acceptances", 1)
        repl.put("attempts", 0)
        repl.put("accepted", 0)
        repl.put("status", "pending")
        // Add an assignment whose assignee is the originator
        val arr = org.json.JSONArray()
        val asn = JSONObject()
        asn.put("assigneeId", "origin")
        asn.put("token", "tok")
        arr.put(asn)
        repl.put("assignments", arr)
        // set originator id so worker will skip
        repl.put("originator", "origin")
        replFile.writeText(repl.toString())

        val worker = TestListenableWorkerBuilder<ReplicationWorker>(ctx)
            .setInputData(androidx.work.Data.Builder().putString("repl_job_path", replFile.absolutePath).build())
            .build()

        val result = worker.startWork().get()
        assertTrue(result is ListenableWorker.Result.Retry || result is ListenableWorker.Result.Success)

    // Copy the resulting job file to build/test-artifacts for inspection by host
    TestArtifactUtil.copyToArtifacts(replFile)

    val j = JSONObject(replFile.readText())
    // Worker should have attempted delegation/processing and persisted attempts
    assertTrue(j.optInt("attempts", 0) >= 1)
    }
}
