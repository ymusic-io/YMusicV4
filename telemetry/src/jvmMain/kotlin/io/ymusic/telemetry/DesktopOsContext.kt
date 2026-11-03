package io.ymusic.telemetry

import io.sentry.Sentry
import io.sentry.protocol.Device
import io.sentry.protocol.OperatingSystem

/** sentry-java fills no OS or device context on desktop JVMs (only SentryAndroid does): set them, so
 *  events carry os.name / os.version / device.arch. No-op until Sentry.init ran. */
fun installSentryDesktopOsContext() {
    Sentry.configureScope { scope ->
        scope.contexts.setOperatingSystem(
            OperatingSystem().apply {
                name = System.getProperty("os.name")
                version = System.getProperty("os.version")
            },
        )
        scope.contexts.setDevice(Device().apply { archs = arrayOf(System.getProperty("os.arch")) })
    }
}
