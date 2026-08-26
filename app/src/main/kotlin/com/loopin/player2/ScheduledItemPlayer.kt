package com.loopin.player2

import com.loopin.player2.core.model.DynamicContentType
import com.loopin.player2.core.model.PlaylistItem
import com.loopin.player2.core.playback.CurrentItemPlayer

class ScheduledItemPlayer(
    private val normal: CurrentItemPlayer,
    private val weather: CurrentItemPlayer,
) : CurrentItemPlayer {
    private var active: CurrentItemPlayer? = null
    override fun play(item: PlaylistItem, callback: CurrentItemPlayer.Callback) {
        active = if (item.dynamic?.type == DynamicContentType.WEATHER) weather else normal
        active!!.play(item, callback)
    }
    override fun pause() = active?.pause() ?: Unit
    override fun resume() = active?.resume() ?: Unit
    override fun stopAndReleaseCurrent() { active?.stopAndReleaseCurrent(); active = null }
    override fun release() { normal.release(); weather.release(); active = null }
}
