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
import org.torproject.android.ui.mesh.model.toStorageItem
import org.torproject.android.ui.mesh.model.getFormattedSize
import org.torproject.android.ui.mesh.model.getFormattedDate
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi

class DropFolderAdapter(
    private val onShareClicked: (StorageItem) -> Unit,
    private val meshrabiyaApi: MeshrabiyaApi,
    private val folderId: String
) : ListAdapter<StorageItem, DropFolderAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder_content, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onShareClicked)
    }

    fun loadDropFolderContents() {
        // Use MeshrabiyaApi.getDropFolderFiles for folder contents
        val files = meshrabiyaApi.getDropFolderFiles()
        // If you need to filter by folderId, do so here
        val filteredFiles = files.filter { file ->
            // Example: filter by folder path or id if needed
            // file.parentFile?.name == folderId
            true // No filtering by default
        }
        submitList(filteredFiles.map { it.toStorageItem() })
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

            // All download logic must be routed through MeshrabiyaApi only
            // Example: Use MeshrabiyaApi.listFiles or MeshrabiyaApi.listFolders for data operations
            // If download functionality is required, ensure MeshrabiyaApi provides a downloadFile or similar method
            // If not present, STOP and request user guidance before proceeding
            // Remove legacy StorageDropFolderManager and direct download logic
            // ...existing code...
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<StorageItem>() {
        override fun areItemsTheSame(oldItem: StorageItem, newItem: StorageItem): Boolean =
            oldItem.path == newItem.path
        override fun areContentsTheSame(oldItem: StorageItem, newItem: StorageItem): Boolean =
            oldItem == newItem
    }
}
