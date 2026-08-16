package com.darkroot.ytdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class QueueAdapter(
    private val onActionClick: (QueueItem) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private val items = mutableListOf<QueueItem>()

    fun submitList(newItems: List<QueueItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Update a single item's row without rebinding the whole list - keeps
     * progress bar animation smooth during active downloads. */
    fun updateItem(item: QueueItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index != -1) {
            items[index] = item
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(items[position], onActionClick)
    }

    override fun getItemCount(): Int = items.size

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.queueThumbnail)
        private val title: TextView = itemView.findViewById(R.id.queueTitle)
        private val status: TextView = itemView.findViewById(R.id.queueStatus)
        private val progress: ProgressBar = itemView.findViewById(R.id.queueProgress)
        private val actionButton: ImageButton = itemView.findViewById(R.id.queueActionButton)

        fun bind(item: QueueItem, onActionClick: (QueueItem) -> Unit) {
            title.text = item.title.ifBlank { item.url }

            if (!item.thumbnailUrl.isNullOrBlank()) {
                thumbnail.load(item.thumbnailUrl)
            } else {
                thumbnail.setImageDrawable(null)
            }

            val statusText = when (item.status) {
                QueueStatus.QUEUED -> "Waiting in queue"
                QueueStatus.FETCHING_INFO -> "Fetching video info..."
                QueueStatus.DOWNLOADING -> "Downloading... ${item.progress}%"
                QueueStatus.PAUSED -> "Paused"
                QueueStatus.DONE -> "Saved: ${item.savedFileName ?: ""}"
                QueueStatus.ERROR -> "Error: ${item.errorMessage ?: "unknown"}"
                QueueStatus.CANCELLED -> "Cancelled"
            }
            status.text = statusText

            progress.visibility = if (item.status == QueueStatus.DOWNLOADING || item.status == QueueStatus.PAUSED) {
                View.VISIBLE
            } else {
                View.GONE
            }
            progress.progress = item.progress

            // Action button doubles as pause (while downloading), resume
            // (while paused), or remove (any other state)
            val iconRes = when (item.status) {
                QueueStatus.DOWNLOADING -> android.R.drawable.ic_media_pause
                QueueStatus.PAUSED -> android.R.drawable.ic_media_play
                else -> android.R.drawable.ic_menu_close_clear_cancel
            }
            actionButton.setImageResource(iconRes)
            actionButton.setOnClickListener { onActionClick(item) }
        }
    }
}
