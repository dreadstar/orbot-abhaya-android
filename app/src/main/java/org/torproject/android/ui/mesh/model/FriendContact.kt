package org.torproject.android.ui.mesh.model

data class FriendContact(
    val id: String,
    val displayName: String,
    val isOnline: Boolean = false
)
