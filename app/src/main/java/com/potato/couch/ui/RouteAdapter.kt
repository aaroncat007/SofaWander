package com.potato.couch.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.potato.couch.R

class RouteAdapter(
    private val onClick: (RouteItem) -> Unit
) : ListAdapter<RouteItem, RouteAdapter.RouteViewHolder>(DiffCallback()) {

    private var selectedId: Long? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route, parent, false)
        return RouteViewHolder(view, onClick) { id -> setSelected(id) }
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        holder.bind(getItem(position), selectedId)
    }

    fun getSelectedId(): Long? = selectedId

    private fun setSelected(id: Long) {
        val previousId = selectedId
        selectedId = id
        if (previousId == id) return
        if (previousId != null) {
            val previousIndex = currentList.indexOfFirst { it.id == previousId }
            if (previousIndex >= 0) notifyItemChanged(previousIndex)
        }
        val newIndex = currentList.indexOfFirst { it.id == id }
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }

    class RouteViewHolder(
        itemView: View,
        private val onClick: (RouteItem) -> Unit,
        private val onSelect: (Long) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val nameView: TextView = itemView.findViewById(R.id.textRouteName)

        fun bind(item: RouteItem, selectedId: Long?) {
            nameView.text = item.name
            itemView.isSelected = item.id == selectedId
            itemView.setOnClickListener {
                onSelect(item.id)
                onClick(item)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<RouteItem>() {
        override fun areItemsTheSame(oldItem: RouteItem, newItem: RouteItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: RouteItem, newItem: RouteItem): Boolean {
            return oldItem == newItem
        }
    }
}
