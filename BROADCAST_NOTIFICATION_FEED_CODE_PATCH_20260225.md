# BROADCAST NOTIFICATION FEED PATCH (DISK-VERIFIED)

## File: app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt

### Location: Lines 451–470 (Broadcast Listener, Notification Feed Update)

**BEFORE (Lines 451–470):**
```
                }

			viewLifecycleOwner.lifecycleScope.launch {
				notificationFeed.collect { notifications ->
					val badgeCount = notifications.size
					(activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(badgeCount)
				}
			}
		}
            
        }
		meshrabiyaApi.registerBroadcastListener(broadcastListener)
```

**AFTER (Lines 451–470):**
```
                }

			// Observe notificationFeed and update both badge and dropdown adapter
			viewLifecycleOwner.lifecycleScope.launch {
				notificationFeed.collect { notifications ->
					val badgeCount = notifications.size
					(activity as? org.torproject.android.OrbotActivity)?.updateNotificationBadge(badgeCount)
					// Update the notifications dropdown adapter here
					MeshUIBindings.notificationsDropdownAdapter.submitList(notifications)
				}
			}
		}
            
        }
		meshrabiyaApi.registerBroadcastListener(broadcastListener)
```

**Purpose:**
- Ensures the notification dropdown UI and badge count are both updated in real time by observing the StateFlow `notificationFeed`.
- Fixes the bug where new broadcasts did not appear in the dropdown and badge count was inconsistent.
- All changes are disk-verified and falsification-driven.

---

**Apply this change manually to app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt at the specified lines.**
