# KNOWLEDGE-09192025.md

## Date: September 19, 2025

### Modular, Device-Aware Runtime Strategy for Distributed Compute Services

#### Manifest Schema Updates

- **runtimeRequired**: List of runtimes guaranteed to be available (e.g., JVM, native C/C++ via NDK)
- **runtimeOptional**: List of modular runtimes (Python, Node.js, Go, Rust, WASM) that may be downloaded or enabled only on capable devices
- **deviceProfile**: Device category (flagship, mid-range, budget) used to determine which runtimes are enabled
- **serviceType**: Explicitly declares if service is workflow, ML, data processing, etc.
- **resourceRequirements**: Memory, CPU, storage, GPU, and special hardware needs
- **platformSupport**: List of supported platforms (Android, Linux, WASM, etc.)

#### Developer Documentation Updates

- **Default Runtimes**: JVM (Kotlin/Java) and native (C/C++) are always available and recommended for broad compatibility and minimal APK size
- **Optional Runtimes**: Python, Node.js, Go, Rust, WASM are modular and only enabled for flagship devices or via user opt-in
- **Manifest-Driven Validation**: Services must declare required runtimes; system will warn or reject high-overhead runtimes on low-end devices
- **Device Profiling**: Device profile determines which runtimes are available and which services can be installed
- **User Control**: Users can opt-in to install extra runtimes for advanced services
- **Workflow Services**: Supported as a serviceType; may require multiple runtimes, validated per device

#### Example Manifest Snippet

```json
{
  "packageId": "org.example.workflowservice",
  "serviceType": "WORKFLOW",
  "runtimeRequired": ["jvm"],
  "runtimeOptional": ["python", "nodejs"],
  "deviceProfile": "flagship",
  "resourceRequirements": {
    "minMemoryMB": 32,
    "maxMemoryMB": 128,
    "estimatedCpuUsage": 0.7,
    "storageRequiredMB": 20,
    "requiresGPU": false
  },
  "platformSupport": ["android", "linux"]
}
```

#### Strategy Summary
- Keep APK lean by only bundling JVM/native by default
- Expand language support modularly as devices become more powerful
- Validate service installability based on device profile and runtime availability
- Document runtime requirements and device compatibility for all services
- Support workflow services and advanced types with modular runtime strategy
