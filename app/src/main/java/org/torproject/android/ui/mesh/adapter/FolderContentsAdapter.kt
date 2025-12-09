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
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi

/**
 * Adapter for displaying folder contents in the Storage Drop Folder card
 */
class FolderContentsAdapter(
    private val onShareClick: (StorageItem) -> Unit,
    private val meshrabiyaApi: MeshrabiyaApi,
    private val folderId: String
) : ListAdapter<StorageItem, FolderContentsAdapter.ViewHolder>(StorageItemDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder_content, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun loadFolderContents() {
        // Use MeshrabiyaApi.getDropFolderFiles for folder contents
        val files = meshrabiyaApi.getDropFolderFiles()
        submitList(files.map {
            StorageItem(
                name = it.name,
                path = it.absolutePath,
                isDirectory = it.isDirectory,
                size = if (it.isFile) it.length() else 0L,
                lastModified = it.lastModified(),
                isShared = false,
                sharedWith = emptySet()
            )
        })
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(item: StorageItem) {
            // Bind StorageItem to UI
        }
    }

    class StorageItemDiffCallback : DiffUtil.ItemCallback<StorageItem>() {
        override fun areItemsTheSame(oldItem: StorageItem, newItem: StorageItem): Boolean {
            return oldItem.path == newItem.path
        }
        override fun areContentsTheSame(oldItem: StorageItem, newItem: StorageItem): Boolean {
            return oldItem == newItem
        }
    }
}
