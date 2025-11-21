# TASK_LIBRARY_REFACTOR_PLAN.md

## Purpose
Refactor the Meshrabiya service/task library to provide a unified, user-controllable, and extensible structure for all service types (ML Kit, Python, JVM, etc.), with full enable/disable support and Meshrabiya API mediation.

---

## 1. Unified Service Library Structure
- All services (ML Kit, Python, JVM, etc.) are represented as `ServiceEntry` objects in a single, unified library (e.g., `LocalDeviceServiceLibrary`).
- Each service entry includes:
  - Unique service ID
  - Task type (e.g., ML_NATIVE, PYTHON)
  - Capabilities, resource requirements, execution profile
  - **Enabled/disabled status** (user-controlled)

## 2. User Enable/Disable Control (via Meshrabiya API)
- Expose Meshrabiya API endpoints/methods to:
  - List all available services (with status)
  - Enable a service (triggers download/installation if needed)
  - Disable a service (removes from compute availability)
- All user actions (enable/disable) are mediated through the Meshrabiya API, not direct UI or internal calls.

## 3. ML Kit Service Management
- ML Kit services are registered as individual entries in the library.
- Enabling a service triggers download/installation of the required ML Kit API/model from the official source.
- Only enabled ML Kit services are available for compute and discovery.

## 4. Service Registration and Discovery
- On initialization, all potential services are registered in the library as disabled by default (unless pre-installed).
- Only enabled services are advertised and available for compute task execution.
- Discovery logic: when a task request is broadcast, only nodes with the requested service enabled respond.

## 5. Scheduler and Task Manager Integration
- The scheduler and task manager use a unified interface to start, execute, and manage the lifecycle of any task type.
- The sandbox/task management interface is identical for all runtimes.

## 6. Implementation Steps
1. **Extend ServiceEntry** to include enabled/disabled status.
2. **Refactor LocalDeviceServiceLibrary** to:
   - Track enabled/disabled status for all services
   - Support dynamic enable/disable via Meshrabiya API
   - Persist status across app restarts
3. **Update UnifiedMLServiceManager** to:
   - Register all ML Kit services in the library
   - Expose enable/disable logic via Meshrabiya API
   - Trigger ML Kit API/model download on enable
4. **Update Meshrabiya API** to:
   - Add endpoints/methods for service listing, enable, disable
   - Ensure all user actions go through the API
5. **Update discovery and broadcast logic** to:
   - Only include enabled services in responses
6. **Test**:
   - Enable/disable services and verify correct registration, discovery, and execution
   - Ensure scheduler/task manager can handle all task types identically

## 7. Future Considerations
- Advanced discovery (e.g., partial capability matching, remote service advertisement)
- User feedback on storage/space usage for ML Kit downloads
- Policy for auto-updating or removing unused services

---

**This plan is the authoritative guide for the unified, user-controllable task/service library refactor in Meshrabiya.**
