package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import com.github.libretube.R
import com.github.libretube.constants.PIPED_FRONTEND_URL
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.constants.YOUTUBE_FRONTEND_URL
import com.github.libretube.databinding.DialogShareBinding
import com.github.libretube.db.DatabaseHolder.Companion.Database
import com.github.libretube.enums.ShareObjectType
import com.github.libretube.extensions.awaitQuery
import com.github.libretube.obj.ShareData
import com.github.libretube.util.PreferenceHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ShareDialog(
    private val id: String,
    private val shareObjectType: ShareObjectType,
    private val shareData: ShareData
) : DialogFragment() {
    private var binding: DialogShareBinding? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        var shareOptions = arrayOf(
            getString(R.string.piped),
            getString(R.string.youtube)
        )
        val instanceUrl = getCustomInstanceFrontendUrl()
        val shareableTitle = getShareableTitle(shareData)
        // add instanceUrl option if custom instance frontend url available
        if (instanceUrl != "") shareOptions += getString(R.string.instance)

        if (shareObjectType == ShareObjectType.VIDEO) {
            setupTimeStampBinding()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(context?.getString(R.string.share))
            .setItems(
                shareOptions
            ) { _, which ->
                val host = when (which) {
                    0 -> PIPED_FRONTEND_URL
                    1 -> YOUTUBE_FRONTEND_URL
                    // only available for custom instances
                    else -> instanceUrl
                }
                val path = when (shareObjectType) {
                    ShareObjectType.VIDEO -> "/watch?v=$id"
                    ShareObjectType.PLAYLIST -> "/playlist?list=$id"
                    else -> "/channel/$id"
                }
                var url = "$host$path"

                if (shareObjectType == ShareObjectType.VIDEO && binding!!.timeCodeSwitch.isChecked) {
                    url += "&t=${binding!!.timeStamp.text}"
                }

                val intent = Intent()
                intent.apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, url)
                    putExtra(Intent.EXTRA_SUBJECT, shareableTitle)
                    type = "text/plain"
                }
                context?.startActivity(
                    Intent.createChooser(intent, context?.getString(R.string.shareTo))
                )
            }
            .setView(binding?.root)
            .show()
    }

    private fun setupTimeStampBinding() {
        binding = DialogShareBinding.inflate(layoutInflater)
        binding!!.timeCodeSwitch.isChecked = PreferenceHelper.getBoolean(
            PreferenceKeys.SHARE_WITH_TIME_CODE,
            true
        )
        binding!!.timeCodeSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding!!.timeStampLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding!!.timeStamp.setText((shareData.currentPosition ?: 0L).toString())
        if (binding!!.timeCodeSwitch.isChecked) binding!!.timeStampLayout.visibility = View.VISIBLE
    }

    // get the frontend url if it's a custom instance
    private fun getCustomInstanceFrontendUrl(): String {
        val instancePref = PreferenceHelper.getString(
            PreferenceKeys.FETCH_INSTANCE,
            PIPED_FRONTEND_URL
        )

        // get the api urls of the other custom instances
        val customInstances = awaitQuery {
            Database.customInstanceDao().getAll()
        }

        // return the custom instance frontend url if available
        customInstances.forEach { instance ->
            if (instance.apiUrl == instancePref) return instance.frontendUrl
        }
        return ""
    }
    private fun getShareableTitle(shareData: ShareData): String {
        shareData.apply {
            currentChannel?.let {
                return it
            }
            currentVideo?.let {
                return it
            }
            currentPlaylist?.let {
                return it
            }
        }
        return ""
    }
}
