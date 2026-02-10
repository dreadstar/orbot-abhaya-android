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

/**
 * RecyclerView adapter for displaying broadcast notifications
 */
class NotificationsAdapter(
    private val notifications: List<BroadcastNotification>
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
        val notification = notifications[position]
        
        holder.senderText.text = "From: ${notification.senderNodeId}"
        holder.timestampText.text = dateFormat.format(Date(notification.timestamp))
        
        if (notification.hasError) {
            holder.messageText.text = "Error: ${notification.errorMessage}"
            holder.messageText.setTextColor(android.graphics.Color.RED)
            holder.fileText.visibility = View.GONE
            holder.errorText.visibility = View.VISIBLE
            holder.errorText.text = notification.errorMessage
        } else {
            holder.messageText.text = notification.messageText.ifEmpty { "(No message)" }
            holder.messageText.setTextColor(android.graphics.Color.BLACK)
            holder.errorText.visibility = View.GONE
            
            if (notification.fileName.isNotBlank()) {
                holder.fileText.visibility = View.VISIBLE
                holder.fileText.text = "📎 ${notification.fileName}"
            } else {
                holder.fileText.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = notifications.size
}
