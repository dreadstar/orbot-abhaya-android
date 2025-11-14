# User Guide: Task Submission & Monitoring

**Version**: 1.0.0  
**Last Updated**: November 14, 2025  
**Audience**: End Users

---

## Overview

This guide explains how to submit tasks to the Meshrabiya distributed compute network, monitor their execution, and retrieve results.

---

## Quick Start

### Submit Your First Task

```kotlin
// 1. Create a task
val task = Task(
    executable = Executable(
        runtime = RuntimeType.PYTHON,
        code = """
            print("Hello, Meshrabiya!")
        """.trimIndent()
    ),
    resourceLimits = ResourceLimits(
        maxMemoryMB = 128,
        timeoutSeconds = 30
    ),
    owner = "your-node-id"
)

// 2. Submit task
val taskId = taskManager.submitTask(task)
println("Task submitted: $taskId")

// 3. Wait for completion
val status = taskManager.waitForCompletion(taskId)

// 4. Get results
if (status.state == TaskState.COMPLETED) {
    val result = taskManager.getTaskResult(taskId, "your-node-id")
    println("Task completed: ${result.logs}")
}
```

---

## Task Types

### Python Tasks

```kotlin
val pythonTask = Task(
    executable = Executable(
        runtime = RuntimeType.PYTHON,
        code = """
            import sys
            with open('/sandbox/input/data.txt', 'r') as f:
                data = f.read()
            with open('/sandbox/output/result.txt', 'w') as f:
                f.write(data.upper())
        """.trimIndent()
    ),
    inputFiles = listOf(inputFileId),
    resourceLimits = ResourceLimits(maxMemoryMB = 128, timeoutSeconds = 60),
    owner = "your-node-id"
)
```

### Java Tasks

```kotlin
val javaTask = Task(
    executable = Executable(
        runtime = RuntimeType.JAVA,
        code = """
            import java.io.*;
            public class Main {
                public static void main(String[] args) throws Exception {
                    BufferedReader reader = new BufferedReader(
                        new FileReader("/sandbox/input/data.txt")
                    );
                    String line = reader.readLine();
                    reader.close();
                    
                    BufferedWriter writer = new BufferedWriter(
                        new FileWriter("/sandbox/output/result.txt")
                    );
                    writer.write(line.toUpperCase());
                    writer.close();
                }
            }
        """.trimIndent()
    ),
    inputFiles = listOf(inputFileId),
    resourceLimits = ResourceLimits(maxMemoryMB = 256, timeoutSeconds = 60),
    owner = "your-node-id"
)
```

---

## Working with Files

### Upload Input Files

```kotlin
// Store input file
val inputData = "Process this data".toByteArray()
val inputFileId = storageManager.storeFile(
    data = inputData,
    filename = "input.txt",
    owner = "your-node-id",
    recipients = listOf("your-node-id")
)

// Use in task
val task = Task(
    executable = pythonCode,
    inputFiles = listOf(inputFileId),  // Reference file by ID
    resourceLimits = limits,
    owner = "your-node-id"
)
```

### Retrieve Output Files

```kotlin
// Get task result
val result = taskManager.getTaskResult(taskId, "your-node-id")

// Download output files
for (fileId in result.outputFiles) {
    val fileData = storageManager.retrieveFile(fileId, "your-node-id")
    println("Output: ${String(fileData)}")
}
```

---

## Monitoring Tasks

### Check Task Status

```kotlin
val status = taskManager.getTaskStatus(taskId)

println("State: ${status.state}")
println("Progress: ${(status.progress * 100).toInt()}%")
println("Assigned Node: ${status.assignedNode}")

when (status.state) {
    TaskState.SUBMITTED -> println("Waiting for scheduling")
    TaskState.SCHEDULED -> println("Assigned to node, waiting for execution")
    TaskState.RUNNING -> println("Currently executing")
    TaskState.COMPLETED -> println("Execution complete")
    TaskState.FAILED -> println("Execution failed: ${status.error}")
    TaskState.CANCELLED -> println("Task was cancelled")
}
```

### Monitor Progress

```kotlin
val monitorJob = taskManager.monitorProgress(taskId) { status ->
    println("[${status.taskId}] ${status.state} - ${(status.progress * 100).toInt()}%")
}

// ... do other work

monitorJob.cancel()  // Stop monitoring
```

### List Your Tasks

```kotlin
// List all tasks
val allTasks = taskManager.listTasks(owner = "your-node-id")
println("Total tasks: ${allTasks.size}")

// List running tasks
val runningTasks = taskManager.listTasks(
    owner = "your-node-id",
    state = TaskState.RUNNING
)
println("Running: ${runningTasks.size}")
```

---

## Resource Limits

### Setting Appropriate Limits

```kotlin
// Small task (text processing)
val smallTaskLimits = ResourceLimits(
    maxMemoryMB = 64,
    maxCpuCores = 1,
    timeoutSeconds = 30,
    networkAccess = false
)

// Medium task (data analysis)
val mediumTaskLimits = ResourceLimits(
    maxMemoryMB = 256,
    maxCpuCores = 2,
    timeoutSeconds = 300,
    networkAccess = false
)

// Large task (ML inference)
val largeTaskLimits = ResourceLimits(
    maxMemoryMB = 512,
    maxCpuCores = 4,
    timeoutSeconds = 600,
    networkAccess = false
)
```

### Understanding Limits

- **maxMemoryMB**: Maximum RAM the task can use. Task is killed if exceeded.
- **maxCpuCores**: Number of CPU cores available to task.
- **timeoutSeconds**: Maximum execution time. Task is killed if exceeded.
- **networkAccess**: Whether task can access network (usually disabled for security).

---

## Error Handling

### Common Errors and Solutions

**Task Submission Failed**:
```kotlin
try {
    val taskId = taskManager.submitTask(task)
} catch (e: InvalidTaskException) {
    // Fix: Check task specification
    println("Invalid task: ${e.message}")
} catch (e: InsufficientResourcesException) {
    // Fix: Wait and retry, or reduce resource requirements
    println("No available nodes, retrying...")
    delay(30_000)
    // ... retry
}
```

**Task Execution Failed**:
```kotlin
val status = taskManager.getTaskStatus(taskId)
if (status.state == TaskState.FAILED) {
    println("Task failed: ${status.error}")
    
    // Common causes:
    // - Syntax error in code
    // - Memory limit exceeded
    // - Timeout exceeded
    // - File not found
}
```

**Result Retrieval Failed**:
```kotlin
try {
    val result = taskManager.getTaskResult(taskId, "your-node-id")
} catch (e: TaskNotCompleteException) {
    // Fix: Wait for task to complete
    taskManager.waitForCompletion(taskId)
    val result = taskManager.getTaskResult(taskId, "your-node-id")
}
```

---

## Best Practices

### 1. Set Realistic Timeouts

```kotlin
// ✅ GOOD: Appropriate timeout
val limits = ResourceLimits(
    maxMemoryMB = 128,
    timeoutSeconds = 60  // Enough for task, not excessive
)

// ❌ BAD: Excessive timeout
val limits = ResourceLimits(
    maxMemoryMB = 128,
    timeoutSeconds = 3600  // 1 hour for 30-second task
)
```

### 2. Handle Failures Gracefully

```kotlin
// ✅ GOOD: Retry logic
suspend fun submitTaskWithRetry(task: Task, maxRetries: Int = 3): String {
    repeat(maxRetries) { attempt ->
        try {
            return taskManager.submitTask(task)
        } catch (e: InsufficientResourcesException) {
            if (attempt == maxRetries - 1) throw e
            delay(10_000)
        }
    }
    throw Exception("Failed after $maxRetries retries")
}
```

### 3. Cleanup After Completion

```kotlin
// ✅ GOOD: Cleanup resources
val taskId = taskManager.submitTask(task)
try {
    val result = taskManager.waitForCompletion(taskId)
    // ... process result
} finally {
    // Delete temporary files
    for (fileId in task.inputFiles) {
        storageManager.deleteFile(fileId, "your-node-id")
    }
}
```

---

## Troubleshooting

### Task Stuck in SUBMITTED State

**Cause**: No available nodes or scheduling delay

**Solution**:
1. Check network connectivity
2. Verify nodes are online: `meshNetwork.getActiveNodes()`
3. Wait 30-60 seconds for scheduling

### Task Times Out

**Cause**: Task execution exceeds timeout limit

**Solution**:
1. Increase timeout in ResourceLimits
2. Optimize task code for performance
3. Split task into smaller sub-tasks

### Memory Limit Exceeded

**Cause**: Task uses more memory than allocated

**Solution**:
1. Increase maxMemoryMB in ResourceLimits
2. Optimize task memory usage
3. Process data in chunks instead of loading all at once

### Output Files Not Found

**Cause**: Task didn't write to correct location

**Solution**:
Ensure task writes to `/sandbox/output/` directory:
```python
# ✅ GOOD
with open('/sandbox/output/result.txt', 'w') as f:
    f.write(result)

# ❌ BAD
with open('result.txt', 'w') as f:  # Wrong location
    f.write(result)
```

---

## FAQ

**Q: How long do tasks take to execute?**
A: Depends on task complexity and resource availability. Typical range: 30 seconds to 5 minutes.

**Q: Can I cancel a running task?**
A: Yes, use `taskManager.cancelTask(taskId, "your-node-id")`.

**Q: How many tasks can I run simultaneously?**
A: No limit, but tasks are queued if no nodes are available.

**Q: Are my files encrypted?**
A: Yes, all files are encrypted end-to-end with per-task isolation.

**Q: Can tasks access the internet?**
A: No, network access is disabled by default for security.

---

## See Also

- [TaskManager API Documentation](../api/TaskManager_API.md)
- [DistributedStorageManager API Documentation](../api/DistributedStorageManager_API.md)
- [Advanced Task Patterns](TaskPatterns.md)

---

**Document Version**: 1.0.0  
**Last Updated**: November 14, 2025  
**Maintainer**: Meshrabiya Core Team
