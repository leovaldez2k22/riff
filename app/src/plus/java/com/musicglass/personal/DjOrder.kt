package com.musicglass.personal

import kotlin.math.abs
import kotlin.math.ln

/*
 * Feature 18 (Riff+, owner Q18): the songs after the one playing, ordered by tempo and key. Only songs whose tempo is
 * known move; a song not analysed and a song the user put to play next stay exactly where they are, and the others
 * fill the remaining places in the new order. Steady keeps each step's tempo change small, Build up goes from slow to
 * fast and Wind down from fast to slow; within that, a key that mixes well with the one before comes first. Ties keep
 * the queue's own order, so the same queue always gives the same order.
 */

internal enum class DjMode(val key: String) { Steady("steady"), BuildUp("build_up"), WindDown("wind_down") }

/** What is known of a song for ordering: its tempo (BPM) and key, either possibly unknown. */
internal data class DjFacts(val bpm: Double?, val key: MusicalKey?)

/** One song after the one playing: its entry, what is known of it, and whether it must stay in its place. */
internal data class DjSlot(val entry: String, val facts: DjFacts?, val pinned: Boolean = false) {
    val movable: Boolean get() = !pinned && facts?.bpm != null
}

/** A tempo folded into one octave, 85 to 170 BPM, so a song at 70 counts as 140 (half and double time mix). */
internal fun foldedBpm(bpm: Double): Double {
    var t = bpm
    while (t < 85.0) t *= 2.0
    while (t >= 170.0) t /= 2.0
    return t
}

/** How far apart two tempos are, in steps of 4 % (about what a DJ can still blend without stretching). */
internal fun tempoSteps(a: Double, b: Double): Double = abs(ln(foldedBpm(a) / foldedBpm(b))) / ln(1.04)

/** How well two songs follow each other: tempo steps plus key steps; an unknown key counts as one and a half. */
private fun cost(from: DjFacts, to: DjFacts): Double {
    val tempo = if (from.bpm != null && to.bpm != null) tempoSteps(from.bpm, to.bpm) else 0.0
    val key = if (from.key != null && to.key != null) from.key.distance(to.key).toDouble() else 1.5
    return tempo + key
}

/**
 * The entries of [upcoming] in their new order (same length, same entries). [current] is the song playing, when its
 * tempo or key is known: Steady starts from it.
 */
internal fun djOrder(upcoming: List<DjSlot>, current: DjFacts?, mode: DjMode): List<String> {
    val moving = upcoming.withIndex().filter { it.value.movable }
    val ordered: List<DjSlot> = when (mode) {
        DjMode.Steady -> greedy(moving, current)
        DjMode.BuildUp, DjMode.WindDown -> {
            val up = mode == DjMode.BuildUp
            // By tempo first; songs within one 4 % step of each other form a group, ordered by key from the one before.
            val byTempo = moving.sortedWith(compareBy<IndexedValue<DjSlot>> { foldedBpm(it.value.facts!!.bpm!!) * if (up) 1 else -1 }.thenBy { it.index })
            val groups = mutableListOf<MutableList<IndexedValue<DjSlot>>>()
            for (slot in byTempo) {
                val group = groups.lastOrNull()
                if (group != null && tempoSteps(group.first().value.facts!!.bpm!!, slot.value.facts!!.bpm!!) <= 1.0) group += slot
                else groups += mutableListOf(slot)
            }
            val out = mutableListOf<DjSlot>()
            // The first group starts with its slowest (or fastest) song; each later one from the song before it.
            for (group in groups) out += greedy(group, out.lastOrNull()?.facts)
            out
        }
    }
    val queue = ArrayDeque(ordered)
    return upcoming.map { slot -> if (slot.movable) queue.removeFirst().entry else slot.entry }
}

/** Each next song the one that follows the last best; ties by the queue's own order. */
private fun greedy(slots: List<IndexedValue<DjSlot>>, start: DjFacts?): List<DjSlot> {
    val left = slots.toMutableList()
    val out = mutableListOf<DjSlot>()
    var last = start
    while (left.isNotEmpty()) {
        val next = if (last == null) left.first()
            else left.minWith(compareBy<IndexedValue<DjSlot>> { cost(last!!, it.value.facts!!) }.thenBy { it.index })
        left.remove(next); out += next.value; last = next.value.facts
    }
    return out
}

/** How much of the queue after the song playing the order can use. */
internal data class DjCoverage(val upcoming: Int, val withTempo: Int, val withKey: Int, val pinned: Int)

internal fun djCoverage(upcoming: List<DjSlot>) = DjCoverage(upcoming.size, upcoming.count { it.facts?.bpm != null },
    upcoming.count { it.facts?.key != null }, upcoming.count { it.pinned })

/**
 * Undo: the queue [now] (playing [current]) with the songs still to come back in their [previous] order. Songs played
 * since stay where they are; a [pinned] song keeps its place; a song added since goes after the ones put back.
 */
internal fun djUndoOrder(now: List<String>, current: Int, previous: List<String>, pinned: Set<String>): List<String> {
    if (current !in now.indices) return now
    val head = now.subList(0, current + 1)
    val upcoming = now.drop(current + 1)
    val loose = upcoming.filterNot { it in pinned }.toSet()
    val back = ArrayDeque(previous.filter { it in loose } + upcoming.filter { it in loose && it !in previous })
    return head + upcoming.map { if (it in pinned) it else back.removeFirst() }
}
