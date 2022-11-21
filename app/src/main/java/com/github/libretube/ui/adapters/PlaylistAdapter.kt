package com.github.libretube.ui.adapters

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.databinding.PlaylistRowBinding
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toID
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.extensions.setFormattedDuration
import com.github.libretube.ui.extensions.setWatchProgressLength
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet
import com.github.libretube.ui.viewholders.PlaylistViewHolder
import com.github.libretube.util.ImageHelper
import com.github.libretube.util.NavigationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

class PlaylistAdapter(
    private val videoFeed: MutableList<StreamItem>,
    private val playlistId: String,
    private val playlistType: PlaylistType
) : RecyclerView.Adapter<PlaylistViewHolder>() {

    override fun getItemCount(): Int {
        return videoFeed.size
    }

    fun updateItems(newItems: List<StreamItem>) {
        val oldSize = videoFeed.size
        videoFeed.addAll(newItems)
        notifyItemRangeInserted(oldSize, videoFeed.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = PlaylistRowBinding.inflate(layoutInflater, parent, false)
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val streamItem = videoFeed[position]
        holder.binding.apply {
            playlistTitle.text = streamItem.title
            playlistDescription.text = streamItem.uploaderName
            thumbnailDuration.setFormattedDuration(streamItem.duration!!)
            ImageHelper.loadImage(streamItem.thumbnail, playlistThumbnail)
            root.setOnClickListener {
                NavigationHelper.navigateVideo(root.context, streamItem.url, playlistId)
            }
            val videoId = streamItem.url!!.toID()
            val videoName = streamItem.title!!
            root.setOnLongClickListener {
                VideoOptionsBottomSheet(videoId, videoName)
                    .show(
                        (root.context as BaseActivity).supportFragmentManager,
                        VideoOptionsBottomSheet::class.java.name
                    )
                true
            }

            if (playlistType != PlaylistType.PUBLIC) {
                deletePlaylist.visibility = View.VISIBLE
                deletePlaylist.setOnClickListener {
                    removeFromPlaylist(root.context, position)
                }
            }
            watchProgress.setWatchProgressLength(videoId, streamItem.duration!!)
        }
    }

    fun removeFromPlaylist(context: Context, position: Int) {
        videoFeed.removeAt(position)
        (context as Activity).runOnUiThread {
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, itemCount)
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PlaylistsHelper.removeFromPlaylist(playlistId, position)
            } catch (e: IOException) {
                Log.e(TAG(), e.toString())
                return@launch
            }
        }
    }
}
