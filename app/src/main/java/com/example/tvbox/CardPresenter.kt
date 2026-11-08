package com.example.tvbox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders

class CardPresenter : Presenter() {

    var playingUrl: String? = null

    private val logoHeaders = LazyHeaders.Builder()
        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .build()

    private class ChannelHolder(row: View) : ViewHolder(row) {
        val title: TextView = row.findViewById(R.id.channel_title)
        val logo: ImageView = row.findViewById(R.id.channel_logo)
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder =
        ChannelHolder(LayoutInflater.from(parent.context).inflate(R.layout.channel_row, parent, false))

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val channel = item as Channel
        val holder = viewHolder as ChannelHolder
        val playing = channel.url == playingUrl
        holder.title.text = channel.title
        val titleColor = if (playing) R.color.tv_accent else R.color.tv_text
        holder.title.setTextColor(ContextCompat.getColor(holder.view.context, titleColor))
        ViewCompat.setStateDescription(
            holder.view,
            if (playing) holder.view.resources.getString(R.string.current_channel) else null
        )
        val logo = channel.imageUrl?.takeIf { it.isNotBlank() }?.let { GlideUrl(it, logoHeaders) }
        Glide.with(holder.view)
            .load(logo)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(holder.logo)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val holder = viewHolder as ChannelHolder
        Glide.with(holder.view).clear(holder.logo)
    }
}
