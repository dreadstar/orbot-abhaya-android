package org.torproject.android.ui.mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.torproject.android.R
import org.torproject.android.ui.mesh.model.FriendContact

class FriendListAdapter(
    private val onFriendSelected: (FriendContact) -> Unit
) : ListAdapter<FriendContact, FriendListAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_friend_list_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onFriendSelected)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.friendName)
        private val status: TextView = itemView.findViewById(R.id.friendStatus)

        fun bind(friend: FriendContact, onFriendSelected: (FriendContact) -> Unit) {
            name.text = friend.displayName
            status.text = if (friend.isOnline) "Online" else "Offline"
            itemView.setOnClickListener { onFriendSelected(friend) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<FriendContact>() {
        override fun areItemsTheSame(oldItem: FriendContact, newItem: FriendContact): Boolean =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: FriendContact, newItem: FriendContact): Boolean =
            oldItem == newItem
    }
}
