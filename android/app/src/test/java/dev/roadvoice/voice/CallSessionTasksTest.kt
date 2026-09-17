package dev.roadvoice.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionTasksTest {
    @Test fun remoteHangUpCompletesEvenWhenTelecomStatusChannelsStayOpen() = runBlocking {
        val endpoints = Channel<String>(Channel.UNLIMITED)
        val mute = Channel<Boolean>(Channel.UNLIMITED)
        val ready = CompletableDeferred<CallSessionTasks>()
        val disconnected = CompletableDeferred<Unit>()
        val call = async {
            coroutineScope {
                val tasks = CallSessionTasks(this)
                tasks.launch { endpoints.receiveAsFlow().collect() }
                tasks.launch { mute.receiveAsFlow().collect() }
                ready.complete(tasks)
                disconnected.await()
                // Mirrors addCall's return after remote disconnect. Its coroutineScope still
                // waits for app children; it does not close the two platform status channels.
            }
        }
        ready.await().cancel()
        disconnected.complete(Unit)
        withTimeout(1_000) { call.await() }
        assertTrue(endpoints.trySend("still open").isSuccess)
        assertTrue(mute.trySend(false).isSuccess)
        endpoints.close()
        mute.close()
        Unit
    }

    @Test fun repeatedEndDoesNotPreventANewConversationFromRunning() = runBlocking {
        repeat(2) {
            val ready = CompletableDeferred<CallSessionTasks>()
            val audioStarted = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            val call = async {
                coroutineScope {
                    val tasks = CallSessionTasks(this)
                    tasks.launch { audioStarted.complete(Unit); CompletableDeferred<Unit>().await() }
                    ready.complete(tasks)
                    ended.await()
                }
            }
            withTimeout(1_000) { audioStarted.await() }
            val tasks = ready.await()
            tasks.cancel()
            tasks.cancel()
            ended.complete(Unit)
            withTimeout(1_000) { call.await() }
        }
    }
}
