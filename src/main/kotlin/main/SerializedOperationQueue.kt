package main

import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newSingleThreadContext

class SerializedOperationQueue(name: String = "EventLoop", capacity: Int = 0) {
    private val singleThreadContext = newSingleThreadContext(name)
    private val actor = actor<suspend () -> Unit>(singleThreadContext, capacity) {
        for (operation in channel) {
            operation.invoke()
        }
    }

    fun push(operation: suspend () -> Unit) = launch(Unconfined) {
        actor.send(operation)
    }
}