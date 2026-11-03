package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.Sentry

private const val DEVICE_ID_TAG = "device_id"

/** Attach the anonymous stable device id to every event this process sends (crash grouping across
 *  sessions, "how many devices" triage). No-op until Sentry.init ran. */
fun installSentryDeviceId(id: String) {
    Sentry.configureScope { scope ->
        scope.setContext("DEVICE_ID", id)
        // A tag too: contexts aren't searchable, and a user quotes this id on GitHub or by email.
        scope.setTag(DEVICE_ID_TAG, id)
    }
}

/** Attach the RUNNING engine-js bundle's self-declared revision to every event — set at start and
 *  again on every remote swap (the manager's onBackendVersion callback), so a report always names
 *  the bundle that was live. A tag, not a context: tags are indexed (searchable, and issues show
 *  their value distribution), a context is display-only. */
fun installSentryEngineVersion(version: Int) {
    Sentry.configureScope { scope -> scope.setTag("engine.version", version.toString()) }
}

/** The player.js revision of the most recent player session, as the `player.version` tag — set each
 *  time a session's bundle passes its health check, so playback reports name the player that ran. */
fun installSentryPlayerVersion(version: Int) {
    Sentry.configureScope { scope -> scope.setTag("player.version", version.toString()) }
}
