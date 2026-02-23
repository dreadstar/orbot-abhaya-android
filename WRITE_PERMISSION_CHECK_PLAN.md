# WRITE PERMISSION CHECK AND REQUEST PLAN (DISK-VERIFIED)

## Objective
Add robust, code-level checks for app write permissions before any file/folder creation in EnhancedMeshFragment.kt, and request permission if missing.

## 1. Permission to Check
- For Android 10 and below: Manifest.permission.WRITE_EXTERNAL_STORAGE
- For Android 11+ (targetSdk >= 30): Use app-specific storage (no permission needed) or request MANAGE_EXTERNAL_STORAGE for broad access (not recommended for Play Store).
- This project uses getExternalFilesDir (app-specific), so explicit WRITE_EXTERNAL_STORAGE is not required for most cases, but check for edge cases (e.g., fallback to shared storage).

## 2. Verified Write Operations (EnhancedMeshFragment.kt)
- Line 145: broadcastFolder.mkdirs()
- Line 155: fallbackFolder.mkdirs()
- Line 165: fallbackFolder.mkdirs()
- Line 1028: newFolder.mkdirs()

## 3. Existing Permission Handling
- Location permission is handled with requestLocationPermissionLauncher (lines 172–186).
- No explicit check/request for WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE found for file/folder creation.

## 4. Implementation Plan (Code-Level, Disk-Verified)

### a. Add Permission Check Utility
- Add function to check if WRITE_EXTERNAL_STORAGE is granted (for Android <= 29).
- Use ContextCompat.checkSelfPermission.

### b. Add Permission Request Launcher
- Add ActivityResultLauncher for requesting WRITE_EXTERNAL_STORAGE (if needed).

### c. Insert Permission Check Before mkdirs()
- Before each mkdirs() call (lines 145, 155, 165, 1028):
  - If Android <= 29, check permission. If not granted, request and return early.
  - If Android >= 30, proceed (app-specific storage is allowed).

### d. Handle Permission Result
- On permission grant, retry folder creation.
- On denial, show error/snackbar.

## 5. Example Code Snippet (Kotlin, Disk-Verified)

```kotlin
private val requestWritePermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { isGranted ->
    if (isGranted) {
        // Retry folder creation
    } else {
        Snackbar.make(requireView(), "Write permission is required to create folders", Snackbar.LENGTH_LONG).show()
    }
}

private fun hasWritePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // App-specific storage: permission not required
        true
    } else {
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun ensureWritePermissionAndCreateFolder(folderName: String) {
    if (!hasWritePermission()) {
        requestWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return
    }
    createStorageFolder(folderName)
}
```

## 6. Integration Points (by line, EnhancedMeshFragment.kt)
- Line 145: Replace direct mkdirs() with ensureWritePermissionAndCreateFolder("broadcasts")
- Line 155: Same as above for fallbackFolder
- Line 165: Same as above for fallbackFolder
- Line 1028: Same for newFolder

## 7. Summary
- No existing code for write permission check/request before mkdirs() (disk-verified).
- Plan above is ready for direct implementation.
- All code locations and signatures verified from disk.

---

**Prepared per AGENTS.md, Rule Zero, and DETAILED PLAN SPECIFICATION RULE.**
