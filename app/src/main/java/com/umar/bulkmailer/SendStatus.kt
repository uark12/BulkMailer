package com.umar.bulkmailer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Shared, in-memory progress between the sending service and the screen. */
object SendStatus {
    data class State(
        val running: Boolean = false,
        val total: Int = 0,
        val sent: Int = 0,
        val failed: Int = 0,
        val log: List<String> = emptyList(),
        val failedRecipients: List<Recipient> = emptyList(),
        val waitingUntil: Long = 0L
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile var stopRequested = false
    @Volatile var pendingJob: MailJob? = null

    fun update(f: (State) -> State) = _state.update(f)

    fun log(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(500)) }
}
