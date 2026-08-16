package com.darkroot.ytdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class LibraryAdapter(
    private val onPlayClick: (LibraryItem) -> Unit,
    private val onShareClick: (LibraryItem) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.LibraryViewHolder>() {

    private val items = mutableListOf<LibraryItem>()

    fun submitList(newItems: List<LibraryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_library, parent, false)
        return LibraryViewHolder(view)
    }

    override fun onBindViewHolder(holder: LibraryViewHolder, position: Int) {
        holder.bind(items[position], onPlayClick, onShareClick)
    }

    override fun getItemCount(): Int = items.size

    class LibraryViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val typeIcon: ImageView = itemView.findViewById(R.id.libraryTypeIcon)
        private val fileName: TextView = itemView.findViewById(R.id.libraryFileName)
        private val shareButton: ImageButton = itemView.findViewById(R.id.libraryShareButton)

        fun bind(item: LibraryItem, onPlayClick: (LibraryItem) -> Unit, onShareClick: (LibraryItem) -> Unit) {
            fileName.text = item.name

            when (item.kind) {
                MediaKind.PHOTO -> {
                    // Show an actual thumbnail preview for photos instead of a generic icon
                    typeIcon.load(item.uri)
                }
                MediaKind.AUDIO -> {
                    typeIcon.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                }
                MediaKind.VIDEO -> {
                    typeIcon.setImageResource(android.R.drawable.ic_media_play)
                }
            }

            itemView.setOnClickListener { onPlayClick(item) }
            shareButton.setOnClickListener { onShareClick(item) }
        }
    }
}
