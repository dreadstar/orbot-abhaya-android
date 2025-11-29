# KNOWLEDGE-11292025.md

## Date: 2025-11-29

### CoreGossipBroadcastService Singleton Refactor
- Refactored to a thread-safe singleton using VirtualNodeHolder.virtualNode.
- getInstance() accessor now requires no parameters.
- All usages in IntelligentDistributedComputeService.kt and DistributedStorageClient.kt updated to use CoreGossipBroadcastService.getInstance() with short name import.
- Removed all injected references to CoreGossipBroadcastService.

### MeshEcosystemListener.kt Usage Pattern
- Literal file read confirmed no direct usage of CoreGossipBroadcastService.
- Future usage must follow: import CoreGossipBroadcastService (short name, after package), use CoreGossipBroadcastService.getInstance().

### Import Style Rule (AGENTS.md)
- All imports must use short name, never fully qualified notation.
- Import statements must be placed after the package line.
- Verified compliance in all affected files.

### Validation & Protocols
- All changes validated with error checks; no errors found in affected files.
- Pending: Full build/test validation (user cancelled build step).
- Work documented per AGENTS.md protocols.

### Reference Mapping Protocol (2025-11-21)
- All referenced symbols enumerated and verified by literal file read.
- No uncertainty about code existence or location.
- MeshEcosystemListener.kt: No direct usage of CoreGossipBroadcastService as of 2025-11-29.

### Next Steps
- Resume build/test validation when possible.
- Continue to enforce AGENTS.md protocols for all future refactors and documentation.
