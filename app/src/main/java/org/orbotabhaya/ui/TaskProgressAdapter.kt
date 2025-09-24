package org.orbotabhaya.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.orbotabhaya.task.TaskProgress
import org.torproject.android.R

class TaskProgressAdapter : ListAdapter<TaskProgress, TaskProgressAdapter.TaskProgressViewHolder>(TaskProgressDiffCallback()) {
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
        fun bind(progress: TaskProgress) {
            taskName.text = progress.taskName
            progressBar.progress = progress.percentComplete
            statusText.text = progress.status
        }
    }

    class TaskProgressDiffCallback : DiffUtil.ItemCallback<TaskProgress>() {
        override fun areItemsTheSame(oldItem: TaskProgress, newItem: TaskProgress): Boolean {
            return oldItem.taskId == newItem.taskId
        }
        override fun areContentsTheSame(oldItem: TaskProgress, newItem: TaskProgress): Boolean {
            return oldItem == newItem
        }
    }
}
