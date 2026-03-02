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
import android.net.Uri

/**
 * RecyclerView adapter for displaying broadcast notifications
 */
class NotificationsAdapter(
    private val notifications: List<NotificationFeedEntry>,
    private val onDismiss: (NotificationFeedEntry) -> Unit
) : RecyclerView.Adapter<NotificationsAdapter.NotificationViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    inner class NotificationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val senderText: TextView = itemView.findViewById(R.id.notificationSenderText)
        val closeButton: android.widget.ImageButton = itemView.findViewById(R.id.notificationCloseButton)
        val messageText: TextView = itemView.findViewById(R.id.notificationMessageText)
        val locationText: TextView = itemView.findViewById(R.id.notificationLocationText)
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
                    val displayPath = try {
                        val uri = Uri.parse(notification.filePath)
                        if (uri.scheme == "content") {
                            // Query ContentResolver for display name
                            val cursor = holder.itemView.context.contentResolver.query(
                                uri,
                                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                null,
                                null,
                                null
                            )
                            cursor?.use {
                                if (it.moveToFirst()) {
                                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (nameIndex >= 0) {
                                        it.getString(nameIndex) ?: notification.filePath
                                    } else {
                                        notification.filePath
                                    }
                                } else {
                                    notification.filePath
                                }
                            } ?: notification.filePath
                        } else {
                            notification.filePath
                        }
                    } catch (_: Exception) {
                        notification.filePath
                    }
                    holder.fileText.text = "📎 $displayPath"
                } else {
                    holder.fileText.visibility = View.GONE
                }
                holder.closeButton.setOnClickListener { onDismiss(notification) }
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

        // location line (always after basic fields)
        if (notification.latitude != null && notification.longitude != null) {
            holder.locationText.visibility = View.VISIBLE
            holder.locationText.text = "📍 ${notification.latitude}, ${notification.longitude}"
        } else {
            holder.locationText.visibility = View.GONE
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

