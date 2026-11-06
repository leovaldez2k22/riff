package com.musicglass.personal

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.random.Random

/** The selected entry first, then every other one in a new order. */
internal fun <T> shuffledAround(items: List<T>, selected: Int, random: Random = Random.Default): List<T> =
    listOf(items[selected]) + items.filterIndexed { index, _ -> index != selected }.shuffled(random)

/**
 * The order to go back to when shuffle is turned off: [original], less what has left the queue, with whatever joined
 * it since straight after the selected entry.
 */
internal fun <T> unshuffledOrder(now: List<T>, original: List<T>, selected: T): List<T> {
    val kept = original.filter { it in now }
    val joined = now.filter { it !in original }
    val at = kept.indexOf(selected)
    return if (at < 0) now else kept.take(at + 1) + joined + kept.drop(at + 1)
}

/**
 * Shuffle changes the queue itself, once (owner, 5 Nov): the list is the play order, so the player no longer picks
 * another song after each one. The player's shuffle flag stays on as what every button shows, over an order that
 * follows the list (see the service). Main thread.
 */
internal class QueueOrder(private val random: Random = Random.Default) {
    /** The flag this class has dealt with; a change to any other value came from a button somewhere. */
    private var handled = false
    private var original: List<String>? = null
    private val added = mutableSetOf<String>()

    /** A new queue: nothing to go back to, and nothing waiting to play next. */
    fun forget() { original = null; added.clear() }

    /** A queue arriving already in its shuffled or plain order. [unshuffled] is its order before shuffling, if known. */
    fun loaded(shuffled: Boolean, unshuffled: List<String>? = null) { handled = shuffled; original = unshuffled; added.clear() }

    /** Where a song the user adds goes: after the current one and after what they added before it. */
    fun nextSlot(player: Player): Int {
        var slot = player.currentMediaItemIndex + 1
        while (slot < player.mediaItemCount && player.getMediaItemAt(slot).entryId() in added) slot++
        return slot
    }

    fun userAdded(item: MediaItem) { added += item.entryId() }

    /** The player's flag changed, from the app, the lock screen, a widget or a car. */
    fun shuffleChanged(player: Player, on: Boolean) {
        if (on == handled) return
        handled = on
        if (on) shuffle(player) else restore(player)
    }

    /** The queue's reshuffle button: the whole queue again, around the song playing. */
    fun reshuffle(player: Player) {
        if (!handled) { handled = true; player.shuffleModeEnabled = true }
        shuffle(player)
    }

    private fun items(player: Player) = (0 until player.mediaItemCount).map(player::getMediaItemAt)

    private fun shuffle(player: Player) {
        if (player.mediaItemCount < 2) return
        val now = items(player)
        if (original == null) original = now.map { it.entryId() }
        added.clear()
        rearrange(player, shuffledAround(now, player.currentMediaItemIndex, random))
    }

    private fun restore(player: Player) {
        val order = original
        original = null
        added.clear()
        val selected = player.currentMediaItem?.entryId()
        val byId = items(player).associateBy { it.entryId() }
        // Every entry has its own id, or nothing can be put back safely.
        if (order == null || selected == null || byId.size != player.mediaItemCount || "" in byId) return
        rearrange(player, unshuffledOrder(byId.keys.toList(), order, selected).map(byId::getValue))
    }

    /** Everything but the song playing leaves and comes back in place, so that song is never interrupted. */
    private fun rearrange(player: Player, target: List<MediaItem>) {
        val selected = player.currentMediaItemIndex
        val at = target.indexOfFirst { it.entryId() == player.currentMediaItem?.entryId() }
        if (at < 0) return
        if (selected + 1 < player.mediaItemCount) player.removeMediaItems(selected + 1, player.mediaItemCount)
        if (selected > 0) player.removeMediaItems(0, selected)
        if (at > 0) player.addMediaItems(0, target.subList(0, at))
        if (at + 1 < target.size) player.addMediaItems(player.mediaItemCount, target.subList(at + 1, target.size))
    }
}
