package fr.jd.audiobooks

import androidx.media3.common.Player

/** Durée du saut avant/arrière (réglable dans le menu, mémorisée). Partagée par l'écran, la notification et Android Auto. */
object Skip {
    val choices = listOf(10, 15, 30, 45, 60)
    var seconds = 30
    val ms: Long get() = seconds * 1000L
    fun load(store: Store) { seconds = store.skipSeconds().takeIf { it in choices } ?: 30 }
}

fun Player.skipBack() { seekTo((currentPosition - Skip.ms).coerceAtLeast(0)) }

fun Player.skipForward() {
    val d = duration
    val t = currentPosition + Skip.ms
    seekTo(if (d > 0) minOf(t, d) else t)
}
