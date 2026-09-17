package dev.roadvoice.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** App work must end even if Telecom leaves its endpoint/mute channels open after hang-up. */
internal class CallSessionTasks(parent: CoroutineScope) {
    private val job = Job(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)

    fun launch(block: suspend CoroutineScope.() -> Unit) { scope.launch(block = block) }

    fun cancel() { job.cancel() }
}
