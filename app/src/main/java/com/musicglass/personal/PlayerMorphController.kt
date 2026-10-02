package com.musicglass.personal

import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One actor owns the player position. Drag input can interrupt an in-flight settle. */
internal class PlayerMorphController {
    val position = Animatable(0f)
    private sealed interface Command {
        data class Drag(val progress: Float) : Command
        data class Settle(val open: Boolean, val policy: MotionPolicy,
            val direct: Boolean, val fromDrag: Float?, val releaseVelocity: Float?) : Command
    }
    private val commands = Channel<Command>(Channel.CONFLATED)

    fun dragTo(progress: Float) { commands.trySend(Command.Drag(progress.coerceIn(0f, 1f))) }
    fun settle(open: Boolean, policy: MotionPolicy, direct: Boolean = false,
        fromDrag: Float? = null, releaseVelocity: Float? = null) {
        commands.trySend(Command.Settle(open, policy, direct, fromDrag, releaseVelocity))
    }

    suspend fun run() = coroutineScope {
        position.updateBounds(0f, 1f)
        var settling: Job? = null
        for (command in commands) {
            val interruptedVelocity = position.velocity
            settling?.cancelAndJoin()
            settling = null
            when (command) {
                is Command.Drag -> position.snapTo(command.progress)
                is Command.Settle -> settling = launch {
                    command.fromDrag?.let { position.snapTo(it.coerceIn(0f, 1f)) }
                    val target = if (command.open) 1f else 0f
                    if (command.policy.reduced) position.snapTo(target)
                    else if (command.direct) position.animateTo(target,
                        GlassMotion.settle(command.policy),
                        initialVelocity = command.releaseVelocity ?: interruptedVelocity)
                    else position.animateTo(target,
                        GlassMotion.timed(GlassMotion.playerMs, command.policy))
                }
            }
        }
    }
}
