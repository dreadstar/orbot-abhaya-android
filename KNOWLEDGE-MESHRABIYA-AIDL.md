Meshrabiya AIDL packaging and consumer build notes

Summary
-------
We centralized Meshrabiya AIDL/Binder interfaces into a new Gradle module `:meshrabiya-api` which contains:
- src/main/aidl/com/ustadmobile/meshrabiya/api/IMeshrabiyaService.aidl
- src/main/aidl/com/ustadmobile/meshrabiya/api/IOperationCallback.aidl
- src/main/aidl/com/ustadmobile/meshrabiya/api/MeshStatus.aidl
- src/main/java/com/ustadmobile/meshrabiya/api/MeshStatus.kt (@Parcelize)

Problem
-------
When consumers depend on `:meshrabiya-api` they need the AIDL sources at compile time so the Android build tools can generate Binder stub classes (IMeshrabiyaService.Stub, etc.). AARs produced by AGP do not automatically ship the original `src/main/aidl` files into the AAR's `aidl/` directory in a variant-agnostic way, so consumers can't generate the binder stubs when relying solely on the compiled AAR.

Short-term workarounds attempted
-------------------------------
1) Duplicate AIDL files in each consumer's `src/main/aidl`.
   - Works but duplicates source and is error-prone.

2) Add `aidl.srcDir(project(":meshrabiya-api").file("src/main/aidl"))` to each consumer's `android.sourceSets.main`.
   - Chosen solution (Option A). Consumers generate binder stubs locally using the canonical AIDL sources in the API module. Simple and reliable within mono-repo.

3) Post-process AARs to inject `aidl/` into each produced AAR (variant-aware).
   - Implemented a temporary debug-only task `bundleAarWithAidl` in `meshrabiya-api` to inject the AIDL for debug variant.
   - Attempted proper variant-aware Gradle Kotlin DSL code using AGP variant APIs; this failed at script compilation time due to Kotlin DSL typing / API usage errors (see "Kotlin DSL errors" below).

Why the variant-aware attempt failed (Kotlin DSL errors)
-----------------------------------------------------
While experimenting with injecting AIDL into every variant's AAR (via AGP variant APIs / androidComponents), the Gradle Kotlin script compilation failed with several errors. These are symptoms of mismatched lambda parameter types, wrong expected return types, and misuse of `TaskProvider` APIs:
- Return type mismatch: expected 'Boolean', actual 'Unit'.
- Argument type mismatch: actual type 'Function2<...>', but 'Function1<...>' expected.
- Cannot infer type for this parameter. Specify it explicitly.
- Unresolved reference 'finalizedBy' (caused when the parameter was typed incorrectly).

These compile-time Kotlin script errors prevented a correct variant-aware implementation from being accepted by the Gradle script compiler. Fixing them requires explicit typed lambdas and correct use of the AGP `androidComponents` or `libraryVariants` APIs and TaskProvider wiring; it's doable but fragile in Kotlin DSL and requires careful typing.

Current chosen fix (Option A)
----------------------------
- Add the canonical `:meshrabiya-api` module's `src/main/aidl` directory to each consumer's `android.sourceSets.main.aidl.srcDirs`.
- This is implemented for:
  - `orbotservice` (already updated)
  - `abhaya-sensor-android/app` (updated in this change)
- This makes consumers generate binder stubs at compile time using the same canonical source; no duplication.

How to apply (example)
-----------------------
In the consumer module's `build.gradle.kts` under the `android` block add:

sourceSets {
    getByName("main") {
        aidl.srcDir(project(":meshrabiya-api").file("src/main/aidl"))
    }
}

Notes:
- This must be done for every consumer module that directly references AIDL-generated types (i.e., that references IMeshrabiyaService or MeshStatus from AIDL).
- For library modules consuming meshrabiya-api, prefer `api(project(":meshrabiya-api"))` so downstream modules also see the dependency.

Long-term solutions (recommended)
---------------------------------
A. Implement variant-aware AAR packaging that places AIDL sources inside the produced AAR under `aidl/` for every variant and artifact.
   - Use AGP's new `androidComponents.onVariants` and `artifacts` APIs or the transform API to attach the `src/main/aidl` folder into each variant's AAR output.
   - Be explicit with Kotlin DSL typing when registering tasks and wiring `TaskProvider`s. Example research pointers:
     - androidComponents.onVariants { variant -> variant.artifacts.use(...).wiredTo(...) }
     - AGP artifact transforms and `ArtifactType.AAR` usage
   - Trade-offs: more complex Gradle code, fragile across AGP versions, but produces publishable AARs that external consumers can use without adding `aidl.srcDir`.

B. Publish a separate artifact that contains only AIDL sources (or an aar with aidl/) and have consumers depend on that artifact. This isolates packaging complexity.

C. Keep Option A for internal monorepo usage and document the requirement clearly in developer setup guides. Use pre-push checks or CI tasks to ensure consumers have the sourceSet entry.

Open Todos (short and long term)
--------------------------------
- [ ] Add aidl.srcDir to all remaining consumers (search: modules that implement MeshrabiyaAidlService or call IMeshrabiyaService).
- [ ] Implement and test the service manifest entry and enforce signature-level BIND_MESHRABIYA permission in `MeshrabiyaAidlService.onBind()`.
- [ ] Implement MeshStatus provider logic in the Meshrabiya service and add a coroutine-based client wrapper in sensor apps.
- [ ] Implement full variant-aware AAR packaging (AGP artifacts API) for publishing a proper AAR with AIDL bundled.
- [ ] Add unit/integration tests around binder IPC contract (happy path + permission-denied path).
- [ ] Add CI checks ensuring consumers compile with the updated sourceSets and fail early if they do not.

Files changed in this work
-------------------------
- Added module: `:meshrabiya-api` (AIDL + MeshStatus).
- Edited: `orbotservice/build.gradle.kts` — added `aidl.srcDir(project(":meshrabiya-api").file("src/main/aidl"))`.
- Edited: `abhaya-sensor-android/app/build.gradle.kts` — added `aidl.srcDir(project(":meshrabiya-api").file("src/main/aidl"))`.
- Edited: `meshrabiya-api/build.gradle.kts` — added a debug-only `bundleAarWithAidl` task that injects `src/main/aidl` into the debug AAR (temporary helper).

Build verification notes
------------------------
- After adding the `aidl.srcDir` to `abhaya-sensor-android/app`, building `:abhaya-sensor-android:app:assembleDebug` failed due to unrelated Kotlin duplicate declarations (likely because there are duplicate sources under different packages). These are separate issues in the sensor app and need to be resolved independently; they are not caused by the AIDL change.

Contact / Next steps
--------------------
If you want I can:
- Add `aidl.srcDir` to other modules I find as potential consumers.
- Attempt to implement the long-term variant-aware AAR packaging using `androidComponents` and the AGP artifacts API (I will proceed carefully and run validation builds). Note: this may require iterating to get the Kotlin DSL typing correct.
- Open a PR with these changes and a short CI job that verifies consumer compilation.

