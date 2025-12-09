package org.torproject.android.ui.mesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.torproject.android.R
// Simulated mesh service task for UI

data class SimulatedServiceTask(
    val serviceId: String,
    val serviceName: String,
    val author: String,
    val serviceType: String
)

class TaskListAdapter(
    private val onTaskSelected: (SimulatedServiceTask) -> Unit
) : ListAdapter<SimulatedServiceTask, TaskListAdapter.ViewHolder>(DiffCallback) {

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

        fun bind(task: SimulatedServiceTask, onTaskSelected: (SimulatedServiceTask) -> Unit) {
            name.text = task.serviceType + ": " + task.author
            type.text = task.serviceType
            itemView.setOnClickListener { onTaskSelected(task) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SimulatedServiceTask>() {
        override fun areItemsTheSame(oldItem: SimulatedServiceTask, newItem: SimulatedServiceTask): Boolean =
            oldItem.serviceId == newItem.serviceId
        override fun areContentsTheSame(oldItem: SimulatedServiceTask, newItem: SimulatedServiceTask): Boolean =
            oldItem == newItem
    }
}
