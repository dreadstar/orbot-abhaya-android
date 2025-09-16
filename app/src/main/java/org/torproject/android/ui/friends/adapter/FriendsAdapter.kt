package org.torproject.android.ui.friends.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.torproject.android.R
import org.torproject.android.ui.friends.model.Friend

/**
 * RecyclerView adapter for displaying friends list
 */
class FriendsAdapter(
    private val friends: MutableList<Friend>,
    private val onShowInfoClicked: (Friend) -> Unit,
    private val onMessageClicked: (Friend) -> Unit,
    private val onDeleteClicked: (Friend) -> Unit
) : RecyclerView.Adapter<FriendsAdapter.FriendViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_friend, parent, false)
        return FriendViewHolder(view)
    }

    override fun onBindViewHolder(holder: FriendViewHolder, position: Int) {
        holder.bind(friends[position])
    }

    override fun getItemCount(): Int = friends.size

    inner class FriendViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nicknameTextView: TextView = itemView.findViewById(R.id.tv_nickname)
        private val onionAddressTextView: TextView = itemView.findViewById(R.id.tv_onion_address)
        private val onlineStatusTextView: TextView = itemView.findViewById(R.id.tv_online_status)
        private val infoButton: MaterialButton = itemView.findViewById(R.id.btn_info)
        private val messageButton: MaterialButton = itemView.findViewById(R.id.btn_message)
        private val deleteButton: MaterialButton = itemView.findViewById(R.id.btn_delete)

        fun bind(friend: Friend) {
            nicknameTextView.text = friend.nickname
            onionAddressTextView.text = friend.getShortOnionAddress()
            
            // Set online status
            if (friend.isOnline) {
                onlineStatusTextView.text = "Online"
                onlineStatusTextView.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.status_online)
                )
            } else if (friend.wasRecentlyOnline()) {
                onlineStatusTextView.text = "Recently Online"
                onlineStatusTextView.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.status_recently_online)
                )
            } else {
                onlineStatusTextView.text = "Offline"
                onlineStatusTextView.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.status_offline)
                )
            }

            // Set click listeners
            infoButton.setOnClickListener { onShowInfoClicked(friend) }
            messageButton.setOnClickListener { onMessageClicked(friend) }
            deleteButton.setOnClickListener { onDeleteClicked(friend) }
        }
    }
}
