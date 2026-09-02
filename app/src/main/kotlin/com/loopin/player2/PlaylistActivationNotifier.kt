package com.loopin.player2

import com.loopin.player2.core.model.Playlist
import java.util.concurrent.CopyOnWriteArrayList

class PlaylistActivationNotifier {
    private val listeners = CopyOnWriteArrayList<(Playlist) -> Unit>()
    fun publish(playlist: Playlist) = listeners.forEach { listener -> runCatching { listener(playlist) } }
    fun subscribe(listener: (Playlist) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}
