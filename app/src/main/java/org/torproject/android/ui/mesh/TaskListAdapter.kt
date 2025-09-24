package org.torproject.android.ui.mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.torproject.android.R
import org.torproject.android.service.compute.ServiceSearchResult

class TaskListAdapter(
    private val onTaskSelected: (ServiceSearchResult) -> Unit
) : ListAdapter<ServiceSearchResult, TaskListAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task_list_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onTaskSelected)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.taskName)
        private val type: TextView = itemView.findViewById(R.id.taskType)

        fun bind(task: ServiceSearchResult, onTaskSelected: (ServiceSearchResult) -> Unit) {
            name.text = task.manifest.serviceType.name + ": " + task.manifest.author
            type.text = task.manifest.serviceType.name
            itemView.setOnClickListener { onTaskSelected(task) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ServiceSearchResult>() {
        override fun areItemsTheSame(oldItem: ServiceSearchResult, newItem: ServiceSearchResult): Boolean =
            oldItem.serviceId == newItem.serviceId
        override fun areContentsTheSame(oldItem: ServiceSearchResult, newItem: ServiceSearchResult): Boolean =
            oldItem == newItem
    }
}
