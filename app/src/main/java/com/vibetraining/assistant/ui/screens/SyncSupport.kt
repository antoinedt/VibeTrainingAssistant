package com.vibetraining.assistant.ui.screens

import android.content.Intent
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException

/** UI state for the manual Strava sync flow, shared by the screens that host it. */
sealed class SyncState {
    object Idle : SyncState()
    data class Loading(val step: String) : SyncState()
    data class Success(val message: String) : SyncState()
    data class Error(val message: String) : SyncState()
}

/** Drive access can fail with a recoverable consent error whose recovery intent,
 *  when launched, shows the user Google's permission screen. Returns that intent
 *  if the error (or any cause) is one, else null. */
fun recoverableConsentIntent(e: Throwable?): Intent? =
    generateSequence(e) { it.cause }
        .mapNotNull { (it as? UserRecoverableAuthIOException)?.intent }
        .firstOrNull()

/** Renders a throwable into something actionable even when its message is null
 *  (e.g. SocketTimeoutException), walking up to three causes so the underlying
 *  reason — a timeout, an auth problem, a missing file — is never swallowed. */
fun describe(e: Throwable?): String {
    if (e == null) return "unknown error"
    return generateSequence(e) { it.cause }
        .take(3)
        .map { t -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName }
        .distinct()
        .joinToString(" ← ")
}
