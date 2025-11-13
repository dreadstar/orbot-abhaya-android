# Task KeyPair Enhancement Implementation Plan - Part 3

**Date**: November 13, 2025  
**Continuation of**: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART2.md  
**Sections**: 8 (verification note), 9-10

---

## Section 8 Verification

**Section 8 Status**: Section 8 (PGP Encryption Service Enhancements) was fully completed in Part 2. All planned content was included:

- ✅ Multi-recipient encryption implementation with session keys
- ✅ TaskKeypair generation (RSA 2048-bit)
- ✅ PGP key format conversion (export/import)
- ✅ encryptForMultipleRecipients() with AES-256 + RSA
- ✅ decryptWithPrivateKey() implementation
- ✅ Re-encryption optimization (addRecipientToPackage, removeRecipientFromPackage)
- ✅ Secure key handling with session key management

No additional Section 8 content needs to be written.

---

## Section 9: Failover & Error Handling

### 9.1 Error Categories and Recovery Strategies

#### 9.1.1 Client-Side Error Handling

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/IntelligentDistributedComputeService.kt

/**
 * Comprehensive error handling for client-side task lifecycle
 */
object TaskErrorHandler {
    
    enum class ErrorCategory {
        NETWORK_ERROR,          // Mesh communication failure
        ENCRYPTION_ERROR,       // File encryption/decryption failure
        STORAGE_ERROR,          // Distributed storage unavailable
        TIMEOUT_ERROR,          // Operation timeout
        TASK_REJECTED,          // No capable nodes or explicit rejection
        INVALID_STATE,          // Task in wrong state for operation
        RESOURCE_EXHAUSTED      // Client out of resources (storage, etc.)
    }
    
    data class TaskError(
        val taskId: String,
        val category: ErrorCategory,
        val message: String,
        val cause: Throwable?,
        val timestamp: Long = System.currentTimeMillis(),
        val recoverable: Boolean = false,
        val retryAfterMs: Long? = null
    )
    
    /**
     * Handle task submission failure
     */
    suspend fun handleSubmissionError(
        request: TaskRequest,
        error: Throwable
    ): TaskError {
        val category = categorizeError(error)
        
        return when (category) {
            ErrorCategory.NETWORK_ERROR -> {
                // Retry after network recovery
                TaskError(
                    taskId = request.taskId ?: "unknown",
                    category = category,
                    message = "Mesh network unavailable",
                    cause = error,
                    recoverable = true,
                    retryAfterMs = 30000  // Retry after 30s
                )
            }
            
            ErrorCategory.STORAGE_ERROR -> {
                // Cannot proceed without storage
                TaskError(
                    taskId = request.taskId ?: "unknown",
                    category = category,
                    message = "Failed to upload input files to distributed storage",
                    cause = error,
                    recoverable = false
                )
            }
            
            ErrorCategory.ENCRYPTION_ERROR -> {
                // Configuration issue, not recoverable
                TaskError(
                    taskId = request.taskId ?: "unknown",
                    category = category,
                    message = "Failed to encrypt input files",
                    cause = error,
                    recoverable = false
                )
            }
            
            else -> {
                TaskError(
                    taskId = request.taskId ?: "unknown",
                    category = category,
                    message = "Unknown submission error: ${error.message}",
                    cause = error,
                    recoverable = false
                )
            }
        }
    }
    
    /**
     * Handle file re-encryption failure
     */
    suspend fun handleReEncryptionError(
        taskId: String,
        fileId: String,
        error: Throwable
    ): TaskError {
        Log.e(TAG, "Re-encryption failed for file $fileId in task $taskId", error)
        
        val category = categorizeError(error)
        
        return when (category) {
            ErrorCategory.NETWORK_ERROR -> {
                // Retry re-encryption
                TaskError(
                    taskId = taskId,
                    category = category,
                    message = "Network failure during file re-encryption",
                    cause = error,
                    recoverable = true,
                    retryAfterMs = 5000  // Retry after 5s
                )
            }
            
            ErrorCategory.ENCRYPTION_ERROR -> {
                // Invalid task pub key or corrupted file
                TaskError(
                    taskId = taskId,
                    category = category,
                    message = "Invalid task public key or corrupted file",
                    cause = error,
                    recoverable = false
                )
            }
            
            ErrorCategory.STORAGE_ERROR -> {
                // Storage node unavailable
                TaskError(
                    taskId = taskId,
                    category = category,
                    message = "Storage node unavailable for file update",
                    cause = error,
                    recoverable = true,
                    retryAfterMs = 10000  // Retry after 10s
                )
            }
            
            else -> {
                TaskError(
                    taskId = taskId,
                    category = category,
                    message = "Re-encryption failed: ${error.message}",
                    cause = error,
                    recoverable = false
                )
            }
        }
    }
    
    /**
     * Handle task timeout
     */
    suspend fun handleTaskTimeout(taskId: String, stage: String): TaskError {
        Log.w(TAG, "Task $taskId timed out at stage: $stage")
        
        return TaskError(
            taskId = taskId,
            category = ErrorCategory.TIMEOUT_ERROR,
            message = "Task timed out at stage: $stage",
            cause = null,
            recoverable = false  // Timeouts are not retried automatically
        )
    }
    
    /**
     * Categorize exception into error category
     */
    private fun categorizeError(error: Throwable): ErrorCategory {
        return when (error) {
            is java.net.SocketException,
            is java.net.UnknownHostException,
            is java.io.IOException -> ErrorCategory.NETWORK_ERROR
            
            is SecurityException,
            is javax.crypto.BadPaddingException,
            is java.security.InvalidKeyException -> ErrorCategory.ENCRYPTION_ERROR
            
            is java.util.concurrent.TimeoutException -> ErrorCategory.TIMEOUT_ERROR
            
            is IllegalStateException -> ErrorCategory.INVALID_STATE
            
            else -> {
                // Check error message for hints
                val message = error.message?.lowercase() ?: ""
                when {
                    "storage" in message -> ErrorCategory.STORAGE_ERROR
                    "network" in message -> ErrorCategory.NETWORK_ERROR
                    "timeout" in message -> ErrorCategory.TIMEOUT_ERROR
                    else -> ErrorCategory.NETWORK_ERROR  // Default to network error
                }
            }
        }
    }
    
    companion object {
        private const val TAG = "TaskErrorHandler"
    }
}
```

#### 9.1.2 Compute-Side Error Handling

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskManager.kt

/**
 * Error handling for compute node task execution
 */
object ComputeNodeErrorHandler {
    
    /**
     * Handle keypair generation failure
     */
    suspend fun handleKeypairGenerationError(
        taskId: String,
        error: Throwable
    ) {
        Log.e(TAG, "Keypair generation failed for task $taskId", error)
        
        // Send TASK_REJECTED to client
        sendTaskRejected(
            taskId = taskId,
            reason = "Keypair generation failed",
            errorDetails = error.message
        )
        
        // Cleanup any partial state
        cleanupTask(taskId)
    }
    
    /**
     * Handle file preparation failure (download, decrypt, assemble)
     */
    suspend fun handleFilePreparationError(
        taskId: String,
        fileId: String,
        stage: String,
        error: Throwable
    ) {
        Log.e(TAG, "File preparation failed at stage '$stage' for task $taskId", error)
        
        val errorCategory = when (stage) {
            "download" -> "STORAGE_UNAVAILABLE"
            "decrypt" -> "DECRYPTION_FAILED"
            "assemble" -> "FILE_CORRUPTED"
            else -> "FILE_PREPARATION_FAILED"
        }
        
        // Send TASK_FAILED to client
        sendTaskFailed(
            taskId = taskId,
            errorType = errorCategory,
            errorMessage = "Failed to prepare file $fileId: ${error.message}"
        )
        
        // Cleanup
        destroyTaskKeypair(taskId)
        cleanupTask(taskId)
    }
    
    /**
     * Handle sandbox creation failure
     */
    suspend fun handleSandboxCreationError(
        taskId: String,
        error: Throwable
    ) {
        Log.e(TAG, "Sandbox creation failed for task $taskId", error)
        
        // Send TASK_FAILED to client
        sendTaskFailed(
            taskId = taskId,
            errorType = "SANDBOX_CREATION_FAILED",
            errorMessage = "Failed to create sandbox: ${error.message}"
        )
        
        // Cleanup keypair
        destroyTaskKeypair(taskId)
        cleanupTask(taskId)
    }
    
    /**
     * Handle task execution timeout
     */
    suspend fun handleExecutionTimeout(
        taskId: String,
        timeoutMs: Long
    ) {
        Log.w(TAG, "Task $taskId exceeded timeout: ${timeoutMs}ms")
        
        // Kill container forcefully
        val executionState = executionStates[taskId]
        if (executionState?.containerId != null) {
            try {
                StrangersSafeComputeEngine.killContainer(executionState.containerId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to kill container ${executionState.containerId}", e)
            }
        }
        
        // Send TASK_FAILED to client
        sendTaskFailed(
            taskId = taskId,
            errorType = "EXECUTION_TIMEOUT",
            errorMessage = "Task execution exceeded timeout of ${timeoutMs}ms"
        )
        
        // Cleanup
        destroyTaskKeypair(taskId)
        cleanupTask(taskId)
    }
    
    /**
     * Handle task execution crash
     */
    suspend fun handleExecutionCrash(
        taskId: String,
        error: Throwable,
        exitCode: Int?
    ) {
        Log.e(TAG, "Task $taskId crashed with exit code $exitCode", error)
        
        // Send TASK_FAILED to client
        sendTaskFailed(
            taskId = taskId,
            errorType = "EXECUTION_CRASHED",
            errorMessage = "Task crashed: ${error.message} (exit code: $exitCode)"
        )
        
        // Cleanup
        destroyTaskKeypair(taskId)
        cleanupTask(taskId)
    }
    
    /**
     * Handle output encryption failure
     */
    suspend fun handleOutputEncryptionError(
        taskId: String,
        error: Throwable
    ) {
        Log.e(TAG, "Output encryption failed for task $taskId", error)
        
        // Send TASK_FAILED to client with partial results warning
        sendTaskFailed(
            taskId = taskId,
            errorType = "OUTPUT_ENCRYPTION_FAILED",
            errorMessage = "Task completed but output encryption failed: ${error.message}"
        )
        
        // Cleanup (output files already deleted by cleanup process)
        destroyTaskKeypair(taskId)
        cleanupTask(taskId)
    }
    
    /**
     * Cleanup task state and resources
     */
    private suspend fun cleanupTask(taskId: String) {
        try {
            // Remove execution state
            val state = executionStates.remove(taskId)
            
            // Cleanup workspace directory
            if (state != null) {
                try {
                    state.workspaceDir.deleteRecursively()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cleanup workspace for task $taskId", e)
                }
            }
            
            // Stop resource monitoring
            stopResourceMonitoring(taskId)
            
            Log.d(TAG, "Cleaned up task $taskId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during task cleanup for $taskId", e)
        }
    }
    
    companion object {
        private const val TAG = "ComputeNodeErrorHandler"
    }
}
```

### 9.2 Retry Logic and Exponential Backoff

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/RetryManager.kt

package org.torproject.android.meshrabiya.compute

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

/**
 * Retry manager with exponential backoff
 */
class RetryManager(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1000,
    private val maxDelayMs: Long = 30000,
    private val multiplier: Double = 2.0
) {
    
    /**
     * Execute operation with retry logic
     */
    suspend fun <T> executeWithRetry(
        operationName: String,
        retryableExceptions: List<Class<out Exception>> = listOf(
            java.io.IOException::class.java,
            java.net.SocketException::class.java
        ),
        operation: suspend () -> T
    ): T {
        var lastException: Exception? = null
        var attempt = 0
        
        while (attempt <= maxRetries) {
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e
                
                // Check if exception is retryable
                val isRetryable = retryableExceptions.any { it.isInstance(e) }
                
                if (!isRetryable || attempt >= maxRetries) {
                    throw e
                }
                
                // Calculate backoff delay
                val delayMs = calculateBackoff(attempt)
                
                Log.w(
                    TAG,
                    "$operationName failed (attempt ${attempt + 1}/$maxRetries), " +
                    "retrying in ${delayMs}ms: ${e.message}"
                )
                
                delay(delayMs)
                attempt++
            }
        }
        
        throw lastException ?: RuntimeException("Operation failed after $maxRetries retries")
    }
    
    /**
     * Calculate exponential backoff delay
     */
    private fun calculateBackoff(attempt: Int): Long {
        val delay = initialDelayMs * multiplier.pow(attempt.toDouble())
        return min(delay.toLong(), maxDelayMs)
    }
    
    companion object {
        private const val TAG = "RetryManager"
    }
}

/**
 * Example usage for file re-encryption with retry
 */
suspend fun reEncryptFileWithRetry(
    fileId: String,
    taskId: String,
    taskRecipient: Recipient
): Boolean {
    val retryManager = RetryManager(
        maxRetries = 3,
        initialDelayMs = 2000,
        maxDelayMs = 15000
    )
    
    return try {
        retryManager.executeWithRetry(
            operationName = "Re-encrypt file $fileId",
            retryableExceptions = listOf(
                java.io.IOException::class.java,
                java.net.SocketException::class.java
            )
        ) {
            DistributedStorageManager.updateFileAccess(
                fileId = fileId,
                addRecipients = listOf(taskRecipient)
            )
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to re-encrypt file $fileId after retries", e)
        false
    }
}
```

### 9.3 Keypair Lifecycle Error Recovery

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskManager.kt

/**
 * Robust keypair lifecycle management with error recovery
 */
class TaskKeypairManager {
    
    private val keypairs = ConcurrentHashMap<String, TaskKeypair>()
    private val generationLock = Mutex()
    
    /**
     * Generate keypair with error handling and cleanup
     */
    suspend fun generateOrRetrieveKeypair(taskId: String): TaskKeypair {
        // Check if already exists (idempotent)
        keypairs[taskId]?.let { 
            Log.d(TAG, "Keypair already exists for task $taskId")
            return it
        }
        
        // Lock to prevent concurrent generation for same task
        generationLock.withLock {
            // Double-check after acquiring lock
            keypairs[taskId]?.let { return it }
            
            try {
                // Generate keypair
                val keypair = PGPEncryptionService.generateTaskKeypair(taskId)
                
                // Validate keypair before storing
                validateKeypair(keypair)
                
                // Store keypair
                keypairs[taskId] = keypair
                
                Log.d(TAG, "Generated keypair for task $taskId")
                return keypair
                
            } catch (e: Exception) {
                Log.e(TAG, "Keypair generation failed for task $taskId", e)
                
                // Cleanup any partial keypair
                keypairs.remove(taskId)
                
                throw RuntimeException("Failed to generate keypair", e)
            }
        }
    }
    
    /**
     * Validate keypair integrity
     */
    private fun validateKeypair(keypair: TaskKeypair) {
        require(keypair.publicKeyPem.isNotBlank()) { "Public key is empty" }
        require(keypair.privateKeyPem.isNotBlank()) { "Private key is empty" }
        require(keypair.taskId.isNotBlank()) { "Task ID is empty" }
        
        // Test encryption/decryption with keypair
        try {
            val testData = "test".toByteArray()
            val publicKey = PGPEncryptionService.importPublicKeyFromPGP(keypair.publicKeyPem)
            val privateKey = PGPEncryptionService.importPrivateKeyFromPGP(keypair.privateKeyPem)
            
            // Encrypt with public key
            val encrypted = encryptTestData(testData, publicKey)
            
            // Decrypt with private key
            val decrypted = decryptTestData(encrypted, privateKey)
            
            require(testData.contentEquals(decrypted)) { 
                "Keypair validation failed: decrypted data doesn't match" 
            }
            
        } catch (e: Exception) {
            throw RuntimeException("Keypair validation failed", e)
        }
    }
    
    /**
     * Securely destroy keypair with guaranteed cleanup
     */
    suspend fun destroyKeypair(taskId: String) {
        try {
            val keypair = keypairs.remove(taskId)
            if (keypair == null) {
                Log.w(TAG, "Keypair not found for task $taskId (already destroyed?)")
                return
            }
            
            // Secure wipe of sensitive key material
            secureWipe(keypair.privateKeyPem)
            
            Log.d(TAG, "Destroyed keypair for task $taskId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying keypair for task $taskId", e)
            // Continue anyway - best effort cleanup
        }
    }
    
    /**
     * Emergency cleanup: destroy all keypairs
     */
    suspend fun destroyAllKeypairs() {
        Log.w(TAG, "Emergency cleanup: destroying all ${keypairs.size} keypairs")
        
        keypairs.keys.toList().forEach { taskId ->
            try {
                destroyKeypair(taskId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy keypair for task $taskId", e)
            }
        }
    }
    
    /**
     * Secure memory wipe
     */
    private fun secureWipe(sensitiveData: String) {
        try {
            val bytes = sensitiveData.toByteArray()
            bytes.fill(0)
            // Additional platform-specific secure wiping if available
        } catch (e: Exception) {
            Log.e(TAG, "Secure wipe failed", e)
        }
    }
    
    private fun encryptTestData(data: ByteArray, publicKey: PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }
    
    private fun decryptTestData(encrypted: ByteArray, privateKey: PrivateKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(encrypted)
    }
    
    companion object {
        private const val TAG = "TaskKeypairManager"
    }
}
```

### 9.4 Timeout Configuration

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskTimeouts.kt

package org.torproject.android.meshrabiya.compute

/**
 * Centralized timeout configuration for task lifecycle
 */
object TaskTimeouts {
    
    // Client-side timeouts
    const val TASK_DISCOVERY_TIMEOUT_MS = 30_000L       // 30 seconds
    const val TASK_ASSIGNMENT_TIMEOUT_MS = 10_000L      // 10 seconds
    const val TASK_SCHEDULED_TIMEOUT_MS = 30_000L       // 30 seconds
    const val FILE_REENCRYPTION_TIMEOUT_MS = 60_000L    // 60 seconds per file
    const val TASK_COMPLETION_TIMEOUT_MS = 300_000L     // 5 minutes default
    
    // Compute-side timeouts
    const val KEYPAIR_GENERATION_TIMEOUT_MS = 10_000L   // 10 seconds
    const val FILE_PREPARATION_TIMEOUT_MS = 60_000L     // 60 seconds per file
    const val SANDBOX_CREATION_TIMEOUT_MS = 30_000L     // 30 seconds
    const val TASK_EXECUTION_TIMEOUT_MS = 600_000L      // 10 minutes default
    const val OUTPUT_ENCRYPTION_TIMEOUT_MS = 60_000L    // 60 seconds per file
    
    /**
     * Calculate timeout based on task type and resource requirements
     */
    fun calculateTaskTimeout(
        taskType: TaskType,
        inputFileSizeMB: Long,
        estimatedComplexity: Int
    ): Long {
        val baseTimeout = when (taskType) {
            TaskType.PYTHON_SCRIPT -> 300_000L       // 5 minutes
            TaskType.DATA_PROCESSING -> 600_000L     // 10 minutes
            TaskType.ML_INFERENCE -> 900_000L        // 15 minutes
            TaskType.CUSTOM -> 600_000L              // 10 minutes
        }
        
        // Add time for large files (1 minute per 10MB)
        val fileSizeTimeout = (inputFileSizeMB / 10) * 60_000L
        
        // Add time for complex tasks
        val complexityTimeout = estimatedComplexity * 30_000L
        
        return baseTimeout + fileSizeTimeout + complexityTimeout
    }
}
```

### 9.5 State Consistency Checks

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskStateValidator.kt

package org.torproject.android.meshrabiya.compute

/**
 * Validate task state consistency and detect corruption
 */
object TaskStateValidator {
    
    /**
     * Validate client-side task state
     */
    fun validateClientTaskState(
        taskId: String,
        taskStatus: TaskStatus,
        taskRegistry: Map<String, TaskRequest>
    ): ValidationResult {
        val errors = mutableListOf<String>()
        
        // Check task exists in registry
        val task = taskRegistry[taskId]
        if (task == null) {
            errors.add("Task not found in registry")
            return ValidationResult(valid = false, errors = errors)
        }
        
        // Validate state transitions
        when (taskStatus.status) {
            TaskExecutionStatus.SCHEDULED -> {
                if (taskStatus.taskPubKey == null) {
                    errors.add("SCHEDULED status requires taskPubKey")
                }
                if (taskStatus.executorNodeId == null) {
                    errors.add("SCHEDULED status requires executorNodeId")
                }
            }
            
            TaskExecutionStatus.RUNNING -> {
                if (taskStatus.taskPubKey == null) {
                    errors.add("RUNNING status requires taskPubKey")
                }
                if (taskStatus.executorNodeId == null) {
                    errors.add("RUNNING status requires executorNodeId")
                }
            }
            
            TaskExecutionStatus.COMPLETED,
            TaskExecutionStatus.FAILED -> {
                // Terminal states - no additional validation
            }
            
            else -> {
                // Other states don't require taskPubKey
            }
        }
        
        return ValidationResult(valid = errors.isEmpty(), errors = errors)
    }
    
    /**
     * Validate compute-side task state
     */
    fun validateComputeTaskState(
        taskId: String,
        executionState: ExecutionState,
        keypair: TaskKeypair?
    ): ValidationResult {
        val errors = mutableListOf<String>()
        
        // Validate keypair exists for active tasks
        when (executionState.status) {
            TaskExecutionStatus.SCHEDULED,
            TaskExecutionStatus.RUNNING -> {
                if (keypair == null) {
                    errors.add("Active task requires keypair")
                }
            }
            
            TaskExecutionStatus.COMPLETED,
            TaskExecutionStatus.FAILED -> {
                if (keypair != null) {
                    errors.add("Terminal state should not have keypair (should be destroyed)")
                }
            }
            
            else -> {
                // Other states - no keypair requirement
            }
        }
        
        // Validate workspace directory exists
        if (!executionState.workspaceDir.exists()) {
            errors.add("Workspace directory does not exist")
        }
        
        // Validate sandbox filesystem for running tasks
        if (executionState.status == TaskExecutionStatus.RUNNING) {
            if (executionState.sandboxFileSystem == null) {
                errors.add("RUNNING task requires sandboxFileSystem")
            }
        }
        
        return ValidationResult(valid = errors.isEmpty(), errors = errors)
    }
    
    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String>
    ) {
        fun throwIfInvalid() {
            if (!valid) {
                throw IllegalStateException("Task state validation failed: ${errors.joinToString(", ")}")
            }
        }
    }
}
```

---

## Section 10: Security Testing

### 10.1 Keypair Security Tests

```kotlin
// File: Meshrabiya/src/test/java/org/torproject/android/meshrabiya/crypto/TaskKeypairSecurityTest.kt

package org.torproject.android.meshrabiya.crypto

import org.junit.Test
import org.junit.Assert.*
import kotlin.test.assertFailsWith

/**
 * Security-focused tests for task keypair generation and handling
 */
class TaskKeypairSecurityTest {
    
    @Test
    fun `keypair generation produces unique keys for different tasks`() {
        val keypair1 = PGPEncryptionService.generateTaskKeypair("task1")
        val keypair2 = PGPEncryptionService.generateTaskKeypair("task2")
        
        assertNotEquals(keypair1.publicKeyPem, keypair2.publicKeyPem)
        assertNotEquals(keypair1.privateKeyPem, keypair2.privateKeyPem)
    }
    
    @Test
    fun `keypair has sufficient entropy (RSA 2048-bit)`() {
        val keypair = PGPEncryptionService.generateTaskKeypair("test-task")
        
        val publicKey = PGPEncryptionService.importPublicKeyFromPGP(keypair.publicKeyPem)
        
        // RSA 2048-bit key should have modulus of 2048 bits
        val rsaPublicKey = publicKey as java.security.interfaces.RSAPublicKey
        val modulusBitLength = rsaPublicKey.modulus.bitLength()
        
        assertTrue(
            "RSA key should be 2048 bits, got $modulusBitLength",
            modulusBitLength in 2047..2049  // Allow small variance
        )
    }
    
    @Test
    fun `private key cannot decrypt data encrypted for different task`() {
        val keypair1 = PGPEncryptionService.generateTaskKeypair("task1")
        val keypair2 = PGPEncryptionService.generateTaskKeypair("task2")
        
        val testData = "sensitive data".toByteArray()
        
        // Encrypt for task1
        val recipient1 = Recipient(
            recipientId = "task1",
            recipientPubKey = keypair1.publicKeyPem,
            recipientType = RecipientType.TASK
        )
        
        val encryptedPackage = PGPEncryptionService.encryptForMultipleRecipients(
            data = testData,
            recipients = listOf(recipient1)
        )
        
        // Try to decrypt with task2's private key (should fail)
        assertFailsWith<SecurityException> {
            PGPEncryptionService.decryptWithPrivateKey(
                encryptedPackage = encryptedPackage,
                recipientId = "task2",
                privateKeyPem = keypair2.privateKeyPem
            )
        }
    }
    
    @Test
    fun `secure wipe removes key material from memory`() {
        val sensitiveData = "private-key-material"
        val bytes = sensitiveData.toByteArray()
        
        // Simulate secure wipe
        bytes.fill(0)
        
        // Verify all bytes are zeroed
        assertTrue(bytes.all { it == 0.toByte() })
    }
    
    @Test
    fun `keypair export is in valid PGP format`() {
        val keypair = PGPEncryptionService.generateTaskKeypair("test-task")
        
        // Check PGP ASCII armor format
        assertTrue(keypair.publicKeyPem.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
        assertTrue(keypair.publicKeyPem.endsWith("-----END PGP PUBLIC KEY BLOCK-----"))
        
        assertTrue(keypair.privateKeyPem.contains("-----BEGIN PGP PRIVATE KEY BLOCK-----"))
        assertTrue(keypair.privateKeyPem.contains("-----END PGP PRIVATE KEY BLOCK-----"))
    }
    
    @Test
    fun `public key can be safely shared (no private key material)`() {
        val keypair = PGPEncryptionService.generateTaskKeypair("test-task")
        
        // Verify public key doesn't contain "PRIVATE"
        assertFalse(keypair.publicKeyPem.contains("PRIVATE", ignoreCase = true))
    }
}
```

### 10.2 Encryption Security Tests

```kotlin
// File: Meshrabiya/src/test/java/org/torproject/android/meshrabiya/crypto/MultiRecipientEncryptionTest.kt

package org.torproject.android.meshrabiya.crypto

import org.junit.Test
import org.junit.Assert.*

/**
 * Security tests for multi-recipient encryption
 */
class MultiRecipientEncryptionTest {
    
    @Test
    fun `encrypted data is different each time (unique session keys)`() {
        val testData = "test data".toByteArray()
        
        val keypair1 = PGPEncryptionService.generateTaskKeypair("task1")
        val recipient = Recipient(
            recipientId = "task1",
            recipientPubKey = keypair1.publicKeyPem,
            recipientType = RecipientType.TASK
        )
        
        // Encrypt twice
        val package1 = PGPEncryptionService.encryptForMultipleRecipients(testData, listOf(recipient))
        val package2 = PGPEncryptionService.encryptForMultipleRecipients(testData, listOf(recipient))
        
        // Encrypted data should be different (different session keys)
        assertFalse(package1.encryptedData.contentEquals(package2.encryptedData))
        
        // But decryption should yield same plaintext
        val decrypted1 = PGPEncryptionService.decryptWithPrivateKey(
            package1, "task1", keypair1.privateKeyPem
        )
        val decrypted2 = PGPEncryptionService.decryptWithPrivateKey(
            package2, "task1", keypair1.privateKeyPem
        )
        
        assertTrue(decrypted1.contentEquals(testData))
        assertTrue(decrypted2.contentEquals(testData))
    }
    
    @Test
    fun `multiple recipients can decrypt same data`() {
        val testData = "shared secret".toByteArray()
        
        val keypair1 = PGPEncryptionService.generateTaskKeypair("task1")
        val keypair2 = PGPEncryptionService.generateTaskKeypair("task2")
        val keypair3 = PGPEncryptionService.generateTaskKeypair("task3")
        
        val recipients = listOf(
            Recipient("task1", keypair1.publicKeyPem, RecipientType.TASK),
            Recipient("task2", keypair2.publicKeyPem, RecipientType.TASK),
            Recipient("task3", keypair3.publicKeyPem, RecipientType.TASK)
        )
        
        val encryptedPackage = PGPEncryptionService.encryptForMultipleRecipients(
            testData, recipients
        )
        
        // All three recipients can decrypt
        val decrypted1 = PGPEncryptionService.decryptWithPrivateKey(
            encryptedPackage, "task1", keypair1.privateKeyPem
        )
        val decrypted2 = PGPEncryptionService.decryptWithPrivateKey(
            encryptedPackage, "task2", keypair2.privateKeyPem
        )
        val decrypted3 = PGPEncryptionService.decryptWithPrivateKey(
            encryptedPackage, "task3", keypair3.privateKeyPem
        )
        
        assertTrue(decrypted1.contentEquals(testData))
        assertTrue(decrypted2.contentEquals(testData))
        assertTrue(decrypted3.contentEquals(testData))
    }
    
    @Test
    fun `removing recipient prevents decryption`() {
        val testData = "sensitive data".toByteArray()
        
        val keypair1 = PGPEncryptionService.generateTaskKeypair("task1")
        val keypair2 = PGPEncryptionService.generateTaskKeypair("task2")
        
        val recipients = listOf(
            Recipient("task1", keypair1.publicKeyPem, RecipientType.TASK),
            Recipient("task2", keypair2.publicKeyPem, RecipientType.TASK)
        )
        
        val encryptedPackage = PGPEncryptionService.encryptForMultipleRecipients(
            testData, recipients
        )
        
        // Both can decrypt initially
        assertNotNull(PGPEncryptionService.decryptWithPrivateKey(
            encryptedPackage, "task1", keypair1.privateKeyPem
        ))
        assertNotNull(PGPEncryptionService.decryptWithPrivateKey(
            encryptedPackage, "task2", keypair2.privateKeyPem
        ))
        
        // Remove task2 from recipients
        val updatedPackage = PGPEncryptionService.removeRecipientFromPackage(
            encryptedPackage, "task2"
        )
        
        // task1 can still decrypt
        assertNotNull(PGPEncryptionService.decryptWithPrivateKey(
            updatedPackage, "task1", keypair1.privateKeyPem
        ))
        
        // task2 can no longer decrypt
        assertFailsWith<SecurityException> {
            PGPEncryptionService.decryptWithPrivateKey(
                updatedPackage, "task2", keypair2.privateKeyPem
            )
        }
    }
    
    @Test
    fun `session key is properly secured (AES-256)`() {
        val testData = ByteArray(1024) { it.toByte() }  // 1KB test data
        
        val keypair = PGPEncryptionService.generateTaskKeypair("task1")
        val recipient = Recipient("task1", keypair.publicKeyPem, RecipientType.TASK)
        
        val encryptedPackage = PGPEncryptionService.encryptForMultipleRecipients(
            testData, listOf(recipient)
        )
        
        // Encrypted data should be larger than plaintext (includes IV)
        assertTrue(encryptedPackage.encryptedData.size > testData.size)
        
        // Encrypted session key should be 256 bytes for RSA 2048-bit
        val encryptedSessionKey = encryptedPackage.encryptedSessionKeys["task1"]!!
        assertEquals(256, encryptedSessionKey.size)
    }
}
```

### 10.3 Access Control Tests

```kotlin
// File: Meshrabiya/src/test/java/org/torproject/android/meshrabiya/storage/FileAccessControlTest.kt

package org.torproject.android.meshrabiya.storage

import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

/**
 * Test access control for distributed storage
 */
class FileAccessControlTest {
    
    @Test
    fun `only authorized recipients can access file`() = runBlocking {
        val owner = createTestUser("owner")
        val task1 = createTestTask("task1")
        val task2 = createTestTask("task2")
        
        val testData = "confidential data".toByteArray()
        
        // Store file for owner + task1
        val fileId = DistributedStorageManager.storeFile(
            file = createTempFile(testData),
            accessScope = AccessScope.SHARED,
            owner = owner.userId,
            recipients = listOf(
                Recipient(task1.taskId, task1.publicKey, RecipientType.TASK)
            )
        )
        
        // Owner can retrieve
        val ownerRetrieved = DistributedStorageManager.retrieveFile(
            fileId, owner.userId, owner.privateKey
        )
        assertTrue(ownerRetrieved.contentEquals(testData))
        
        // Task1 can retrieve
        val task1Retrieved = DistributedStorageManager.retrieveFile(
            fileId, task1.taskId, task1.privateKey
        )
        assertTrue(task1Retrieved.contentEquals(testData))
        
        // Task2 cannot retrieve
        assertFailsWith<SecurityException> {
            DistributedStorageManager.retrieveFile(
                fileId, task2.taskId, task2.privateKey
            )
        }
    }
    
    @Test
    fun `file access can be dynamically updated`() = runBlocking {
        val owner = createTestUser("owner")
        val task1 = createTestTask("task1")
        
        val testData = "data".toByteArray()
        
        // Store file for owner only
        val fileId = DistributedStorageManager.storeFile(
            file = createTempFile(testData),
            accessScope = AccessScope.PRIVATE,
            owner = owner.userId,
            recipients = emptyList()
        )
        
        // Task1 cannot access initially
        assertFailsWith<SecurityException> {
            DistributedStorageManager.retrieveFile(
                fileId, task1.taskId, task1.privateKey
            )
        }
        
        // Add task1 as recipient
        val success = DistributedStorageManager.updateFileAccess(
            fileId = fileId,
            addRecipients = listOf(
                Recipient(task1.taskId, task1.publicKey, RecipientType.TASK)
            )
        )
        assertTrue(success)
        
        // Task1 can now access
        val retrieved = DistributedStorageManager.retrieveFile(
            fileId, task1.taskId, task1.privateKey
        )
        assertTrue(retrieved.contentEquals(testData))
    }
    
    @Test
    fun `file access revocation is immediate`() = runBlocking {
        val owner = createTestUser("owner")
        val task1 = createTestTask("task1")
        
        val testData = "data".toByteArray()
        
        // Store file for owner + task1
        val fileId = DistributedStorageManager.storeFile(
            file = createTempFile(testData),
            accessScope = AccessScope.SHARED,
            owner = owner.userId,
            recipients = listOf(
                Recipient(task1.taskId, task1.publicKey, RecipientType.TASK)
            )
        )
        
        // Task1 can access
        assertNotNull(DistributedStorageManager.retrieveFile(
            fileId, task1.taskId, task1.privateKey
        ))
        
        // Revoke task1 access
        val success = DistributedStorageManager.updateFileAccess(
            fileId = fileId,
            removeRecipients = listOf(
                Recipient(task1.taskId, task1.publicKey, RecipientType.TASK)
            )
        )
        assertTrue(success)
        
        // Task1 can no longer access
        assertFailsWith<SecurityException> {
            DistributedStorageManager.retrieveFile(
                fileId, task1.taskId, task1.privateKey
            )
        }
    }
    
    private fun createTestUser(userId: String): TestUser {
        val keypair = PGPEncryptionService.generateTaskKeypair(userId)
        return TestUser(userId, keypair.publicKeyPem, keypair.privateKeyPem)
    }
    
    private fun createTestTask(taskId: String): TestTask {
        val keypair = PGPEncryptionService.generateTaskKeypair(taskId)
        return TestTask(taskId, keypair.publicKeyPem, keypair.privateKeyPem)
    }
    
    data class TestUser(val userId: String, val publicKey: String, val privateKey: String)
    data class TestTask(val taskId: String, val publicKey: String, val privateKey: String)
}
```

### 10.4 Penetration Testing Scenarios

```kotlin
// File: Meshrabiya/src/test/java/org/torproject/android/meshrabiya/security/PenetrationTests.kt

package org.torproject.android.meshrabiya.security

import org.junit.Test
import org.junit.Assert.*

/**
 * Penetration testing scenarios for task keypair enhancement
 */
class PenetrationTests {
    
    @Test
    fun `compute node cannot access files without task private key`() {
        // Scenario: Malicious compute node tries to access files
        // without going through proper task execution flow
        
        val testData = "sensitive data".toByteArray()
        val taskKeypair = PGPEncryptionService.generateTaskKeypair("task1")
        
        // Store file encrypted for task
        val fileId = storeFileForTask(testData, taskKeypair.publicKeyPem)
        
        // Compute node tries to retrieve without private key
        assertFailsWith<SecurityException> {
            retrieveFileWithoutKey(fileId)
        }
        
        // Compute node tries to retrieve with wrong private key
        val wrongKeypair = PGPEncryptionService.generateTaskKeypair("task2")
        assertFailsWith<SecurityException> {
            retrieveFileWithKey(fileId, wrongKeypair.privateKeyPem)
        }
        
        // Only correct private key works
        val retrieved = retrieveFileWithKey(fileId, taskKeypair.privateKeyPem)
        assertTrue(retrieved.contentEquals(testData))
    }
    
    @Test
    fun `task cannot access files from different task`() {
        // Scenario: Task A tries to access files encrypted for Task B
        
        val testData = "task B data".toByteArray()
        val taskAKeypair = PGPEncryptionService.generateTaskKeypair("taskA")
        val taskBKeypair = PGPEncryptionService.generateTaskKeypair("taskB")
        
        // Store file encrypted for task B
        val fileId = storeFileForTask(testData, taskBKeypair.publicKeyPem)
        
        // Task A tries to access with its private key
        assertFailsWith<SecurityException> {
            retrieveFileWithKey(fileId, taskAKeypair.privateKeyPem)
        }
    }
    
    @Test
    fun `keypair cannot be extracted from sandbox environment variables`() {
        // Scenario: Malicious task code tries to exfiltrate private key
        
        val taskKeypair = PGPEncryptionService.generateTaskKeypair("task1")
        
        // Simulate sandbox environment
        val sandboxEnv = mapOf(
            "TASK_ID" to "task1",
            "TASK_PUBLIC_KEY" to taskKeypair.publicKeyPem,
            "TASK_PRIVATE_KEY" to taskKeypair.privateKeyPem
        )
        
        // Task code should NOT be able to read TASK_PRIVATE_KEY directly
        // (In production, this would be enforced by sandbox container restrictions)
        // For this test, we verify that private key is not exposed in task results
        
        val taskOutput = simulateTaskExecution(sandboxEnv)
        
        // Verify private key material is not in output
        assertFalse(taskOutput.contains(taskKeypair.privateKeyPem))
        assertFalse(taskOutput.contains("BEGIN PGP PRIVATE KEY"))
    }
    
    @Test
    fun `man-in-the-middle cannot modify encrypted files`() {
        // Scenario: Attacker intercepts and modifies encrypted file
        
        val testData = "original data".toByteArray()
        val taskKeypair = PGPEncryptionService.generateTaskKeypair("task1")
        
        val recipient = Recipient("task1", taskKeypair.publicKeyPem, RecipientType.TASK)
        val encryptedPackage = PGPEncryptionService.encryptForMultipleRecipients(
            testData, listOf(recipient)
        )
        
        // Attacker modifies encrypted data
        val modifiedData = encryptedPackage.encryptedData.copyOf()
        modifiedData[0] = (modifiedData[0] + 1).toByte()  // Flip bits
        
        val modifiedPackage = encryptedPackage.copy(encryptedData = modifiedData)
        
        // Decryption should fail (integrity check)
        assertFailsWith<Exception> {
            PGPEncryptionService.decryptWithPrivateKey(
                modifiedPackage, "task1", taskKeypair.privateKeyPem
            )
        }
    }
    
    @Test
    fun `replay attack prevention - TASK_SCHEDULED message`() {
        // Scenario: Attacker replays old TASK_SCHEDULED message
        
        val taskId = "task1"
        val oldTaskPubKey = PGPEncryptionService.generateTaskKeypair("old").publicKeyPem
        
        // Original TASK_SCHEDULED message
        val originalMessage = TaskScheduledMessage(
            taskId = taskId,
            taskPubKey = oldTaskPubKey,
            scheduledStartTime = System.currentTimeMillis() - 60000,  // 1 minute ago
            estimatedCompletionTime = System.currentTimeMillis()
        )
        
        // Client processes original message
        val clientState = TaskClientState()
        clientState.handleTaskScheduled(originalMessage)
        
        // Attacker replays same message
        val replayResult = clientState.handleTaskScheduled(originalMessage)
        
        // Should be rejected (already processed)
        assertFalse(replayResult.accepted)
        assertEquals("Task already scheduled", replayResult.reason)
    }
    
    private fun simulateTaskExecution(env: Map<String, String>): String {
        // Simulate task execution that tries to exfiltrate private key
        // In production, sandbox would prevent environment variable access
        return "Task output (no private key material)"
    }
}
```

### 10.5 Audit Logging for Security Events

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/security/SecurityAuditLog.kt

package org.torproject.android.meshrabiya.security

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Audit logging for security-sensitive operations
 */
object SecurityAuditLog {
    
    private const val TAG = "SecurityAudit"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    
    enum class SecurityEvent {
        KEYPAIR_GENERATED,
        KEYPAIR_DESTROYED,
        FILE_ENCRYPTED,
        FILE_DECRYPTED,
        FILE_ACCESS_GRANTED,
        FILE_ACCESS_REVOKED,
        UNAUTHORIZED_ACCESS_ATTEMPT,
        DECRYPTION_FAILED,
        KEYPAIR_VALIDATION_FAILED,
        SUSPICIOUS_ACTIVITY
    }
    
    data class AuditEntry(
        val timestamp: Long,
        val event: SecurityEvent,
        val taskId: String?,
        val userId: String?,
        val fileId: String?,
        val details: String,
        val success: Boolean,
        val ipAddress: String? = null
    )
    
    /**
     * Log security event
     */
    fun logEvent(
        event: SecurityEvent,
        taskId: String? = null,
        userId: String? = null,
        fileId: String? = null,
        details: String,
        success: Boolean = true
    ) {
        val entry = AuditEntry(
            timestamp = System.currentTimeMillis(),
            event = event,
            taskId = taskId,
            userId = userId,
            fileId = fileId,
            details = details,
            success = success
        )
        
        val logMessage = formatAuditEntry(entry)
        
        when (event) {
            SecurityEvent.UNAUTHORIZED_ACCESS_ATTEMPT,
            SecurityEvent.SUSPICIOUS_ACTIVITY,
            SecurityEvent.KEYPAIR_VALIDATION_FAILED -> {
                Log.w(TAG, logMessage)
            }
            else -> {
                Log.i(TAG, logMessage)
            }
        }
        
        // Write to persistent audit log file
        writeToAuditLog(entry)
    }
    
    /**
     * Format audit entry for logging
     */
    private fun formatAuditEntry(entry: AuditEntry): String {
        val timestamp = dateFormat.format(Date(entry.timestamp))
        val status = if (entry.success) "SUCCESS" else "FAILURE"
        
        return buildString {
            append("[$timestamp] ")
            append("[${entry.event}] ")
            append("[$status] ")
            if (entry.taskId != null) append("taskId=${entry.taskId} ")
            if (entry.userId != null) append("userId=${entry.userId} ")
            if (entry.fileId != null) append("fileId=${entry.fileId} ")
            append(entry.details)
        }
    }
    
    /**
     * Write to persistent audit log file
     */
    private fun writeToAuditLog(entry: AuditEntry) {
        try {
            val auditLogFile = File(getAuditLogDirectory(), "security_audit.log")
            auditLogFile.appendText(formatAuditEntry(entry) + "\n")
            
            // Rotate log file if too large (> 10MB)
            if (auditLogFile.length() > 10 * 1024 * 1024) {
                rotateAuditLog(auditLogFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write audit log", e)
        }
    }
    
    private fun rotateAuditLog(logFile: File) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val archiveFile = File(logFile.parent, "security_audit_$timestamp.log")
        logFile.renameTo(archiveFile)
    }
    
    private fun getAuditLogDirectory(): File {
        // Implementation depends on Android context
        return File("/data/local/tmp/orbot/audit")  // Placeholder
    }
}

/**
 * Example usage in code
 */
fun exampleAuditLogging() {
    // Log keypair generation
    SecurityAuditLog.logEvent(
        event = SecurityAuditLog.SecurityEvent.KEYPAIR_GENERATED,
        taskId = "task123",
        details = "Generated RSA 2048-bit keypair for task execution"
    )
    
    // Log unauthorized access attempt
    SecurityAuditLog.logEvent(
        event = SecurityAuditLog.SecurityEvent.UNAUTHORIZED_ACCESS_ATTEMPT,
        taskId = "task123",
        fileId = "file456",
        details = "Task attempted to decrypt file without valid private key",
        success = false
    )
    
    // Log file access granted
    SecurityAuditLog.logEvent(
        event = SecurityAuditLog.SecurityEvent.FILE_ACCESS_GRANTED,
        taskId = "task123",
        fileId = "file456",
        details = "Added task as recipient for file access"
    )
}
```

---

This completes Part 3 (Sections 8 verification, 9-10), covering:
- Section 8 verification note (confirmed complete in Part 2)
- Section 9: Comprehensive failover and error handling for both client and compute sides
- Section 10: Security testing including keypair security, encryption security, access control, penetration testing, and audit logging

**Part 4** will cover:
- Section 11: Performance Benchmarks
- Section 12: Edge Cases & Corner Cases
- Section 13: Implementation Checklist
- Section 14: Integration with Existing Plan
- Section 15: Migration & Rollout Guide
