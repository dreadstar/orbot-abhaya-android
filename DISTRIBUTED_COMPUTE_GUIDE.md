# Orbot-Abhaya Distributed Compute Service Guide

## Overview
This guide provides onboarding instructions for developers who want to create, test, and deploy distributed compute services for the Orbot-Abhaya mesh intelligence platform. It covers service schema, manifest requirements, local testing, and integration steps.

---

## 1. Service Architecture

All services are defined using the manifest-driven `LibraryEntry` schema. Supported service types:
- Python
- LiteRT (ML inference)
- Hybrid (Python + LiteRT)
- Java
- NDK (native)
- Workflow (multi-step)

Each service must provide:
- Unique `serviceId`
- `ServiceManifest` (type, version, author, resource requirements, platform support, etc.)
- `ExecutionProfile` (deterministic, access level, etc.)
- Input/output schema
- Capabilities

See `IntelligentDistributedComputeService.kt` for schema details.

---

## 2. Creating a Service

### Example: Python Service
```kotlin
val myService = LibraryEntry.PythonServiceEntry(
    serviceId = "custom_image_preprocessing",
    scriptCode = """<your Python code>""",
    libraries = setOf(PythonLibrary.OPENCV, PythonLibrary.NUMPY),
    manifest = ServiceManifest(
        serviceType = ServiceType.PYTHON,
        version = "1.0.0",
        author = "Your Name",
        signature = null,
        resourceRequirements = ResourceRequirements(
            minRAMMB = 256,
            preferredRAMMB = 512,
            cpuIntensity = CPUIntensity.LIGHT
        ),
        builtin = false
    ),
    executionProfile = ExecutionProfile(deterministic = true),
    inputs = listOf(ServiceInput("images", "List<Base64Image>", true)),
    outputs = listOf(ServiceOutput("processed_tensors", "List<Base64Tensor>")),
    capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
)
```

### Manifest Fields
- `serviceType`: One of PYTHON, LITERT, HYBRID, JAVA, NDK, WORKFLOW, STORAGE
- `resourceRequirements`: RAM, CPU, GPU/NPU, storage, battery, thermal
- `platformSupport`: List of supported platforms (e.g., Android, Linux)
- `files`: List of required files (models, scripts, etc.)
- `builtin`: Set to `false` for user-defined services

---

## 3. Local Testing

### Python Services
- Use the provided script code and test with sample input data.
- Validate output schema and types.
- For Android, use the built-in Python executor or run scripts in a local environment.

### LiteRT/Hybrid/Java/NDK Services
- Ensure all dependencies (models, .jar, .so files) are available locally.
- Use mock input data to verify service logic.
- For native code, test with JNI wrappers if needed.

### Workflow Services
- Define steps as a list of service IDs.
- Test each step independently before chaining.

---

## 4. Integration & Registration

- Add your service to the registry (see `builtinLibraryEntries` for examples).
- For user-defined services, register via the UI or API.
- Ensure manifest and execution profile are complete and accurate.
- Use privacy-preserving search: only manifest/meta is exposed in service discovery.

---

## 5. Testing in Mesh Environment

- Deploy service on a test node or emulator.
- Use the Task Management tab to create and assign tasks.
- Select destination folders using the folder picker dialog.
- Monitor atomicity and integrity: verify file delivery and handle failures (gray-out, retry).
- Confirm notification/confirmation logic for successful delivery.

---

## 6. Best Practices

- Keep service logic modular and stateless.
- Use clear input/output schema.
- Document resource requirements and platform support.
- Test for edge cases and error handling.
- Follow privacy and security guidelines: do not expose sensitive code or data in manifest/meta.

---

## 7. References
- `IntelligentDistributedComputeService.kt` (core schema, registry, onboarding logic)
- `StorageDropFolderManager` (folder navigation, file delivery)
- `TaskManagerFragment` (UI integration)
- KNOWLEDGE docs for advanced topics (atomicity, notification, privacy)

---

## 8. Support
For questions or contributions, open an issue or pull request in the Orbot-Abhaya repository.
