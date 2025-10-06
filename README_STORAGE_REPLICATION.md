Meshrabiya storage & replication

This document summarizes the storage and replication artifacts and runtime preferences added for Meshrabiya integration.

Artifacts written into the drop-folder (per blob):
- <id>.blob — raw binary blob contents (written durably using temp->fsync/fdatasync->atomic rename)
- <id>.json — metadata JSON produced by the uploader
- <id>.repl.json — replication job JSON created at store time. Contains at minimum: { "blob_path": "<id>.blob", "meta_path": "<id>.json", ... }
- receipts.txt — append-only receipts file; entries record upload receipts including uploader public key and timestamp

Runtime wiring / APIs:
- DistributedStorageAgent.replicateReplJobForBlob(blobId: String, replicationFactor: Int = 3)
  - Targeted replication API: reads the <id>.repl.json job file from the drop folder and attempts replication to candidate peers.

- MeshServiceCoordinator.requestReplicationForBlob(blobId: String)
  - Called by coordinator after a successful store (gated by preference). This will call the storage agent to request targeted replication for the blob.

Preferences / Feature flags:
- mesh_storage_auto_replicate_on_store (SharedPreferences stored under mesh_storage_prefs)
  - Default: true
  - When true, the coordinator will request immediate replication for a blob after it is stored.

Socket timeouts provider:
- com.ustadmobile.meshrabiya.net.SocketTimeoutsProvider
  - Provides acceptTimeoutMillis and socketSoTimeoutMillis (ServerSocket accept & accepted socket SO_TIMEOUTs) and connect/read/write timeouts for stream sockets and HTTP clients.
  - DefaultSocketTimeoutsProvider: reads system property `meshrabiya.hardware.testMode` to decide default timeouts (test-mode values are small ints to make tests deterministic).
  - TestSocketTimeoutsProvider allows explicit values for tests.

Notes for tests and authors:
- Unit tests that run on the JVM use `org.json:json` in test classpath to avoid Android framework stubbing issues.
- To run the focused unit test for targeted replication under Java 21:

  export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
  ./gradlew :app:testFullpermDebugUnitTest --tests org.torproject.android.service.compute.DistributedStorageAgentReplTest -Dmeshrabiya.hardware.testMode=true -i

- The code paths are designed to preserve module boundaries; the service module provides the durable write boundary and creates the .repl.json job files; the app module (DistributedStorageAgent) handles replication orchestration.

If you want an end-to-end instrumented integration test covering WorkManager enqueing and execution on-device, we can add an androidTest which runs on Robolectric or device farm; the current repo includes deterministic unit- and small integration-style tests that run on the JVM.
