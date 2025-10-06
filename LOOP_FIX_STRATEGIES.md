# LOOP_FIX_STRATEGIES

Purpose
- Capture candidate approaches, design notes, quick fixes and test-actions related to eliminating infinite/long-running loops (ChainSocketServer, OriginatingMessageManager, related accept/connect/retry loops).
- Keep this as a single reference so work can be resumed later with context and concrete next steps.

Context / problem
- Tests and some runtime conditions expose indefinite looping/backoff behavior (accept timeouts, repeated worker retries, producer spin), causing resource waste and flaky tests.
- Goal: preserve mesh functionality and production behavior while removing infinite-loop potential, improving efficiency, determinism in tests, and robust shutdown semantics.

Quick fixes already applied (notes)
- ChainSocketServer: accept loop now runs in a cancellable coroutine scope and treats SocketTimeoutException as DEBUG (no backoff).
  - File: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/socket/ChainSocketServer.kt`
- Replication/Delegation: deterministic test-mode offers; preserve pre-existing `assignments` in job files so tests are not clobbered.
- ReplicationWorker: ensure job JSON reloaded/persisted in key branches; added logic to append assignment_results on upload failure.
- Tests: helper to copy `.repl.json` artifacts to `orbotservice/build/test-artifacts` for inspection.

High-level options and assessment
1) ServerLifecycleManager (start-on-demand, refcounted)
   - Impact on loops: High — eliminates idle accept loops when no consumers.
   - Effort: Low–Medium.
   - Change surface: add new `vnet/ServerLifecycleManager.kt`, expose `ChainSocketServer.start()/stop()`, and wire `VirtualNode`/tests to acquire/release.
   - Recommended: First-line action for test-time and runtime efficiency.

2) OriginatingMessageManager → bounded Channel + UNAVAILABLE
   - Impact: High for producer-driven spin; prevents backlog-driven retries.
   - Effort: Low.
   - Behavior: producers use `trySend()`; on full -> respond UNAVAILABLE and backoff.

3) SupervisorJob + cancellable coroutine loops per VirtualNode
   - Impact: High on deterministic shutdown and cancelability.
   - Effort: Medium.
   - Benefit: children tasks don't cancel siblings; `close()` reliably cancels all background work.

4) Circuit-breaker / bounded retries with cap
   - Impact: Medium; prevents infinite retry burning CPU.
   - Effort: Low.
   - Behavior: stop retrying after N consecutive errors for cooldown T.

5) Shared acceptor / NIO Selector (Connection Broker)
   - Impact: High at scale and reduces thread/wake churn.
   - Effort: High — major refactor.
   - Consider later (performance optimization).

6) Replication/Protocol hardening (idempotence, inventory checks, TTL)
   - Impact: Medium–High for logical replication loops.
   - Effort: Medium.
   - Behavior: filter originator, HEAD/metadata checks, hop-count/TTL.

Concrete phased plan (recommended)

Phase 0 — immediate safety
- Keep coroutine accept loop and SocketTimeoutException handling.
- Add circuit-breaker for non-timeout exceptions and a max consecutive error limit.
- Add metrics/counters for retries and drops.

Phase 1 — lifecycle-driven servers + OriginatingMessageManager backpressure (high impact)
- Implement `ServerLifecycleManager` with API:
  - `acquireServer(name, bindAddr, port, chainSocketFactory, logger)` -> returns `ChainSocketServer` (starts on first acquire)
  - `releaseServer(name, bindAddr, port)` -> decrements refcount, schedule idle shutdown
  - `forceStop(name, bindAddr, port)`
- Modify `ChainSocketServer` to remove auto-start in `init` and add `start(scope)`/`stop()` methods that control accept coroutine lifecycle.
- Update `VirtualNode` (and tests) to call acquire/release. Use short idle timeout (e.g., 5s) before shutting down idle servers.
- Convert `OriginatingMessageManager` to a bounded `Channel` and return UNAVAILABLE when full.

Phase 2 — state machines, bounded channels, and replication hardening
- Ensure `VirtualNode` uses `SupervisorJob`-based `CoroutineScope`.
- Convert blocking socket operations into `withContext(Dispatchers.IO)` + cancellable patterns.
- Harden replication logic (dedupe, originator filter, remoteHasBlob checks) so replication cannot re-enter logical loops.
- Add unit tests for all important branches (skip-originator, upload-failure, delegation-preserve-assignments).

Phase 3 — optimization (optional)
- Replace blocking accept loops with a shared NIO acceptor if scale/efficiency warrants.

ServerLifecycleManager sketch (Phase 1)
```kotlin
object ServerLifecycleManager {
    private val lock = Any()
    private val servers = mutableMapOf<String, ServerEntry>()

    data class ServerEntry(
        val name: String,
        val serverSocket: ServerSocket,
        val server: ChainSocketServer,
        val refCount: AtomicInteger = AtomicInteger(1),
        var idleCancelTaskId: Int = -1
    )

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    fun acquire(name: String, bindAddr: InetAddress, port: Int,
                chainSocketFactory: ChainSocketFactory, logger: MNetLogger): ChainSocketServer {
        synchronized(lock) {
            val key = "$name@$bindAddr:$port"
            val existing = servers[key]
            if (existing != null) {
                existing.refCount.incrementAndGet()
                return existing.server
            }

            val ss = ServerSocket(port, 50, bindAddr)
            val server = ChainSocketServer(ss, Executors.newCachedThreadPool(), chainSocketFactory, name, logger)
            // server.start() // call start after moving accept loop into start()
            val entry = ServerEntry(name, ss, server)
            servers[key] = entry
            return server
        }
    }

    fun release(name: String, bindAddr: InetAddress, port: Int, idleTimeoutMs: Long = 5000L) {
        synchronized(lock) {
            val key = "$name@$bindAddr:$port"
            val entry = servers[key] ?: return
            val refs = entry.refCount.decrementAndGet()
            if (refs <= 0) {
                scheduler.schedule({
                    synchronized(lock) {
                        val e = servers[key] ?: return@schedule
                        if (e.refCount.get() <= 0) {
                            try { e.server.close(true) } catch (_: Exception) {}
                            e.serverSocket.close()
                            servers.remove(key)
                        }
                    }
                }, idleTimeoutMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun forceStop(name: String, bindAddr: InetAddress, port: Int) {
        synchronized(lock) {
            val key = "$name@$bindAddr:$port"
            servers.remove(key)?.let { entry ->
                try { entry.server.close(true) } catch (_: Exception) {}
                try { entry.serverSocket.close() } catch (_: Exception) {}
            }
        }
    }
}
```

ChainSocketServer start/stop sketch

```kotlin
class ChainSocketServer(...) : Closeable {
    private var acceptJob: Job? = null

    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())) {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            // move accept loop body here (from init)
        }
    }

    fun stop() {
        acceptJob?.cancel()
        close(closeSocket = true)
    }

    override fun close() { stop() }
}
```

OriginatingMessageManager bounded-channel sketch

- Use `kotlinx.coroutines.Channel` with a capacity (e.g., 512).
- Producers: `channel.trySend(msg)`; if returns `isFailure`, respond `UNAVAILABLE` immediately and increment metric.
- Consumer: launched under `nodeScope` and processes messages from channel. Respect `isActive` for cancellation.

Circuit-breaker for accept loop

- Track consecutive non-timeout exceptions.
- After `N` failures (e.g., 10), mark the server as errored and stop accept loop; restart only after a cooldown or manual forceStop/forceStart.

Testing checklist / canonical commands

- Set Java 21 and truncate logs first:
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
: > runAllTests_output.log
./gradlew runAllTests --console=plain -Dorg.gradle.jvmargs="-Xmx1g -Djdk.attach.allowAttachSelf=true" -Psession.timeout=300 --stacktrace 2>&1 | tee runAllTests_output.log
```

- Run a focused test example:
```bash
: > replication_worker_test.log
./gradlew :orbotservice:testDebugUnitTest --tests com.ustadmobile.meshrabiya.service.ReplicationWorkerUploadFailureTest -Dmeshrabiya.test_mode=false -i 2>&1 | tee replication_worker_test.log
```

Files of interest / quick references
- `ChainSocketServer`: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/socket/ChainSocketServer.kt`
- `VirtualNode`: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`
- `ReplicationWorker`: `orbotservice/src/main/java/com/ustadmobile/meshrabiya/service/ReplicationWorker.kt`
- `DelegationOrchestrator`: `orbotservice/src/main/java/com/ustadmobile/meshrabiya/service/delegation/DelegationOrchestrator.kt`
- Tests/artifacts helper: `orbotservice/src/test/...` (TestArtifactUtil created earlier)

Actionable immediate tasks (pickable, prioritized)
- Implement ServerLifecycleManager + ChainSocketServer.start/stop + wire VirtualNode (Phase 1).
- Convert OriginatingMessageManager to bounded Channel + UNAVAILABLE behavior.
- Add circuit-breaker and supervisor-cancelable tasks.
- Add unit tests for replication branches and ensure artifact writing for test inspection.

Notes for resumption
- Start from `ChainSocketServer.kt` (active file). Move the accept loop into `start()` and expose `stop()` so `ServerLifecycleManager` can control lifecycle.
- Make tests explicitly start the server when needed to avoid global test-mode side effects.

Status
- Quick fix applied: SocketTimeoutException is treated as debug and does not trigger backoff.
- Next recommended step: implement Phase 1 (ServerLifecycleManager + start/stop) and convert OriginatingMessageManager to bounded-channel. After that, re-run failing tests and full suite.


---

Created: `LOOP_FIX_STRATEGIES.md` at repo root
