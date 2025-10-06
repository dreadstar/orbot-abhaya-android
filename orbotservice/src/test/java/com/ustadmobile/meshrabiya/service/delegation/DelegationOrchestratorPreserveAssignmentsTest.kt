package com.ustadmobile.meshrabiya.service.delegation

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DelegationOrchestratorPreserveAssignmentsTest {

    @Test
    fun preserves_existing_assignments() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val baseDir = File(ctx.filesDir, "meshrabiya_blobs")
        if (baseDir.exists()) baseDir.deleteRecursively()
        baseDir.mkdirs()

        val id = "preserve-assignments-blob"
        val replFile = File(baseDir, "${id}.repl.json")
        val repl = JSONObject()
        repl.put("id", id)
        repl.put("blob_path", File(baseDir, "${id}.blob").absolutePath)
        repl.put("meta_path", File(baseDir, "${id}.json").absolutePath)
        repl.put("target_replicas", 1)
        val arr = org.json.JSONArray()
        val asn = JSONObject()
        asn.put("assigneeId", "peer1")
        asn.put("token", "tok")
        arr.put(asn)
        repl.put("assignments", arr)
        replFile.writeText(repl.toString())

        val orch = DelegationOrchestrator(ctx)
    val delegated = orch.processReplicationRequest(replFile.absolutePath, 50)

        // Should return true (delegation considered done) and preserve assignments
        assertTrue(delegated)
        val after = JSONObject(replFile.readText())
        val assignments = after.optJSONArray("assignments")
        assertNotNull(assignments)
        assertEquals(1, assignments.length())
        val asnAfter = assignments.getJSONObject(0)
        assertEquals("peer1", asnAfter.optString("assigneeId"))
    }
}
