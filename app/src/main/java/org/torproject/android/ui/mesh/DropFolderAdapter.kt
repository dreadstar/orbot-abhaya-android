package org.torproject.android.ui.mesh

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

class DropFolderAdapter(
    private val onShareClicked: (StorageItem) -> Unit
) : ListAdapter<StorageItem, DropFolderAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder_content, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onShareClicked)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ShapeableImageView = itemView.findViewById(R.id.itemIcon)
        private val name: TextView = itemView.findViewById(R.id.itemName)
        private val details: TextView = itemView.findViewById(R.id.itemDetails)
        private val shareButton: MaterialButton = itemView.findViewById(R.id.shareButton)

        fun bind(item: StorageItem, onShareClicked: (StorageItem) -> Unit) {
            name.text = item.name
            details.text = "${item.getFormattedSize()} • ${item.getFormattedDate()}"
            // TODO: Set icon based on file/folder type
            shareButton.setOnClickListener { onShareClicked(item) }

            if (item.isShared && item.sharedWith.contains("everyone")) {
                itemView.alpha = 0.5f
                // Use a tag-based approach to avoid missing id resources
                val existing = itemView.getTag(com.google.android.material.R.id.snackbar_text) as? MaterialButton
                if (existing == null) {
                    val downloadButton = MaterialButton(itemView.context).apply {
                        text = "Download"
                        textSize = 12f
                        setOnClickListener {
                            val storageManager = org.torproject.android.service.storage.StorageDropFolderManager.getInstance(itemView.context)
                            storageManager.downloadSharedItem(item)
                        }
                    }
                    (itemView as ViewGroup).addView(downloadButton)
                    itemView.setTag(com.google.android.material.R.id.snackbar_text, downloadButton)
                }
            } else {
                itemView.alpha = 1.0f
                val existing = itemView.getTag(com.google.android.material.R.id.snackbar_text) as? MaterialButton
                if (existing != null) {
                    (itemView as ViewGroup).removeView(existing)
                    itemView.setTag(com.google.android.material.R.id.snackbar_text, null)
                }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<StorageItem>() {
        override fun areItemsTheSame(oldItem: StorageItem, newItem: StorageItem): Boolean =
            oldItem.path == newItem.path
        override fun areContentsTheSame(oldItem: StorageItem, newItem: StorageItem): Boolean =
            oldItem == newItem
    }
}
