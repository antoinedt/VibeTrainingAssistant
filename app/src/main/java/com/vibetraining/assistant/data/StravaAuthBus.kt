package com.vibetraining.assistant.data

import kotlinx.coroutines.channels.Channel

/**
 * Bridges the OAuth redirect (delivered to MainActivity via a deep link) to the
 * sync coroutine waiting for the authorization code. CONFLATED so a code that
 * arrives a moment before the collector starts receiving isn't lost. An empty
 * string signals the user denied/cancelled authorization.
 */
object StravaAuthBus {
    val codes = Channel<String>(Channel.CONFLATED)
}
