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
class ReplicationWorkerUploadFailureTest {

    @Test
    fun worker_handles_unsupported_upload_endpoint() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        System.setProperty("meshrabiya.test_mode", "false")
        System.setProperty("meshrabiya.replication_wait_ms", "1000")
        System.setProperty("meshrabiya.replication_poll_ms", "50")

        val baseDir = File(ctx.filesDir, "meshrabiya_blobs")
        if (baseDir.exists()) baseDir.deleteRecursively()
        baseDir.mkdirs()

        val id = "unsupported-endpoint-blob"
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
        // Add an assignment with a non-http upload endpoint
        val arr = org.json.JSONArray()
        val asn = JSONObject()
        asn.put("assigneeId", "peer1")
        asn.put("token", "tok")
        asn.put("upload_endpoint", "onion://example.onion/upload")
        arr.put(asn)
        repl.put("assignments", arr)
        replFile.writeText(repl.toString())

        val worker = TestListenableWorkerBuilder<ReplicationWorker>(ctx)
            .setInputData(androidx.work.Data.Builder().putString("repl_job_path", replFile.absolutePath).build())
            .build()

        val result = worker.startWork().get()
        // Worker should either retry or succeed depending on timing; ensure job file persisted
        assertTrue(result is ListenableWorker.Result.Retry || result is ListenableWorker.Result.Success)

        // Copy job file to canonical artifacts directory in workspace (for host inspection)
        // Copy reproducible artifacts to workspace/module-local artifact dirs
        TestArtifactUtil.copyToArtifacts(replFile)

        val j = JSONObject(replFile.readText())
        // There should be at least one assignment result recorded (failure)
    val arrRes = j.optJSONArray("assignment_results")
    assertNotNull(arrRes)
    assertTrue((arrRes?.length() ?: 0) >= 1)
        // accepted should be 0 because upload is unsupported
        assertEquals(0, j.optInt("accepted", 0))
    }
}
