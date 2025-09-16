package org.torproject.android.ui.friends.model

/**
 * Data class representing a friend contact with .onion address
 * 
 * @property id Unique identifier for the friend
 * @property nickname User-defined nickname for the friend
 * @property onionAddress The friend's .onion public key/address
 * @property isOnline Current online status (based on service availability)
 * @property dateAdded Timestamp when friend was added
 * @property lastSeen Timestamp of last successful connection
 */
data class Friend(
    val id: String,
    val nickname: String,
    val onionAddress: String,
    val isOnline: Boolean = false,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastSeen: Long? = null
) {
    /**
     * Get short display version of .onion address for UI
     */
    fun getShortOnionAddress(): String {
        return if (onionAddress.length > 16) {
            "${onionAddress.take(8)}...${onionAddress.takeLast(8)}"
        } else {
            onionAddress
        }
    }
    
    /**
     * Check if friend was recently online (within last 5 minutes)
     */
    fun wasRecentlyOnline(): Boolean {
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        return lastSeen?.let { it > fiveMinutesAgo } ?: false
    }
}
