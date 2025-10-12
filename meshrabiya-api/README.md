# Meshrabiya API

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0)

This Gradle module contains AIDL interface definitions and supporting Parcelable classes for the Meshrabiya mesh networking service API. It provides a stable, versioned interface for inter-process communication between Meshrabiya service providers and client applications.

## 📋 Overview

**Package**: `com.ustadmobile.meshrabiya.api`  
**License**: LGPL-3.0  
**Target SDK**: 36  
**Min SDK**: 24  
**Java Version**: 21

## 🎯 Purpose

- **Single Source of Truth**: Centralized AIDL interface definitions for all Meshrabiya operations
- **Client Integration**: Applications add a project dependency on `:meshrabiya-api` to compile generated AIDL stubs
- **Version Stability**: Semantic versioning ensures API compatibility across releases
- **Type Safety**: Strongly-typed Parcelable classes for data exchange

## 🏗️ Architecture

```
meshrabiya-api/
├── src/main/
│   ├── aidl/com/ustadmobile/meshrabiya/api/
│   │   ├── IMeshrabiyaService.aidl       # Main service interface
│   │   ├── IMeshrabiyaCallback.aidl      # Event callbacks
│   │   └── ...                           # Additional interfaces
│   └── java/com/ustadmobile/meshrabiya/api/
│       ├── MeshNode.java                 # Node representation
│       ├── MeshMessage.java              # Message format
│       └── ...                           # Supporting classes
└── build.gradle.kts                      # Module configuration
```

## 🔧 Integration

### For Service Providers

```gradle
dependencies {
    implementation project(':meshrabiya-api')
}
```

### For Client Applications

```gradle
dependencies {
    implementation project(':meshrabiya-api')
}
```

The Android build system automatically generates Java/Kotlin binding stubs from AIDL files, making them available for compilation and runtime use.

## 🔒 Security

- **Permission Protection**: Service implementations must require signature-level permissions
- **Package Verification**: Runtime verification of calling package signatures required
- **Secure Communication**: All mesh communications should use encrypted channels
- **Access Control**: Fine-grained permissions for different API operations

### Recommended Permission Structure

```xml
<!-- In service provider manifest -->
<permission 
    android:name="com.ustadmobile.meshrabiya.MESH_SERVICE"
    android:protectionLevel="signature" />

<service 
    android:name=".MeshrabiyaService"
    android:permission="com.ustadmobile.meshrabiya.MESH_SERVICE" />
```

## 📚 API Documentation

### Core Interfaces

- **`IMeshrabiyaService`**: Primary service interface
  - Node discovery and registration
  - Message routing and delivery
  - Network topology management
  - Service lifecycle operations

- **`IMeshrabiyaCallback`**: Event notification interface
  - Connection state changes
  - Message delivery confirmations
  - Network topology updates
  - Error notifications

### Data Classes

- **`MeshNode`**: Represents a node in the mesh network
- **`MeshMessage`**: Standard message format for inter-node communication
- **`NetworkTopology`**: Current network structure representation

## 🛠️ Building

```bash
# Build the API module
./gradlew :meshrabiya-api:build

# Generate AIDL stubs
./gradlew :meshrabiya-api:compileDebugAidl

# Create AAR with embedded AIDL sources
./gradlew :meshrabiya-api:bundleAarWithAidl
```

## 📄 License

This module is licensed under **LGPL-3.0** to allow maximum flexibility for client applications while ensuring improvements to the API itself remain open source.

### What This Means

- ✅ **Applications can use any license** (including proprietary) when dynamically linking
- ✅ **No restrictions on client code** - your app remains under your chosen license
- ✅ **Commercial use permitted** without additional licensing fees
- ✅ **Modification allowed** - you can extend or modify the API interfaces
- ⚠️ **Modifications must be shared** - improvements to this API module must be open source
- ⚠️ **Static linking has restrictions** - statically linked apps must be LGPL compatible

### Why LGPL-3.0?

1. **Client Freedom**: Applications using this API can remain proprietary while benefiting from open mesh networking
2. **API Evolution**: Ensures improvements to the core API benefit the entire ecosystem
3. **Commercial Compatibility**: Businesses can build proprietary products on top of the open API

### Usage Examples

✅ **Allowed**: Proprietary Android app using this API via AIDL binding (dynamic linking)  
✅ **Allowed**: Commercial service built on Meshrabiya mesh networking  
✅ **Allowed**: Extending interfaces for custom functionality  
⚠️ **Restricted**: Bundling modified API code in proprietary library without sharing changes

## 🤝 Contributing

We welcome contributions to improve the Meshrabiya API! Please read our [Contributing Guidelines](../CONTRIBUTING.md) before submitting pull requests.

### Development Guidelines

- Follow [Android AIDL best practices](https://developer.android.com/guide/components/aidl)
- Maintain backward compatibility in interface changes
- Add comprehensive documentation for new interfaces
- Include unit tests for Parcelable classes
- Update version numbers following semantic versioning

## 🔗 Related Projects

- **[orbot-abhaya-android](https://github.com/dreadstar/orbot-abhaya-android)**: Main application repository
- **[Meshrabiya Fork](../Meshrabiya/)**: Core mesh networking implementation
- **[Sensor Module](../abhaya-sensor-android/)**: IoT sensor integration

## 📞 Support

- **Issues**: [GitHub Issues](https://github.com/dreadstar/orbot-abhaya-android/issues)
- **Discussions**: [GitHub Discussions](https://github.com/dreadstar/orbot-abhaya-android/discussions)
- **Security**: Email security issues privately to the maintainers

---

**Copyright © 2025 Tyrone Thomas/BreakThrough Technologies**  
Licensed under LGPL-3.0 - see [LICENSE](./LICENSE) for details.
