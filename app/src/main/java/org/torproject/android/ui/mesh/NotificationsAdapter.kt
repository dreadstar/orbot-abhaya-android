package org.torproject.android.ui.mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.torproject.android.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.torproject.android.ui.mesh.model.NotificationFeedEntry
import org.torproject.android.ui.mesh.model.NotificationType

/**
 * RecyclerView adapter for displaying broadcast notifications
 */
class NotificationsAdapter(
    private val notifications: List<NotificationFeedEntry>
) : RecyclerView.Adapter<NotificationsAdapter.NotificationViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    inner class NotificationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val senderText: TextView = itemView.findViewById(R.id.notificationSenderText)
        val messageText: TextView = itemView.findViewById(R.id.notificationMessageText)
        val fileText: TextView = itemView.findViewById(R.id.notificationFileText)
        val timestampText: TextView = itemView.findViewById(R.id.notificationTimestampText)
        val errorText: TextView = itemView.findViewById(R.id.notificationErrorText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return NotificationViewHolder(view)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        val notification: NotificationFeedEntry = notifications[position]
        holder.timestampText.text = dateFormat.format(Date(notification.createdAt))

        holder.senderText.text = notification.senderNodeId?.let { "From: $it" } ?: ""

        when (notification.type) {
            NotificationType.BROADCAST -> {
                holder.messageText.text = notification.message ?: "(No message)"
                holder.messageText.setTextColor(android.graphics.Color.BLACK)
                holder.errorText.visibility = View.GONE
                if (!notification.filePath.isNullOrBlank()) {
                    holder.fileText.visibility = View.VISIBLE
                    holder.fileText.text = "📎 ${notification.filePath}"
                } else {
                    holder.fileText.visibility = View.GONE
                }
            }
            NotificationType.STATUS -> {
                holder.messageText.text = "Error: ${notification.message ?: "(Unknown error)"}"
                holder.messageText.setTextColor(android.graphics.Color.RED)
                holder.fileText.visibility = View.GONE
                holder.errorText.visibility = View.VISIBLE
                holder.errorText.text = notification.message ?: ""
            }
            NotificationType.STORAGE -> {
                holder.messageText.text = "Storage: ${notification.folderPath ?: "(No folder)"}"
                holder.messageText.setTextColor(android.graphics.Color.BLUE)
                holder.fileText.visibility = View.GONE
                holder.errorText.visibility = View.GONE
            }
            else -> {
                holder.messageText.text = notification.title
                holder.messageText.setTextColor(android.graphics.Color.BLACK)
                holder.fileText.visibility = View.GONE
                holder.errorText.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = notifications.size

    fun submitList(newNotifications: List<NotificationFeedEntry>) {
        (this as RecyclerView.Adapter<*>).apply {
            // Replace notifications list and notify adapter
            val field = NotificationsAdapter::class.java.getDeclaredField("notifications")
            field.isAccessible = true
            field.set(this@NotificationsAdapter, newNotifications)
            notifyDataSetChanged()
        }
    }
}
