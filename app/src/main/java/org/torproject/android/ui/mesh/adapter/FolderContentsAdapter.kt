package org.torproject.android.ui.mesh.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import org.torproject.android.R
import org.torproject.android.ui.mesh.model.StorageItem

/**
 * Adapter for displaying folder contents in the Storage Drop Folder card
 */
class FolderContentsAdapter(
    private val onShareClick: (StorageItem) -> Unit
) : ListAdapter<StorageItem, FolderContentsAdapter.ViewHolder>(StorageItemDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder_content, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val itemIcon: ShapeableImageView = itemView.findViewById(R.id.itemIcon)
        private val itemName: TextView = itemView.findViewById(R.id.itemName)
        private val itemDetails: TextView = itemView.findViewById(R.id.itemDetails)
        private val shareButton: MaterialButton = itemView.findViewById(R.id.shareButton)

        fun bind(item: StorageItem) {
            itemName.text = item.name
            itemDetails.text = "${item.getFormattedSize()} • ${item.getFormattedDate()}"
            
            // Set appropriate icon based on file type
            val iconRes = when (item.getFileExtension()) {
                "folder" -> R.drawable.ic_folder_24
                "txt", "md", "doc", "docx" -> R.drawable.ic_text_file_24
                "jpg", "jpeg", "png", "gif", "bmp" -> R.drawable.ic_image_24
                "mp4", "avi", "mkv", "mov" -> R.drawable.ic_video_24
                "mp3", "wav", "flac", "ogg" -> R.drawable.ic_audio_24
                "pdf" -> R.drawable.ic_pdf_24
                "zip", "rar", "tar", "gz" -> R.drawable.ic_archive_24
                else -> R.drawable.ic_file_24
            }
            
            try {
                itemIcon.setImageResource(iconRes)
            } catch (e: Exception) {
                // Fallback to generic file icon if specific icon not found
                itemIcon.setImageResource(R.drawable.ic_file_24)
            }
            
            // Update share button state
            if (item.isShared) {
                shareButton.text = "Shared (${item.sharedWith.size})"
                shareButton.setIconResource(R.drawable.ic_shared_24)
            } else {
                shareButton.text = itemView.context.getString(R.string.storage_share_with)
                shareButton.setIconResource(R.drawable.ic_share_24)
            }
            
            shareButton.setOnClickListener {
                onShareClick(item)
            }
        }
    }

    private class StorageItemDiffCallback : DiffUtil.ItemCallback<StorageItem>() {
        override fun areItemsTheSame(oldItem: StorageItem, newItem: StorageItem): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: StorageItem, newItem: StorageItem): Boolean {
            return oldItem == newItem
        }
    }
}
