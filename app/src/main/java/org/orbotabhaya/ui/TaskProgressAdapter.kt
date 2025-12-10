package org.orbotabhaya.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

import org.torproject.android.R

// Simulated data class replacing TaskProgress
data class SimulatedTaskProgress(
    val taskId: String,
    val taskName: String,
    val percentComplete: Int,
    val status: String
)

class TaskProgressAdapter : ListAdapter<SimulatedTaskProgress, TaskProgressAdapter.TaskProgressViewHolder>(TaskProgressDiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskProgressViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_task_progress, parent, false)
        return TaskProgressViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskProgressViewHolder, position: Int) {
        val progress = getItem(position)
        holder.bind(progress)
    }

    class TaskProgressViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val taskName: TextView = itemView.findViewById(R.id.task_name)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.task_progress_bar)
        private val statusText: TextView = itemView.findViewById(R.id.task_status)
        fun bind(progress: SimulatedTaskProgress) {
            taskName.text = progress.taskName
            progressBar.progress = progress.percentComplete
            statusText.text = progress.status
        }
    }

    class TaskProgressDiffCallback : DiffUtil.ItemCallback<SimulatedTaskProgress>() {
        override fun areItemsTheSame(oldItem: SimulatedTaskProgress, newItem: SimulatedTaskProgress): Boolean {
            return oldItem.taskId == newItem.taskId
        }
        override fun areContentsTheSame(oldItem: SimulatedTaskProgress, newItem: SimulatedTaskProgress): Boolean {
            return oldItem == newItem
        }
    }
}
