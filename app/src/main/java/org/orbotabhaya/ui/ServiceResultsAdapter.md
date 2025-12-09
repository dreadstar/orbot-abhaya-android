package org.orbotabhaya.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.orbotabhaya.task.ServiceMetadata
import org.torproject.android.R

class ServiceResultsAdapter(
    private val onServiceSelected: (ServiceMetadata) -> Unit
) : ListAdapter<ServiceMetadata, ServiceResultsAdapter.ServiceViewHolder>(ServiceDiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServiceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_service_result, parent, false)
        return ServiceViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServiceViewHolder, position: Int) {
        val service = getItem(position)
        holder.bind(service)
        holder.itemView.setOnClickListener { onServiceSelected(service) }
    }

    class ServiceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.service_name)
        private val descText: TextView = itemView.findViewById(R.id.service_description)
        fun bind(service: ServiceMetadata) {
            nameText.text = service.name
            descText.text = service.description
        }
    }

    class ServiceDiffCallback : DiffUtil.ItemCallback<ServiceMetadata>() {
        override fun areItemsTheSame(oldItem: ServiceMetadata, newItem: ServiceMetadata): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: ServiceMetadata, newItem: ServiceMetadata): Boolean {
            return oldItem == newItem
        }
    }
}
