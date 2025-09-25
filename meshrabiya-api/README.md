meshrabiya-api
=================

This Gradle module contains AIDL definitions and supporting parcelables for the Meshrabiya bound service API.

Package: `com.ustadmobile.meshrabiya.api`

Purpose:
- Provide a single source of truth for AIDL interfaces used by the Meshrabiya provider and clients.
- Clients (apps) should add a project dependency on `:meshrabiya-api` so they compile the generated AIDL stubs.

Security:
- Service implementations should require a signature-level permission and verify calling package signatures at runtime.
