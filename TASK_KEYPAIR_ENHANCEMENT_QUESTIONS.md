# Task KeyPair Enhancement - Clarifying Questions

**Date**: November 13, 2025  
**Context**: Enhancement proposal for per-task encryption keypairs in the Task Execution Layer

---

## 1. Key Pair Generation Timing & Ownership

**Question**: You say "keypair is generated on the compute_node after it receives message it was selected to run the task"

**Sub-questions**:
- Does this mean compute node generates the keypair when receiving `TASK_ASSIGNMENT` message?
- OR when it sends back the acknowledgment that it will execute the task?


- Is this keypair **per-task** (new keypair for each task) or **per-node** (one keypair used for all tasks on that node)?

**Your Answer**:
compute node generates the keypair when receiving `TASK_ASSIGNMENT` message and per task


---

## 2. Task Acknowledgment Flow

**Question**: Currently in the plan (Part 3 Section 8), we have:
```
Client sends TASK_ASSIGNMENT → Compute node
Compute node can send TASK_REJECTED if runtime unavailable
Compute node sends TASK_COMPLETED when done
```

**Sub-questions**:
- Are you proposing a **new message type** `TASK_ACCEPTED` or `TASK_ASSIGNMENT_ACK` that includes the task public key?
- Or should the pub key be included in an existing message (if so, which one)?
- What should happen if the compute node accepts the task but then needs to reject it later (e.g., after generating keypair but before execution)?

**Your Answer**:
your understanding in Part 3 Section 8 is flawed. the actual lifecycle is:
Client sends TASK_REQUEST  → Broadcast
Only compute nodes with runtime and adequate capabilites and fitness respond
Client sends TASK_ASSIGNMENT only to node selected from repondents (so only nodes with runtime will be selected) → Compute node
Compute node can send TASK_REJECTED if fitnes sor capabilities have drpped below required levels 
Compute node can send TASK_SCHEDULED with pub key
Client node Compute service calls distributedStorrageSevic function to encrypt data wrapper to include the task pub key access to contents like this:
"How PGP encrypts for multiple recipients
Instead of encrypting the entire message multiple times, which would be inefficient, PGP uses a hybrid encryption process: 
Generate a session key. The sender's software creates a random, one-time symmetric key, often called a "session key," for that specific message.
Encrypt the message data. The actual message data (the large payload) is encrypted using a fast symmetric algorithm, such as AES, with the session key.
Encrypt the session key for each recipient. The session key is then encrypted with the public key of each individual recipient. Because the session key is very small, this asymmetric encryption process is fast.
Bundle the encrypted data. All the encrypted parts—the symmetrically encrypted message and each of the asymmetrically encrypted session keys—are bundled into a single file. 
How multiple recipients decrypt the data
A recipient can decrypt the message using their own private key, without needing to coordinate with the other recipients. The process is as follows: 
Find their encrypted session key. The recipient's PGP software looks for the version of the encrypted session key that was encrypted with their public key.
Decrypt the session key. The software uses the recipient's private key to decrypt their unique copy of the session key.
Decrypt the message. Using the now-recovered session key, the software symmetrically decrypts the main message data, revealing the original plaintext. " 
Compute node sends TASK_COMPLETED when done


---

## 3. Encryption Architecture

**Question**: Currently in the plan (Part 1 Section 2), we have:
- File encryption with AccessScope, Owner (task requester), and recipients list
- Hybrid encryption: chunk encrypted with symmetric key, key encrypted per recipient with PGP

**Sub-questions**:
- Should **task requester** encrypt input files with **task pub key** (in addition to compute node's pub key)?
- When user shares "additional files" during execution, should they ONLY encrypt with task pub key (not compute node pub key)?
- Does this mean the task itself (sandboxed code) needs access to the private key to decrypt files?
- If task code has the private key, how does this interact with sandboxing? (Task code could potentially exfiltrate the key)

**Your Answer**:
yes **task requester** encrypt input files with **task pub key** but not with compute node's pub key. task manager will store the keypairs for each task. the TaskManager will retrieve and decrypt the data for the task, so the tasks do not need to store the keypair. Alternately, if it increases security, the keypairs can be stored in the sandbox with some interface that would be transparent to the task which decrypted encrypted files transferred into the sandbox by the TaskManager, so the TaskManger would only track the pub key of each task to have it available for giving the task access to additional data.  the alternate would be preferable.
the compute node  id will be needed on to communicate with the compute node running a given task. it should be noted that the Store file lifecycle will eed to be enhanced such that after when a storage node is selected  to store a file , the requestor/client _node (but also keep replication model in mind) will need to reencrpt the data to add access for  the storage_node pub key, so that the storage_node can handle future access updates for items in Distributed storage which are stored on that storage_node.
the tasks are relatively shortlived and have acces to restricted amounts of data.  also, though the keypair would be in the sandbox, it should be invisible to the task code itself and the files should appear unencrypted and assembled (if preivously chunked) to the task code

---

## 4. Storage & Lifecycle - Compute Node

**Question**: On the compute node (TaskManager), how should the keypair be managed?

**Sub-questions**:
- Where exactly should the keypair be stored?
  - [x] In-memory only (lost on process restart)
  - [ ] Encrypted SharedPreferences
  - [ ] Android Keystore (secure hardware storage)
  - [ ] File system with encryption
  - [ ] Other: _______________
- Should the private key be injected into the sandbox container? How?
  - [x] Environment variable
  - [ ] File in workspace directory
  - [ ] API call to TaskManager from sandbox
  - [ ] Other: _______________
- When should the keypair be destroyed?
  - [x] Immediately after task completion
  - [ ] After result retrieval by client
  - [ ] After a timeout period (e.g., 24 hours)
  - [ ] Never (kept for audit trail)
  - [ ] Other: _______________

**Your Answer**:
-  the keypair should be stored  in Environment variable
- private key should be injected into the sandbox container with Environment variable
- the keypair should be destroyed Immediately after task completion


---

## 5. Storage & Lifecycle - Client Node

**Question**: On the client node (IntelligentDistributedComputeService TaskRequest registry), how should task metadata be tracked?

**Sub-questions**:
- Should this be stored in:
  - [x] New field in `TaskStatus` data class
  - [ ] Separate registry map (e.g., `Map<TaskId, TaskExecutionMetadata>`)
  - [ ] Database/persistence layer
  - [ ] Other: _______________
- What data structure do you envision? Something like:
  ```kotlin
  data class TaskExecutionMetadata(
      val taskId: String,
      val taskPubKey: String,  // PGP public key
      val executorNodeId: String,  // Node running the task
      val createdAt: Long,
      val status: TaskStatus
  )
  ```
- How long should this mapping persist?
  - [ ] Until task completes
  - [ ] Until result is retrieved
  - [ ] Indefinitely for audit trail
  - [ ] User-configurable retention period
  - [x] Other: _app restart__

**Your Answer**:
task metadata should be tracked in New field in `TaskStatus` data class. yes it should look something like the structure you suggest.  and the data should only last until app restart


---

## 6. Additional File Sharing Flow

**Question**: You mention "if a user shares additional files with a task, the taskid used in the UI is mapped to the task pub.key"

**Sub-questions**:
- What is the UI flow here?
  - [ ] User uploads new file while task is running → Client node looks up task pub key → encrypts file → sends to compute node
  - [ ] User uploads file to distributed storage → storage system uses task pub key for encryption
  - [ ] Both of the above
  - [X] Other: _User adds folder to dropfolder subfolder which is shared toa task, the file is uploaded to Distributed Storage node  with the file encrypted with access for everyone with whom the folder is shared including task in the Store FIle Lifecycle. Within the lifecycle there is notification to recipients.  That notification would be sent from Client_Node to all recipients including the compute node running the task. The MeshEconsystme listener on that compute node would send an event for Data Access Change  to the TasKManger , which would then go through the process of  retrieving the file to pass it to the task.
- Should there be a new message type (e.g., `TASK_FILE_ADDED`) to notify the compute node?
- Can the compute node's running task code automatically detect new files? Or does it need notification?
- Should the task code be polling for new files? Or should TaskManager inject files into the sandbox dynamically?
- What happens if user tries to add files after task completes?

**Your Answer**:
User adds folder to dropfolder subfolder which is shared toa task, the file is uploaded to Distributed Storage node  with the file encrypted with access for everyone with whom the folder is shared including task in the Store FIle Lifecycle. Within the lifecycle there is notification to recipients.  That notification would be sent from Client_Node to all recipients including the compute node running the task. The MeshEcosystem listener on that compute node would send an event for Data Access Change  to the TasKManger , which would then go through the process of  retrieving the file to pass it to the task.
We should use the same message for any file stored  because from Distributed Storage perspective a task is just another user. 
as described above the compute node would recieve a message from the client_node that a new files was added just as a user on a different client node would get a notification. ond difference is that for tasks the message is sent to the comput  node pub key/mesh address and the actual recipient task is identified by the task id.
As deiscussed earlier TaskkManager should add the files to the sandbox for the task and have a hook in the sandbox container  to send a notification  event
which service developers could write handlers in the task code to alert the task node of new file arrivals.
if the user adds files to a drop folder shared with  a task but the task has complete, the file is still uploaded to distributed storage and  the notification process may stillsend a notification to the Compute node which had been running the task but the taskManager will see the task has completed or is otherwise not running and do nothing with the notification.

---

## 7. Security Model Clarification

**Question**: What is the threat model and security goal here?

**Sub-questions**: Is the goal to (check all that apply):
- [x] **Isolate task data from compute node owner** (so node operator can't read task data)
- [x] **Allow dynamic permission grants** (user adds files to running task without re-submitting)
- [x] **Provide task-level access control** (separate from node-level access control)
- [ ] **Enable task identity/reputation** (tasks can build trust over time)
- [x] **Support multi-tenant compute nodes** (multiple tasks can't see each other's data)
- [ ] **Other**: _______________

**Follow-up**: If isolating from node operator is a goal, how do we prevent:
- Node operator from reading task code bundle?
- Node operator from inspecting task private key in memory?
- Node operator from modifying task code before execution?

**Your Answer**:
- Node operator from reading task code bundle?  the code is not secret and we are not building a mechanism to enter the sandbox
- Node operator from inspecting task private key in memory?
- Node operator from modifying task code before execution?  task services shold be  published with checksum and other mechanism to ensure the code matches original developer/author intent


---

## 8. Integration with Existing Encryption

**Question**: Currently planned: Files are encrypted with recipient list (requester + compute node). With task keypair, should we have:

**Option A**: Two encryption layers
```
Original File
  → Encrypt with task pub key (inner layer)
  → Encrypt with compute node pub key (outer layer)
  → Store in mesh network
```
- Compute node can receive and store file
- Only task code (with task private key) can read contents

**Option B**: Replace compute node encryption
```
Original File
  → Encrypt ONLY with task pub key
  → Store in mesh network
```
- Compute node can receive but cannot read
- Only task code can read

**Option C**: Hybrid approach
```
Metadata/manifest: Encrypted for compute node
File contents: Encrypted for task keypair
```

**Option D**: Something else

**Which approach do you prefer?**

**Your Answer**:
FILES ARE NEVER ENCRYPtED for a COMPUTE_NODE explicitly (a compute_node could also be acting as a storage_node and a client_node potentially) Files are encrypted for the  owner/creator  and recipients which can be other users on the mesh or tasks (which are FOUND in the mesh with the Compute_nodes mesh address but have a per task key pair for data encryption/access) 
As i keep saying files are encrypted to limit access to the entire recipient list.  There is no needs to bundle the list of recipients with the files.  If you are the owner you have the manifest locally. If you are a recipient all that matters is your access from your prespective.  "Option B: Replace compute node encryption" sounds the most correct but consider in the full context of the answers provided. 


---

## 9. Initial Input Files

**Question**: For the initial task submission (with input files), how should encryption work?

**Sub-questions**:
- **Problem**: Client doesn't know the task pub key yet (not generated until compute node accepts)
- **Options**:
  1. Client encrypts with compute node pub key initially, compute node re-encrypts with task pub key after generating it
  2. Two-phase submission: Client sends task request → receives task pub key → sends encrypted input files
  3. Client encrypts with both compute node pub key AND a temporary key, task pub key added later
  4. Other: _______________

**Your Answer**:
 I describe this process in 2&3  above.  Review those answers and if you still have questions, ask.


---

## 10. Task Key Revocation & Rotation

**Question**: What happens in error scenarios?

**Sub-questions**:
- If task fails, should the keypair be immediately destroyed? YES
- If task is reassigned to a different node (failover), should:
  - [ ] Original node send task private key to new node (security risk)
  - [X] Generate new keypair, re-encrypt all files (expensive)
  - [ ] Tasks cannot be reassigned once started
  - [ ] Other: _______________
- Should there be a way to "revoke" a task keypair (e.g., user cancels task)?  Future TODO 
- What if compute node goes offline with task still running?  we have spoken about periodic hearbeats from the task containers, if those are not received within the polling period by TaskManager on compute node a notice of eof possible hung task can be sent and a mechanism for the user to signal that task should be killed  can later be implmented

**Your Answer**:
Task key pairs are only for the one task on a given compute node. when that task ends or goes away so does the keypair. the peypair is created in the TaskManger on the compute node. so if a task failed and was restarted on a different compute node, a new keypair would be genrated, it would be sent back to client  node for the data to be reencrypted for the task to have access. 


---

## 11. Backwards Compatibility

**Question**: Should this feature be:
- [x] **Mandatory** for all tasks (breaking change)
- [ ] **Optional** with feature flag (tasks can opt-in)
- [ ] **Automatic** based on task sensitivity (high-security tasks get keypairs, simple tasks don't)

**Your Answer**:
THERE IS NO BACKWARDS COMPATIBILITY TO BE CONSIDRED ON THIS PROJECT.


---

## 12. Performance Considerations

**Question**: Overhead and performance impacts

**Sub-questions**:
- Generating a keypair (RSA 2048-bit) takes ~100-500ms on mobile. Is this acceptable?
- Re-encrypting files adds overhead. What are acceptable latency limits?
  - Small files (<1MB): ___________ ms
  - Large files (>100MB): ___________ ms
- Should keypair generation happen synchronously (blocks task acceptance) or asynchronously?

**Your Answer**:
i dont care about the overhead. we will optimize  later. keypair generation happen synchronously at the point in the process described in answers to question 2.


---

## Summary of My Current Understanding

Based on your original description, I **think** you're proposing:

### Flow:
1. **Task Assignment**:
   ```
   Client → TASK_ASSIGNMENT (with input files encrypted for compute node) → Compute Node
   Compute Node generates per-task RSA keypair
   Compute Node → TASK_ACCEPTED{taskPubKey: "-----BEGIN PGP PUBLIC KEY..."} → Client
   Client saves mapping: taskId → {taskPubKey, executorNodeId}
   ```

2. **Task Execution**:
   - TaskManager on compute node stores keypair (in-memory or secure storage)
   - TaskManager re-encrypts input files with task private key (or provides decryption API)
   - Task code can decrypt files using task private key (somehow injected into sandbox)

3. **Additional File Sharing**:
   ```
   User uploads file via UI with taskId
   Client looks up taskPubKey from taskId
   Client encrypts file ONLY with taskPubKey
   Client looks up executorNodeId from taskId
   Client sends file to compute node (message: TASK_FILE_ADDED)
   Compute node stores file in task workspace
   Task code (already running) detects new file, decrypts with task private key
   ```

4. **Security Goal**:
   - Task data is isolated from compute node operator
   - Task has its own "identity" (keypair) separate from node identity
   - Enables dynamic file addition without re-submitting entire task

### Data Structures (tentative):
```kotlin
// Client-side (IntelligentDistributedComputeService)
data class TaskExecutionMetadata(
    val taskId: String,
    val taskPubKey: String,  // PGP public key PEM format
    val executorNodeId: String,
    val createdAt: Long
)
private val taskMetadataRegistry = mutableMapOf<String, TaskExecutionMetadata>()

// Compute-side (TaskManager)
data class TaskKeypair(
    val taskId: String,
    val publicKey: String,  // PGP public key PEM
    val privateKey: String,  // PGP private key PEM (SENSITIVE)
    val createdAt: Long
)
private val taskKeypairs = mutableMapOf<String, TaskKeypair>()

// New message types
data class TaskAcceptedMessage(
    val taskId: String,
    val taskPubKey: String,  // PGP public key
    val estimatedStartTime: Long?
) : MessageData

data class TaskFileAddedMessage(
    val taskId: String,
    val fileReference: FileReference,  // Already encrypted with task pub key
    val notifyTask: Boolean = true  // Should running task be notified?
) : MessageData
```

**Is this understanding correct?** Please answer the questions inline above with as much detail as needed.
