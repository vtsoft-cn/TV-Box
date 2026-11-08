
package com.example.tvbox

import android.app.Application

class MainApplication : Application() {

    companion object {
        lateinit var channelRepository: ChannelRepository
    }

    override fun onCreate() {
        super.onCreate()
        channelRepository = ChannelRepository(this)
    }
}
