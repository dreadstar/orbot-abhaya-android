# KNOWLEDGE - September 15, 2025

## Distributed Storage Layer Refactoring & Architecture Review

### Completed Work
- ✅ Refactored `DistributedStorageAgent` to clarify storage responsibilities
- ✅ Separated concerns between Drop Folder files, SharedWithMe files, and distributed storage
- ✅ Implemented lazy download pattern for shared files
- ✅ Added SharedWithMe folder auto-creation
- ✅ **NEW**: Implemented coroutine-based file I/O operations using `Dispatchers.IO`
- ✅ **NEW**: Added comprehensive `StorageError` system with detailed error types
- ✅ **NEW**: Enhanced error handling with structured error objects
- ✅ **NEW**: Fixed compilation errors and achieved successful APK build
- ✅ Build verification successful

### Critical Architecture Issues Identified & Solutions

#### 1. Storage Path Configuration
**Issue**: Currently using hardcoded paths like `Paths.get("drop_folder")`
**Solution**: Storage paths should be retrieved from user selection in Drop Folder Card
**Status**: TODO - Needs integration with Drop Folder UI preferences

#### 2. File I/O Threading Model
**Issue**: Current design has synchronous file operations that could block UI thread
**Solution**: Refactor to use coroutines for all file I/O operations
**Status**: ✅ **COMPLETED** - All file I/O operations now use `withContext(ioDispatcher)`
**Implementation**: 
- Added `ioDispatcher: CoroutineDispatcher = Dispatchers.IO` parameter
- All file operations wrapped in `withContext(ioDispatcher)`
- Prevents UI thread blocking with suspend functions

#### 3. Security & Trust Management
**Issue**: No validation or trust verification for SharedWithMe files from mesh peers
**Requirements**: 
- Implement peer trust and "friends lists" using Tor project methodologies
- Questions to resolve:
  ### Security Questions & Research Results

1. **✅ RESOLVED: .onion Address Scope**
   - **Question**: Is .onion address a device or user identifier?
   - **Answer**: .onion addresses are **SERVICE-SPECIFIC** identifiers
   - **Details**: Each hidden service gets unique .onion address via Tor's Ed25519 key generation
   - **Implication**: Multiple services per device = multiple .onion addresses per device

2. **✅ RESOLVED: .onion Address Generation**
   - **Question**: Does Orbot generate .onion addresses for device/user identification?
   - **Answer**: Orbot uses native Tor daemon (via JNI) to generate service-specific addresses
   - **Process**: Service directory → Tor generates key pair → reads hostname file → extracts .onion address
   - **Architecture**: `TorService` (JNI) → `torrc` config → `HiddenServiceDir` → Tor daemon → `hostname` file

3. **Security Architecture for Mesh Networking**:
   - Each mesh node runs dedicated hidden service → unique .onion node identifier
   - Peer trust based on .onion address allowlist/blocklist (service-level trust)
   - File signatures using service's Ed25519 private key for validation
   - Client authentication (v3) for restricted peer access
   - Device-level trust = collection of service-level trust relationships
**Status**: TODO - Design trust model inspired by Tor project approaches
**Security Considerations**:
- File validation before download
- Sandboxing for SharedWithMe content
- Permission handling for untrusted sources
- Signature verification for file integrity

#### 4. Error Handling Strategy
**Current**: ~~Simple boolean success/failure returns~~ **COMPLETED**
**Implemented**: Comprehensive `StorageError` sealed class with detailed error types
**Status**: ✅ **COMPLETED** - Full error handling system implemented

**Implemented Error Categories**:

**Network Errors**: ✅ **IMPLEMENTED**
- ✅ `PeerUnreachable` - Target node not responding
- ✅ `NetworkTimeout` - Operation exceeded time limit  
- ✅ `MeshDisconnected` - No mesh network connectivity

**Storage Errors**: ✅ **IMPLEMENTED**
- ✅ `InsufficientSpace` - Not enough local storage
- ✅ `DiskIOError` - File system read/write failure
- ✅ `PermissionDenied` - Access denied to storage location

**Security Errors**: ✅ **IMPLEMENTED** 
- ✅ `UntrustedSource` - File from non-friend peer
- ✅ `ChecksumMismatch` - File integrity verification failed

**Application Errors**: ✅ **IMPLEMENTED**
- ✅ `InvalidFileId` - File identifier not found
- ✅ `AlreadyExists` - File already downloaded/stored
- ✅ `NotImplemented` - Operation not yet implemented

**Additional Categories Still TODO**:
**Additional Categories Still TODO**:
- `BANDWIDTH_LIMITED` - Insufficient bandwidth for operation
- `CORRUPTED_FILE` - File integrity check failed
- `SIGNATURE_INVALID` - File signature verification failed
- `MALWARE_DETECTED` - File flagged by security scan
- `ENCRYPTION_FAILED` - Unable to encrypt/decrypt file
- `OPERATION_CANCELLED` - User cancelled operation
- `QUOTA_EXCEEDED` - Storage quota limits reached
- `VERSION_MISMATCH` - Incompatible protocol versions
- `INVALID_METADATA` - Malformed file metadata
- `UNSUPPORTED_FORMAT` - File type not supported

**Status**: ✅ **CORE SYSTEM COMPLETED** - Additional error types can be added as needed

### Technical Debt & Best Practices
- ✅ ~~Coroutine-based I/O: All file operations should be non-blocking~~ **COMPLETED**
- Android Context usage: Need proper `context.filesDir` instead of hardcoded paths (TODO)
- Error recovery: Implement retry mechanisms for transient failures (TODO)
- Resource management: Proper cleanup of file handles and network connections (TODO)
- **NEW**: All StorageError types include `recoverable` flag for retry logic
- **NEW**: Response objects now use structured error types instead of strings

### Next Steps Priority Order
1. ✅ ~~Implement coroutine-based file I/O~~ **COMPLETED**
2. Integrate with Drop Folder path selection
3. ✅ ~~Design comprehensive error handling system~~ **COMPLETED**
4. Research Tor-inspired trust model design
5. Implement security validation for shared files
6. **NEW**: Add file validation and signature verification
7. **NEW**: Implement proper Android Context usage for file paths

### Integration Points
- Drop Folder UI: Path selection and SharedWithMe display
- Mesh Network: Peer discovery and trust establishment
- Service Layer: Error propagation and user notification
- UI Components: Download progress and error display

### Open Questions
- .onion address scope (device vs user identification)
- Orbot's role in address generation
- Trust relationship persistence across app restarts
- File sharing permission granularity (per-file vs per-peer)
