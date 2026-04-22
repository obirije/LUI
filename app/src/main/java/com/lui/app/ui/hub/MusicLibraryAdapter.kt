package com.lui.app.ui.hub

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lui.app.R
import com.lui.app.data.GeneratedTrackEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple RecyclerView adapter for the generated-music library.
 * Callbacks are passed in from the fragment rather than owning state here —
 * the adapter is intentionally thin so the fragment can reconcile playback
 * state (which track is currently looping) against the observed list.
 */
class MusicLibraryAdapter(
    private val onPlayToggle: (GeneratedTrackEntity) -> Unit,
    private val onFavoriteToggle: (GeneratedTrackEntity) -> Unit,
    private val onDelete: (GeneratedTrackEntity) -> Unit,
    private val onRename: (GeneratedTrackEntity) -> Unit,
    private val playingIdProvider: () -> Long?
) : ListAdapter<GeneratedTrackEntity, MusicLibraryAdapter.VH>(DIFF) {

    private val dateFormat = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())

    class VH(root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val play: ImageButton = root.findViewById(R.id.playToggle)
        val favorite: ImageButton = root.findViewById(R.id.favoriteToggle)
        val delete: ImageButton = root.findViewById(R.id.deleteTrack)
        val name: TextView = root.findViewById(R.id.trackName)
        val meta: TextView = root.findViewById(R.id.trackMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_generated_track, parent, false) as LinearLayout
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = getItem(position)
        holder.name.text = track.displayName.ifBlank { "Untitled track" }

        val durationLabel = if (track.durationMs >= 1000) {
            val sec = (track.durationMs / 1000).toInt()
            "${sec}s"
        } else "—"
        holder.meta.text = "$durationLabel · ${dateFormat.format(Date(track.createdAt))}"

        val isPlaying = playingIdProvider() == track.id
        holder.play.setImageResource(
            if (isPlaying) R.drawable.ic_stop_small else R.drawable.ic_play_small
        )
        holder.play.contentDescription = if (isPlaying) "Stop" else "Play"

        holder.favorite.setImageResource(
            if (track.favorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )

        holder.play.setOnClickListener { onPlayToggle(track) }
        holder.favorite.setOnClickListener { onFavoriteToggle(track) }
        holder.delete.setOnClickListener { onDelete(track) }
        holder.name.setOnClickListener { onRename(track) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<GeneratedTrackEntity>() {
            override fun areItemsTheSame(a: GeneratedTrackEntity, b: GeneratedTrackEntity) = a.id == b.id
            override fun areContentsTheSame(a: GeneratedTrackEntity, b: GeneratedTrackEntity) = a == b
        }
    }
}
