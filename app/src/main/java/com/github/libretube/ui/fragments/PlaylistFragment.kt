package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentPlaylistBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.PlaylistBookmark
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.awaitQuery
import com.github.libretube.extensions.query
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toPixel
import com.github.libretube.ui.adapters.PlaylistAdapter
import com.github.libretube.ui.base.BaseFragment
import com.github.libretube.ui.extensions.serializable
import com.github.libretube.ui.models.PlayerViewModel
import com.github.libretube.ui.sheets.PlaylistOptionsBottomSheet
import com.github.libretube.util.ImageHelper
import com.github.libretube.util.NavigationHelper
import com.github.libretube.util.TextUtils
import retrofit2.HttpException
import java.io.IOException

class PlaylistFragment : BaseFragment() {
    private lateinit var binding: FragmentPlaylistBinding

    private var playlistId: String? = null
    private var playlistName: String? = null
    private var playlistType: PlaylistType = PlaylistType.PUBLIC
    private var nextPage: String? = null
    private var playlistAdapter: PlaylistAdapter? = null
    private var isLoading = true
    private var isBookmarked = false

    private val playerViewModel: PlayerViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            playlistId = it.getString(IntentData.playlistId)
            playlistType = it.serializable(IntentData.playlistType) ?: PlaylistType.PUBLIC
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPlaylistBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playlistId = playlistId!!.toID()
        binding.playlistRecView.layoutManager = LinearLayoutManager(context)

        binding.playlistProgress.visibility = View.VISIBLE

        isBookmarked = awaitQuery {
            DatabaseHolder.Database.playlistBookmarkDao().includes(playlistId!!)
        }
        updateBookmarkRes()

        playerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) {
            binding.playlistRecView.updatePadding(
                bottom = if (it) (64).toPixel().toInt() else 0
            )
        }

        fetchPlaylist()
    }

    private fun updateBookmarkRes() {
        binding.bookmark.setIconResource(
            if (isBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outlined
        )
    }

    @SuppressLint("SetTextI18n")
    private fun fetchPlaylist() {
        binding.playlistScrollview.visibility = View.GONE
        lifecycleScope.launchWhenCreated {
            val response = try {
                PlaylistsHelper.getPlaylist(playlistId!!)
            } catch (e: IOException) {
                println(e)
                Log.e(TAG(), "IOException, you might not have internet connection")
                return@launchWhenCreated
            } catch (e: HttpException) {
                Log.e(TAG(), "HttpException, unexpected response")
                return@launchWhenCreated
            }
            binding.playlistScrollview.visibility = View.VISIBLE
            nextPage = response.nextpage
            playlistName = response.name
            isLoading = false
            runOnUiThread {
                ImageHelper.loadImage(response.thumbnailUrl, binding.thumbnail)
                binding.playlistProgress.visibility = View.GONE
                binding.playlistName.text = response.name

                binding.playlistName.setOnClickListener {
                    binding.playlistName.maxLines = if (binding.playlistName.maxLines == 2) Int.MAX_VALUE else 2
                }

                binding.playlistInfo.text = (if (response.uploader != null) response.uploader + TextUtils.SEPARATOR else "") +
                    getString(R.string.videoCount, response.videos.toString())

                // show playlist options
                binding.optionsMenu.setOnClickListener {
                    PlaylistOptionsBottomSheet(playlistId!!, playlistName ?: "", playlistType).show(
                        childFragmentManager,
                        PlaylistOptionsBottomSheet::class.java.name
                    )
                }

                binding.playAll.setOnClickListener {
                    if (response.relatedStreams.orEmpty().isEmpty()) return@setOnClickListener
                    NavigationHelper.navigateVideo(
                        requireContext(),
                        response.relatedStreams!!.first().url?.toID(),
                        playlistId
                    )
                }

                if (playlistType != PlaylistType.PUBLIC) binding.bookmark.visibility = View.GONE

                binding.bookmark.setOnClickListener {
                    isBookmarked = !isBookmarked
                    updateBookmarkRes()
                    query {
                        if (!isBookmarked) {
                            DatabaseHolder.Database.playlistBookmarkDao().deleteById(playlistId!!)
                        } else {
                            DatabaseHolder.Database.playlistBookmarkDao().insertAll(
                                PlaylistBookmark(
                                    playlistId = playlistId!!,
                                    playlistName = response.name,
                                    thumbnailUrl = response.thumbnailUrl,
                                    uploader = response.uploader,
                                    uploaderAvatar = response.uploaderAvatar,
                                    uploaderUrl = response.uploaderUrl
                                )
                            )
                        }
                    }
                }

                playlistAdapter = PlaylistAdapter(
                    response.relatedStreams.orEmpty().toMutableList(),
                    playlistId!!,
                    playlistType
                )

                // listen for playlist items to become deleted
                playlistAdapter!!.registerAdapterDataObserver(object :
                        RecyclerView.AdapterDataObserver() {
                        override fun onChanged() {
                            binding.playlistInfo.text =
                                binding.playlistInfo.text.split(TextUtils.SEPARATOR).first() + TextUtils.SEPARATOR + getString(
                                R.string.videoCount,
                                playlistAdapter!!.itemCount.toString()
                            )
                        }
                    })

                binding.playlistRecView.adapter = playlistAdapter
                binding.playlistScrollview.viewTreeObserver
                    .addOnScrollChangedListener {
                        if (binding.playlistScrollview.getChildAt(0).bottom
                            == (binding.playlistScrollview.height + binding.playlistScrollview.scrollY)
                        ) {
                            // scroll view is at bottom
                            if (nextPage != null && !isLoading) {
                                isLoading = true
                                fetchNextPage()
                            }
                        }
                    }

                /**
                 * listener for swiping to the left or right
                 */
                if (playlistType != PlaylistType.PUBLIC) {
                    val itemTouchCallback = object : ItemTouchHelper.SimpleCallback(
                        0,
                        ItemTouchHelper.LEFT
                    ) {
                        override fun onMove(
                            recyclerView: RecyclerView,
                            viewHolder: RecyclerView.ViewHolder,
                            target: RecyclerView.ViewHolder
                        ): Boolean {
                            return false
                        }

                        override fun onSwiped(
                            viewHolder: RecyclerView.ViewHolder,
                            direction: Int
                        ) {
                            val position = viewHolder.absoluteAdapterPosition
                            playlistAdapter!!.removeFromPlaylist(requireContext(), position)
                        }
                    }

                    val itemTouchHelper = ItemTouchHelper(itemTouchCallback)
                    itemTouchHelper.attachToRecyclerView(binding.playlistRecView)
                }

                // update the playlist thumbnail if bookmarked
                val playlistBookmark = awaitQuery {
                    DatabaseHolder.Database.playlistBookmarkDao().getAll()
                        .firstOrNull { it.playlistId == playlistId }
                }
                playlistBookmark?.let {
                    if (playlistBookmark.thumbnailUrl != response.thumbnailUrl) {
                        playlistBookmark.thumbnailUrl = response.thumbnailUrl
                        query {
                            DatabaseHolder.Database.playlistBookmarkDao().update(playlistBookmark)
                        }
                    }
                }
            }
        }
    }

    private fun fetchNextPage() {
        lifecycleScope.launchWhenCreated {
            val response = try {
                // load locally stored playlists with the auth api
                if (playlistType == PlaylistType.PRIVATE) {
                    RetrofitInstance.authApi.getPlaylistNextPage(
                        playlistId!!,
                        nextPage!!
                    )
                } else {
                    RetrofitInstance.api.getPlaylistNextPage(
                        playlistId!!,
                        nextPage!!
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                return@launchWhenCreated
            }
            nextPage = response.nextpage
            playlistAdapter?.updateItems(response.relatedStreams!!)
            isLoading = false
        }
    }
}
