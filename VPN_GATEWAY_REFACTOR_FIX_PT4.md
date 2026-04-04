# VPN Gateway Refactor Fix PT4
## AP Chip Disappears After Tab Switch

### Root Cause
`MeshUIBindings` is a singleton object. `deferredViewsInitialized` is never reset to `false` in
`onDestroyView()`. On the next `onViewCreated()` cycle, `setupWifiStateObserver()` fires; since
`deferredViewsInitialized` is already `true`, the StateFlow's immediate replay emission writes
`meshChipAp.visibility = VISIBLE` to the stale singleton reference (the detached chip from the
prior view). `bindDeferredViews()` then replaces the singleton reference with a fresh chip that
starts as `GONE` per XML default. No further StateFlow emission occurs (same value,
`distinctUntilChanged` deduplicates), so the new chip stays `GONE` permanently.

### Fix Summary
- **B-1** — Reset `deferredViewsInitialized = false` in `onDestroyView()` so the guard in
  `setupWifiStateObserver()` blocks the stale-reference write on the next creation cycle.
- **B-2** — After `bindDeferredViews()` sets `deferredViewsInitialized = true`, apply the
  current `wifiStateFlow.value` snapshot directly to the freshly-bound chips as a one-shot sync
  (StateFlow will not re-emit an unchanged value on its own).

---

## CHANGE B-1

**File:** `/home/d8rkl3ft/workspace/orbot-abhaya-android-deadend/app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** Lines 650–661 (`onDestroyView()` tail)

### BEFORE (Lines 650–661)
```kotlin
		if (this::networkOverviewMetricsJob.isInitialized) {
			networkOverviewMetricsJob.cancel()
		}
		
		// Unregister broadcast listener
		if (this::broadcastListener.isInitialized) {
			android.util.Log.d("EnhancedMeshFragment", "[BROADCAST] unregisterBroadcastListener (viewState=${viewLifecycleOwner.lifecycle.currentState})")
			meshrabiyaApi.unregisterBroadcastListener(broadcastListener)
		}
		
	}
```

### AFTER (Lines 650–661)
```kotlin
		if (this::networkOverviewMetricsJob.isInitialized) {
			networkOverviewMetricsJob.cancel()
		}
		
		// Unregister broadcast listener
		if (this::broadcastListener.isInitialized) {
			android.util.Log.d("EnhancedMeshFragment", "[BROADCAST] unregisterBroadcastListener (viewState=${viewLifecycleOwner.lifecycle.currentState})")
			meshrabiyaApi.unregisterBroadcastListener(broadcastListener)
		}

		deferredViewsInitialized = false
		
	}
```

---

## CHANGE B-2

**File:** `/home/d8rkl3ft/workspace/orbot-abhaya-android-deadend/app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** Lines 573–584 (ViewStub inflate callback, after `bindDeferredViews()`)

### BEFORE (Lines 573–584)
```kotlin
                        // Bind newly inflated deferred views (cards 4-9)
                        android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Binding deferred views...")
                        MeshUIBindings.bindDeferredViews(view)

                        // Mark deferred views as initialized
                        deferredViewsInitialized = true
                        android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Deferred views bound, flag set to true")

                        // Now that all views exist, wire up event listeners.
                        android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Setting up listeners...")
                        setupListeners()
                        android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Listeners setup complete")
```

### AFTER (Lines 573–584)
```kotlin
                        // Bind newly inflated deferred views (cards 4-9)
                        android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Binding deferred views...")
                        MeshUIBindings.bindDeferredViews(view)

                        // Mark deferred views as initialized
                        deferredViewsInitialized = true
                        android.util.Log.d("EnhancedMeshFragment", "[LIFECYCLE] Deferred views bound, flag set to true")

                        (meshrabiyaApi as? MeshrabiyaApiImpl)?.wifiStateFlow?.value?.let { wifiState ->
                            val isActingAsSta = wifiState.wifiStationState.status == "AVAILABLE"
                            val isActingAsAp = wifiState.wifiDirectState.hotspotStatus == "STARTED"
                                || wifiState.localOnlyHotspotState.status == "STARTED"
                            MeshUIBindings.meshChipMesh.visibility = View.VISIBLE
                            MeshUIBindings.meshChipSta.visibility = if (isActingAsSta) View.VISIBLE else View.GONE
                            MeshUIBindings.meshChipAp.visibility = if (isActingAsAp) View.VISIBLE else View.GONE
                        }

                        // Now that all views exist, wire up event listeners.
                        android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Setting up listeners...")
                        setupListeners()
                        android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Listeners setup complete")
```
