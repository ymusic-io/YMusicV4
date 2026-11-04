package io.ymusic.desktop.media

import io.ymusic.desktop.isLinuxDebInstall
import kmpcommon.log.Logger
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Linux backend: an MPRIS player on the session bus (`org.mpris.MediaPlayer2.ymusic`), which is
 * what GNOME's / KDE's Now Playing widget, the lock screen and media keys (via the desktop's
 * media-key daemon) talk to.
 *
 * Method calls arrive on the bus receive thread and read only the volatile [published] snapshot;
 * [update] hands state to a single outbox thread, which alone writes [published] and emits signals.
 */
internal class LinuxMprisSession(
    private val commands: MediaSessionCommands,
    private val log: Logger? = null,
) : DesktopMediaSession {

    private class Published(val state: MediaSessionState?, val atMs: Long, val trackId: String) {
        fun positionMsAt(nowMs: Long): Long {
            val state = state ?: return 0
            val elapsed = if (state.playing) ((nowMs - atMs) * state.speed).toLong() else 0
            val position = state.positionMs + elapsed
            return state.durationMs?.let { position.coerceAtMost(it) } ?: position
        }
    }

    private val bus = DBusConnection.openSession()
    private val outbox = Executors.newSingleThreadExecutor { Thread(it, "mpris-outbox").apply { isDaemon = true } }

    @Volatile
    private var published = Published(null, nowMs(), NO_TRACK)

    // Outbox thread only.
    private var trackCounter = 0
    private var emitted: Map<String, Variant> = playerProperties(published)

    init {
        try {
            val name = requestBusName()
            log?.log(Logger.Level.INFO, "MPRIS published as $name")
            bus.startReceiving(::onMethodCall) { cause ->
                if (cause != null) log?.log(Logger.Level.WARN, "MPRIS bus connection lost — media keys dead", cause)
            }
        } catch (error: Throwable) {
            bus.close()
            outbox.shutdown()
            throw error
        }
    }

    override fun update(state: MediaSessionState) {
        outbox.execute {
            try {
                publish(state)
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "MPRIS publish failed", error)
            }
        }
    }

    override fun release() {
        outbox.shutdown()
        bus.close()
    }

    private fun requestBusName(): String {
        val candidates = listOf(BUS_NAME, "$BUS_NAME.instance${ProcessHandle.current().pid()}")
        for (name in candidates) {
            val reply = bus.callBlocking(
                "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName",
                "su", listOf(name, NAME_FLAG_DO_NOT_QUEUE),
            ).body.single() as Long
            if (reply == NAME_PRIMARY_OWNER || reply == NAME_ALREADY_OWNER) return name
        }
        error("MPRIS bus names already taken: $candidates")
    }

    private fun publish(state: MediaSessionState) {
        val now = nowMs()
        val previous = published
        val previousState = previous.state
        val sameTrack = state.hasItem && previousState != null && previousState.hasItem &&
            previousState.title == state.title && previousState.artist == state.artist &&
            previousState.artworkUrl == state.artworkUrl
        val trackId = when {
            !state.hasItem -> NO_TRACK
            sameTrack -> previous.trackId
            else -> "$TRACK_PATH_PREFIX${++trackCounter}"
        }
        val expectedMs = previous.positionMsAt(now)
        val next = Published(state, now, trackId)
        published = next

        val properties = playerProperties(next)
        val changed = properties.filter { (key, value) -> key != "Position" && emitted[key] != value }
        emitted = properties
        if (changed.isNotEmpty()) {
            bus.signal(
                OBJECT_PATH, PROPERTIES_IFACE, "PropertiesChanged", "sa{sv}as",
                listOf(PLAYER_IFACE, changed, emptyList<String>()),
            )
        }
        if (sameTrack && abs(state.positionMs - expectedMs) > SEEK_TOLERANCE_MS) {
            bus.signal(OBJECT_PATH, PLAYER_IFACE, "Seeked", "x", listOf(state.positionMs * 1000))
        }
    }

    private fun playerProperties(snapshot: Published): Map<String, Variant> {
        val state = snapshot.state
        val hasItem = state?.hasItem == true
        val status = when {
            !hasItem -> "Stopped"
            state.playing -> "Playing"
            else -> "Paused"
        }
        val metadata = buildMap {
            put("mpris:trackid", Variant("o", ObjectPath(snapshot.trackId)))
            if (hasItem) {
                state.durationMs?.let { put("mpris:length", Variant("x", it * 1000)) }
                state.title?.let { put("xesam:title", Variant("s", it)) }
                state.artist?.let { put("xesam:artist", Variant("as", listOf(it))) }
                state.artworkUrl?.let { put("mpris:artUrl", Variant("s", it)) }
            }
        }
        val speed = ((state?.speed ?: 1f) * 100).roundToInt() / 100.0
        return linkedMapOf(
            "PlaybackStatus" to Variant("s", status),
            "Rate" to Variant("d", speed),
            "Metadata" to Variant("a{sv}", metadata),
            "Volume" to Variant("d", 1.0),
            "Position" to Variant("x", snapshot.positionMsAt(nowMs()) * 1000),
            "MinimumRate" to Variant("d", MIN_RATE),
            "MaximumRate" to Variant("d", MAX_RATE),
            "CanGoNext" to Variant("b", hasItem),
            "CanGoPrevious" to Variant("b", hasItem),
            "CanPlay" to Variant("b", hasItem),
            "CanPause" to Variant("b", hasItem),
            "CanSeek" to Variant("b", hasItem && state.durationMs != null),
            "CanControl" to Variant("b", true),
        )
    }

    private fun rootProperties(): Map<String, Variant> = linkedMapOf(
        "CanQuit" to Variant("b", false),
        "CanRaise" to Variant("b", false),
        "HasTrackList" to Variant("b", false),
        "Identity" to Variant("s", "YMusic"),
        "DesktopEntry" to Variant("s", if (isLinuxDebInstall) "ymusic-YMusic" else "io.ymusic.desktop"),
        "SupportedUriSchemes" to Variant("as", emptyList<String>()),
        "SupportedMimeTypes" to Variant("as", emptyList<String>()),
    )

    private fun propertiesOf(iface: String?): Map<String, Variant>? = when (iface) {
        ROOT_IFACE -> rootProperties()
        PLAYER_IFACE -> playerProperties(published)
        else -> null
    }

    // --- incoming calls (bus receive thread) ---

    private fun onMethodCall(call: DBusMessage) {
        try {
            dispatch(call)
        } catch (error: Throwable) {
            log?.log(Logger.Level.WARN, "MPRIS ${call.iface}.${call.member} failed", error)
            bus.replyError(call, "org.freedesktop.DBus.Error.Failed", error.message ?: error.javaClass.name)
        }
    }

    private fun dispatch(call: DBusMessage) {
        if (call.iface == PEER_IFACE || (call.iface == null && call.member in setOf("Ping", "GetMachineId"))) {
            when (call.member) {
                "Ping" -> bus.reply(call)
                "GetMachineId" -> bus.reply(call, "s", listOf(File("/etc/machine-id").readText().trim()))
                else -> unknownMethod(call)
            }
            return
        }
        if (call.path != OBJECT_PATH) {
            bus.replyError(call, "org.freedesktop.DBus.Error.UnknownObject", "No such object ${call.path}")
            return
        }
        when (call.member) {
            "Introspect" -> bus.reply(call, "s", listOf(INTROSPECTION_XML))
            "Get" -> {
                val (iface, name) = call.body
                val value = propertiesOf(iface as String)?.get(name as String)
                if (value == null) {
                    bus.replyError(call, "org.freedesktop.DBus.Error.UnknownProperty", "No property $iface.$name")
                } else {
                    bus.reply(call, "v", listOf(value))
                }
            }
            "GetAll" -> {
                val properties = propertiesOf(call.body.first() as String)
                if (properties == null) {
                    bus.replyError(call, "org.freedesktop.DBus.Error.UnknownInterface", "No interface ${call.body.first()}")
                } else {
                    bus.reply(call, "a{sv}", listOf(properties))
                }
            }
            "Set" -> bus.replyError(call, "org.freedesktop.DBus.Error.PropertyReadOnly", "Properties are read-only")
            "Raise", "Quit" -> bus.reply(call)
            "Play" -> reply(call) { commands.play() }
            "Pause", "Stop" -> reply(call) { commands.pause() }
            "PlayPause" -> reply(call) { commands.togglePlay() }
            "Next" -> reply(call) { commands.next() }
            "Previous" -> reply(call) { commands.previous() }
            "Seek" -> reply(call) {
                val snapshot = published
                val state = snapshot.state ?: return@reply
                val target = snapshot.positionMsAt(nowMs()) + (call.body.single() as Long) / 1000
                val duration = state.durationMs
                when {
                    duration != null && target >= duration -> commands.next()
                    else -> commands.seekTo(target.coerceAtLeast(0))
                }
            }
            "SetPosition" -> reply(call) {
                val (trackId, positionUs) = call.body
                val snapshot = published
                val duration = snapshot.state?.durationMs
                val positionMs = (positionUs as Long) / 1000
                val inRange = positionMs >= 0 && (duration == null || positionMs <= duration)
                if ((trackId as ObjectPath).path == snapshot.trackId && inRange) commands.seekTo(positionMs)
            }
            "OpenUri" -> bus.replyError(call, "org.freedesktop.DBus.Error.NotSupported", "OpenUri is not supported")
            else -> unknownMethod(call)
        }
    }

    private inline fun reply(call: DBusMessage, action: () -> Unit) {
        action()
        bus.reply(call)
    }

    private fun unknownMethod(call: DBusMessage) {
        bus.replyError(call, "org.freedesktop.DBus.Error.UnknownMethod", "No method ${call.iface}.${call.member}")
    }

    private companion object {
        const val BUS_NAME = "org.mpris.MediaPlayer2.ymusic"
        const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
        const val ROOT_IFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
        const val PROPERTIES_IFACE = "org.freedesktop.DBus.Properties"
        const val PEER_IFACE = "org.freedesktop.DBus.Peer"
        const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"
        const val TRACK_PATH_PREFIX = "/io/ymusic/track/"
        const val NAME_FLAG_DO_NOT_QUEUE = 4L
        const val NAME_PRIMARY_OWNER = 1L
        const val NAME_ALREADY_OWNER = 4L
        const val MIN_RATE = 0.25
        const val MAX_RATE = 4.0
        const val SEEK_TOLERANCE_MS = 1_500L

        fun nowMs(): Long = System.nanoTime() / 1_000_000

        val INTROSPECTION_XML = """
            |<!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
            | "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
            |<node>
            |  <interface name="org.freedesktop.DBus.Introspectable">
            |    <method name="Introspect"><arg name="xml" type="s" direction="out"/></method>
            |  </interface>
            |  <interface name="org.freedesktop.DBus.Peer">
            |    <method name="Ping"/>
            |    <method name="GetMachineId"><arg name="machine_uuid" type="s" direction="out"/></method>
            |  </interface>
            |  <interface name="org.freedesktop.DBus.Properties">
            |    <method name="Get">
            |      <arg name="interface" type="s" direction="in"/>
            |      <arg name="property" type="s" direction="in"/>
            |      <arg name="value" type="v" direction="out"/>
            |    </method>
            |    <method name="GetAll">
            |      <arg name="interface" type="s" direction="in"/>
            |      <arg name="properties" type="a{sv}" direction="out"/>
            |    </method>
            |    <method name="Set">
            |      <arg name="interface" type="s" direction="in"/>
            |      <arg name="property" type="s" direction="in"/>
            |      <arg name="value" type="v" direction="in"/>
            |    </method>
            |    <signal name="PropertiesChanged">
            |      <arg name="interface" type="s"/>
            |      <arg name="changed_properties" type="a{sv}"/>
            |      <arg name="invalidated_properties" type="as"/>
            |    </signal>
            |  </interface>
            |  <interface name="org.mpris.MediaPlayer2">
            |    <method name="Raise"/>
            |    <method name="Quit"/>
            |    <property name="CanQuit" type="b" access="read"/>
            |    <property name="CanRaise" type="b" access="read"/>
            |    <property name="HasTrackList" type="b" access="read"/>
            |    <property name="Identity" type="s" access="read"/>
            |    <property name="DesktopEntry" type="s" access="read"/>
            |    <property name="SupportedUriSchemes" type="as" access="read"/>
            |    <property name="SupportedMimeTypes" type="as" access="read"/>
            |  </interface>
            |  <interface name="org.mpris.MediaPlayer2.Player">
            |    <method name="Next"/>
            |    <method name="Previous"/>
            |    <method name="Pause"/>
            |    <method name="PlayPause"/>
            |    <method name="Stop"/>
            |    <method name="Play"/>
            |    <method name="Seek"><arg name="Offset" type="x" direction="in"/></method>
            |    <method name="SetPosition">
            |      <arg name="TrackId" type="o" direction="in"/>
            |      <arg name="Position" type="x" direction="in"/>
            |    </method>
            |    <method name="OpenUri"><arg name="Uri" type="s" direction="in"/></method>
            |    <signal name="Seeked"><arg name="Position" type="x"/></signal>
            |    <property name="PlaybackStatus" type="s" access="read"/>
            |    <property name="Rate" type="d" access="read"/>
            |    <property name="Metadata" type="a{sv}" access="read"/>
            |    <property name="Volume" type="d" access="read"/>
            |    <property name="Position" type="x" access="read">
            |      <annotation name="org.freedesktop.DBus.Property.EmitsChangedSignal" value="false"/>
            |    </property>
            |    <property name="MinimumRate" type="d" access="read"/>
            |    <property name="MaximumRate" type="d" access="read"/>
            |    <property name="CanGoNext" type="b" access="read"/>
            |    <property name="CanGoPrevious" type="b" access="read"/>
            |    <property name="CanPlay" type="b" access="read"/>
            |    <property name="CanPause" type="b" access="read"/>
            |    <property name="CanSeek" type="b" access="read"/>
            |    <property name="CanControl" type="b" access="read"/>
            |  </interface>
            |</node>
        """.trimMargin()
    }
}
