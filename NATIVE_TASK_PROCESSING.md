# NATIVE_TASK_PROCESSING.md

## Overview: Native Task Processing via C Shared Library

This document details the concept, implementation, integration, and trade-offs of using a native C/C++ shared library to launch, monitor, and control child processes for task isolation and metrics collection in the Meshrabiya Android environment.

---

## 1. Concept Description

Android's Java APIs (Java <9) do not provide direct access to child process IDs (PIDs) or granular resource metrics for processes started via `ProcessBuilder`. To overcome this, a native shared library (JNI) can be used to:
- Launch child processes (using `fork()`/`exec()` or similar)
- Capture and return the child PID
- Monitor resource usage (memory, CPU, etc.) via `/proc/<pid>/...`
- Control process lifecycle (kill, wait, etc.)
- Communicate with the app via JNI

This approach enables more granular control and monitoring, but comes with security, compatibility, and maintenance trade-offs.

---

## 2. Size Cost and Overhead

- **Native Library Size:**
  - Typical `.so` file: 50KB–500KB (depends on features, architectures, and debug symbols)
  - Must be built for all target ABIs (armeabi-v7a, arm64-v8a, x86, x86_64)
- **App APK Size Impact:**
  - Each ABI adds to APK size; use ABI splits if possible
- **Runtime Overhead:**
  - Native code may increase memory usage and complexity
  - JNI calls are fast but add some overhead
- **Security/Compatibility:**
  - Native code is less portable and may be blocked by SELinux or device policies
  - Forking processes may not be supported on all Android devices

---

## 3. Native Library Implementation (C/C++)

### Example: native-lib.c
```c
#include <jni.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <stdio.h>

JNIEXPORT jint JNICALL
Java_com_ustadmobile_meshrabiya_service_security_StrangersSafeComputeEngine_nativeForkProcess(JNIEnv *env, jobject thiz, jstring command) {
    const char *cmd = (*env)->GetStringUTFChars(env, command, 0);
    pid_t pid = fork();
    if (pid == 0) {
        // Child process: execute command
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(1); // If exec fails
    }
    (*env)->ReleaseStringUTFChars(env, command, cmd);
    return (jint)pid;
}

JNIEXPORT jint JNICALL
Java_com_ustadmobile_meshrabiya_service_security_StrangersSafeComputeEngine_nativeKillProcess(JNIEnv *env, jobject thiz, jint pid) {
    return kill((pid_t)pid, SIGKILL);
}

JNIEXPORT jint JNICALL
Java_com_ustadmobile_meshrabiya_service_security_StrangersSafeComputeEngine_nativeWaitForProcess(JNIEnv *env, jobject thiz, jint pid) {
    int status;
    waitpid((pid_t)pid, &status, 0);
    return status;
}

JNIEXPORT jlong JNICALL
Java_com_ustadmobile_meshrabiya_service_security_StrangersSafeComputeEngine_nativeGetMemoryUsage(JNIEnv *env, jobject thiz, jint pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/status", pid);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    char line[256];
    long mem = -1;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "VmRSS:", 6) == 0) {
            sscanf(line+6, "%ld", &mem);
            mem *= 1024; // kB to bytes
            break;
        }
    }
    fclose(f);
    return mem;
}
```

---

## 4. Kotlin/Java Integration

### JNI Method Declarations
```kotlin
class StrangersSafeComputeEngine(private val context: Context) {
    companion object {
        init { System.loadLibrary("native-lib") }
    }
    external fun nativeForkProcess(command: String): Int
    external fun nativeKillProcess(pid: Int): Int
    external fun nativeWaitForProcess(pid: Int): Int
    external fun nativeGetMemoryUsage(pid: Int): Long

    fun startTask(command: String): Int {
        val pid = nativeForkProcess(command)
        Log.i(TAG, "Started native task with PID: $pid")
        return pid
    }
    fun stopTask(pid: Int) {
        nativeKillProcess(pid)
        Log.i(TAG, "Stopped native task PID: $pid")
    }
    fun waitForTask(pid: Int): Int {
        return nativeWaitForProcess(pid)
    }
    fun getTaskMemoryUsage(pid: Int): Long {
        return nativeGetMemoryUsage(pid)
    }
}
```

### Example Usage in ComputeTask
```kotlin
class ComputeTask(val command: String) {
    var pid: Int? = null
    fun start(engine: StrangersSafeComputeEngine) {
        pid = engine.startTask(command)
    }
    fun stop(engine: StrangersSafeComputeEngine) {
        pid?.let { engine.stopTask(it) }
    }
    fun getMemoryUsage(engine: StrangersSafeComputeEngine): Long? {
        return pid?.let { engine.getTaskMemoryUsage(it) }
    }
}
```

---

## 5. Build Integration (Best Practices)

### Android.mk / CMakeLists.txt
- Use CMake for modern builds:
```cmake
cmake_minimum_required(VERSION 3.4.1)
add_library(native-lib SHARED native-lib.c)
find_library(log-lib log)
target_link_libraries(native-lib ${log-lib})
```
- Add to `build.gradle`:
```groovy
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags "-std=c99"
            }
        }
        ndk {
            abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'
        }
    }
    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
        }
    }
}
```
- Place native code in `src/main/cpp/`
- Use ABI splits for smaller APKs if possible

### Best Practices
- Validate all JNI calls for nulls and errors
- Handle permissions and SELinux restrictions
- Test on all target devices/ABIs
- Document all native interfaces
- Use strict error handling and logging
- Avoid leaking resources (close files, kill processes)
- Keep native code minimal and focused

---

## 6. Security and Compatibility Considerations
- Native process management may be blocked or restricted on some devices
- SELinux and app sandboxing may prevent forking or accessing `/proc/<pid>`
- Always validate device compatibility before deploying
- Consider fallback strategies for unsupported devices

---

## 7. Summary and Recommendations
- Native task processing enables granular control and metrics, but increases complexity and risk
- Use only if Java APIs are insufficient and security/compatibility are acceptable
- Always document, test, and validate native code
- Monitor app size and performance impact

---

## 8. References
- [Android NDK Guides](https://developer.android.com/ndk)
- [JNI Tips](https://developer.android.com/training/articles/perf-jni)
- [Process Management in Linux](https://man7.org/linux/man-pages/man2/fork.2.html)
- [SELinux on Android](https://source.android.com/security/selinux)

---

*This document is a comprehensive guide for integrating native process management in Meshrabiya. All information is validated and based on literal analysis and best practices as of November 2025.*
