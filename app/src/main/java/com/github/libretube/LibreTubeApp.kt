package com.github.libretube

import android.app.Application
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import com.github.libretube.api.CronetHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.BACKGROUND_CHANNEL_ID
import com.github.libretube.constants.DOWNLOAD_CHANNEL_ID
import com.github.libretube.constants.PUSH_CHANNEL_ID
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.util.ExceptionHandler
import com.github.libretube.util.ImageHelper
import com.github.libretube.util.NotificationHelper
import com.github.libretube.util.PreferenceHelper

class LibreTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()

        /**
         * Initialize the needed notification channels for DownloadService and BackgroundMode
         */
        initializeNotificationChannels()

        /**
         * Initialize the [PreferenceHelper]
         */
        PreferenceHelper.initialize(applicationContext)

        /**
         * Initialize the [DatabaseHolder]
         */
        DatabaseHolder.initializeDatabase(this)

        /**
         * Bypassing fileUriExposedException, see https://stackoverflow.com/questions/38200282/android-os-fileuriexposedexception-file-storage-emulated-0-test-txt-exposed
         */
        val builder = VmPolicy.Builder()
        StrictMode.setVmPolicy(builder.build())

        /**
         * Set the api and the auth api url
         */
        RetrofitInstance.initialize()
        CronetHelper.initCronet(this)
        ImageHelper.initializeImageLoader(this)

        /**
         * Initialize the notification listener in the background
         */
        NotificationHelper(this).enqueueWork(
            existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
        )

        /**
         * Handler for uncaught exceptions
         */
        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        val exceptionHandler = ExceptionHandler(defaultExceptionHandler)
        Thread.setDefaultUncaughtExceptionHandler(exceptionHandler)
    }

    /**
     * Initializes the required notification channels for the app.
     */
    private fun initializeNotificationChannels() {
        val downloadChannel = NotificationChannelCompat.Builder(DOWNLOAD_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_NONE)
            .setName(getString(R.string.download_channel_name))
            .setDescription(getString(R.string.download_channel_description))
            .build()
        val backgroundChannel = NotificationChannelCompat.Builder(BACKGROUND_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.background_channel_name))
            .setDescription(getString(R.string.background_channel_description))
            .build()
        val pushChannel = NotificationChannelCompat.Builder(PUSH_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(getString(R.string.push_channel_name))
            .setDescription(getString(R.string.push_channel_description))
            .build()

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.createNotificationChannelsCompat(listOf(downloadChannel,
            backgroundChannel, pushChannel))
    }
}
