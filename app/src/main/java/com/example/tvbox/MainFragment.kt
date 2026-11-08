package com.example.tvbox

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.VerticalGridPresenter
import androidx.leanback.widget.VerticalGridView

class MainFragment : VerticalGridSupportFragment() {

    private lateinit var channelsAdapter: ArrayObjectAdapter
    private val cardPresenter = CardPresenter()
    private var playingIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_XSMALL, false).apply {
            numberOfColumns = 1
            setShadowEnabled(false)
        }
        channelsAdapter = ArrayObjectAdapter(cardPresenter)
        adapter = channelsAdapter
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid).apply {
            layoutParams = layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
            setColumnWidth(0)
            val inset = resources.getDimensionPixelSize(R.dimen.channel_focus_inset)
            setPadding(inset, inset, inset, inset)
            clipToPadding = false
            setVerticalSpacing(resources.getDimensionPixelSize(R.dimen.channel_spacing))
            windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
            itemAnimator = null
        }
        setOnItemViewClickedListener { _, item, _, _ ->
            val screen = activity as? MainActivity ?: return@setOnItemViewClickedListener
            if (item is Channel) playChannel(screen, channelsAdapter.indexOf(item))
        }
    }

    override fun onResume() {
        super.onResume()
        if (channelsAdapter.size() == 0) loadChannels()
    }

    private fun loadChannels() {
        val screen = activity as? MainActivity ?: return
        val repository = MainApplication.channelRepository
        val channels = repository.channels
        screen.showChannelsState(channels.size)
        if (channels.isEmpty()) return

        val lastUrl = repository.getLastPlayedUrl()
        val lastTitle = repository.getLastPlayedTitle()
        val previousUrlIndex = channels.indexOfFirst { it.url == lastUrl }
        val index = if (previousUrlIndex >= 0) previousUrlIndex
            else channels.indexOfFirst { it.title == lastTitle }.coerceAtLeast(0)
        cardPresenter.playingUrl = channels[index].url
        channelsAdapter.addAll(0, channels)
        playingIndex = index
        view?.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid)?.setSelectedPosition(index)
        screen.playChannel(channels[index])
        screen.focusInitialChannel()
    }

    internal fun focusPlayingChannel(): Boolean {
        if (playingIndex < 0) return false
        val grid = view?.findViewById<VerticalGridView>(androidx.leanback.R.id.browse_grid) ?: return false
        grid.setSelectedPosition(playingIndex) { holder -> holder.itemView.requestFocus() }
        return true
    }

    private fun playChannel(screen: MainActivity, index: Int) {
        if (index < 0) return
        val channel = channelsAdapter[index] as Channel
        if (index != playingIndex) {
            val previous = playingIndex
            playingIndex = index
            cardPresenter.playingUrl = channel.url
            if (previous >= 0) channelsAdapter.notifyArrayItemRangeChanged(previous, 1)
            channelsAdapter.notifyArrayItemRangeChanged(index, 1)
        }
        screen.playChannel(channel)
    }
}
