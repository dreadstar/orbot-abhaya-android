# Project Knowledge Base: orbot-android (as of 2025-09-05)

## 1. Project Overview & Goals

**Project:** orbot-android (and Meshrabiya integration)

**Primary Goals:**
- Build a privacy-focused Android app integrating Tor (Orbot) and Meshrabiya mesh networking.
- Provide secure, censorship-resistant communication and internet access via Tor and local mesh networks.
- Modular architecture supporting Orbot core, service, and Meshrabiya library.
- Maintain modern Android compatibility (SDK 36, Java 21, AGP 8.12.2, Kotlin 2.2.10).

## 2. Architecture Summary

- **Modules:**
  - `app`: Main Android application, entry point, UI, integration logic.
  - `OrbotLib`: Core Tor library, shared logic.
  - `orbotservice`: Android service for Tor operations.
  - `Meshrabiya/lib-meshrabiya`: Mesh networking library.
- **Build System:**
  - Gradle (Kotlin DSL), multi-module, plugin aliases via `libs.versions.toml`.
  - JVM toolchain: Java 21 enforced for all modules.
  - AndroidX, Kotlin, and serialization plugins.
- **Source Sets:**
  - Standard Android structure (`src/main`, `src/debug`, etc.), all required directories present.
- **Dependency Management:**
  - Direct `implementation` for all critical libraries (Kotlin, AndroidX, BouncyCastle, etc.).
  - Core library desugaring enabled for Java 21 features.
- **Packaging:**
  - ABI splits, universal APK, resource exclusions, custom signing configs.
- **Testing:**
  - JUnit, Espresso, AndroidX Test Orchestrator, Screengrab.

## 3. Rules & User Requirements

- **Terminal Commands:**
  - Always clear the terminal before running a command in the terminal
  - If youae unable to read the output of the terminal session, tee the output of commands to  a file to analyze  results 
- **Log/Report Analysis:**
  - Always review the *entire* build log and all referenced reports, never partial selections.
- **Root Cause Extraction:**
  - Systematic, bottom-up extraction of build failures and error markers.
- **Actionable Fixes:**
  - Propose concrete, expert-level solutions for all build issues.
- **Directory Structure:**
  - Ensure all expected source/resource directories exist for every module.
- **JVM Context:**
  - All builds must use Java 21; validate JVM context before builds.
- **Build Cleanliness:**
  - Truncate build logs before new builds; purge Gradle caches and build directories for clean builds.
- **Deprecation/D8 Warnings:**
  - Store and document all deprecation and D8 warnings in a project TODO.
- **Full Project Build:**
  - Always run a full project build for proper dependency resolution.
- **NDK Installation:**
  - Use correct Java version for sdkmanager (Java 8 required).
- **Be Critical:**
  - Do not be a sychophant to me. Weigh your suggestions against best practices and what has already been tried: do not suggest things that we have already tried. DO not hesitate to ask for more information or to suggest alternatives if you see issues with my request or approach.

## 4. Frequently Used Commands & Their Purpose

- `./gradlew clean assembleDebug --info | tee build_output.log`
  - Full clean build with debug output, logs to file for analysis.
- `truncate -s 0 build_output.log`
  - Clears build log before a new build.
- `./gradlew --version`
  - Verifies JVM context and Gradle version.
- `./gradlew clean --refresh-dependencies`
  - Purges Gradle caches and forces dependency re-download.
- `find . -name build -type d -exec rm -rf {} + && rm -rf .gradle`
  - Removes all build directories and Gradle cache for a deep clean.
- `sdkmanager --install 'ndk;27.0.12077973'`
  - Installs required NDK version (Java 8 required).
- `jar tf <artifact>.aar|.jar`
  - Inspects contents of built artifacts for missing classes.
- `grep -i 'error\|failed\|d8\|desugar' build_output.log`
  - Searches build log for error markers and D8 issues.

## 5. Detailed Diagnosis of Current Build Issues

### A. D8 Desugaring Errors (Root Cause)
- **Symptoms:**
  - D8 reports missing types during Dexing (e.g., `kotlin.jvm.functions.Function0`, `androidx.work.Worker`, `kotlinx.serialization.KSerializer`, `IPtProxy.*`, `org.bouncycastle.util.io.pem.PemObjectGenerator`).
  - Both `:app:compileFullpermDebugKotlin` and `:app:compileNightlyDebugKotlin` fail.
- **Analysis:**
  - These types are required for default/static interface methods desugaring.
  - Missing types indicate absent or misconfigured dependencies in the runtime classpath.
  - Affects both Orbot and Meshrabiya modules.

### B. Dependency Verification
- **Required Libraries:**
  - Kotlin stdlib, coroutines, serialization
  - AndroidX WorkManager, Fragment, etc.
  - BouncyCastle (bcprov, bcpkix, bcutil)
  - IPtProxy (custom/external)
- **Current State:**
  - Most dependencies are present in `app/build.gradle.kts`.
  - Must verify all are present in `orbotservice` and `Meshrabiya/lib-meshrabiya` as `implementation` (not just `api` or `compileOnly`).
  - No accidental exclusions or misconfigurations.

### C. Classpath & Artifact Inspection
- **Artifacts:**
  - Runtime classpath and built AAR/JARs must include all required classes.
  - Previous inspection showed some classes missing from artifacts.

### D. NDK & JVM Context
- **NDK:**
  - Correct version installed, but sdkmanager requires Java 8.
- **JVM:**
  - Java 21 enforced and validated.

## 6. Recommendations & Next Steps

1. **Audit All Dependencies:**
   - Review `build.gradle.kts`/`build.gradle` for `app`, `orbotservice`, and `Meshrabiya/lib-meshrabiya`.
   - Ensure all required libraries are present as `implementation` dependencies.
   - Add any missing libraries (Kotlin stdlib, coroutines, serialization, AndroidX, BouncyCastle, IPtProxy).
2. **Classpath Inspection:**
   - Confirm runtime classpath includes all required JARs for missing types.
   - Inspect built artifacts for presence of critical classes.
3. **IPtProxy Library:**
   - If custom/external, ensure it is included and present in build outputs.
4. **NDK Installation:**
   - Use Java 8 for sdkmanager if further NDK installs are needed.
5. **Full Clean Build:**
   - Purge caches, truncate logs, and run a full build after dependency fixes.
6. **Deprecation/D8 Warnings:**
   - Continue documenting and suppressing as needed.
7. **Report Analysis:**
   - Always review full logs and referenced reports for every build.

## 7. Expert Prompt Guidance (for Claude/GPT-5)

- Always analyze the *entire* build log and referenced reports, not just partial selections.
- Extract root cause of build failures bottom-up, focusing on D8/desugaring errors and missing types.
- Propose actionable, expert-level fixes for dependency/classpath/artifact issues.
- Validate JVM context and directory structure before builds.
- Document all deprecation/D8 warnings and store in project TODO.
- Use full clean builds and log truncation for reproducible results.
- Confirm all required dependencies are present as `implementation` in every module.
- Inspect runtime classpath and built artifacts for missing classes.
- Use correct Java version for NDK installation.
- Adhere strictly to user-specified rules and requirements.

---

**This document should be updated after every major build issue, architecture change, or dependency update.**
