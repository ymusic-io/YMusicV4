package io.ymusic.desktop

import kmpcommon.log.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

internal val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** What a launch asks the app to do: bring the window back, or a Jump List player task. */
internal enum class InstanceCommand(val arg: String?) {
    Show(null),
    TogglePlay("--player=toggle"),
    Stop("--player=stop"),
    Exit("--player=exit"),
    ;

    companion object {
        fun of(args: List<String>): InstanceCommand = entries.firstOrNull { it.arg != null && it.arg in args } ?: Show
    }
}

/**
 * One app process per data dir: the first launch holds `instance.lock` and listens on a loopback
 * port; a later launch hands its [InstanceCommand] over and exits. The port file carries a random
 * token so only a process that can read the data dir is listened to.
 */
internal class SingleInstance private constructor(
    @Suppress("unused") private val lock: FileLock, // held for the process lifetime
    private val server: ServerSocket,
    private val token: String,
    private val logger: Logger,
) {
    private val _commands = MutableSharedFlow<InstanceCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<InstanceCommand> = _commands

    private fun serve() {
        Thread({
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = HANDOFF_TIMEOUT_MS
                        val lines = socket.getInputStream().bufferedReader().readLines()
                        if (lines.firstOrNull() != token) return@use
                        val command = InstanceCommand.entries.firstOrNull { it.name == lines.getOrNull(1) } ?: return@use
                        _commands.tryEmit(command)
                    }
                } catch (e: IOException) {
                    logger.log(Logger.Level.WARN, "instance handoff failed", e)
                }
            }
        }, "single-instance").apply { isDaemon = true }.start()
    }

    companion object {
        /**
         * Claims this process as the app instance, or hands [command] to the running one and
         * returns null (the caller exits).
         */
        fun claim(dir: File, command: InstanceCommand, logger: Logger): SingleInstance? {
            dir.mkdirs()
            val portFile = File(dir, "instance.port")
            val channel = FileChannel.open(
                File(dir, "instance.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = channel.tryLock()
            if (lock == null) {
                channel.close()
                handOff(portFile, command)
                return null
            }
            val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            portFile.writeText("${server.localPort} $token")
            return SingleInstance(lock, server, token, logger).also { it.serve() }
        }

        // The running instance may still be starting (port file not written yet): retry briefly.
        private fun handOff(portFile: File, command: InstanceCommand) {
            var last: IOException? = null
            repeat(HANDOFF_ATTEMPTS) {
                try {
                    val (port, token) = portFile.readText().trim().split(' ', limit = 2)
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port.toInt()), HANDOFF_TIMEOUT_MS)
                        socket.getOutputStream().write("$token\n${command.name}\n".toByteArray())
                    }
                    return
                } catch (e: IOException) {
                    last = e
                } catch (e: RuntimeException) { // a half-written port file
                    last = IOException("bad instance.port", e)
                }
                Thread.sleep(HANDOFF_RETRY_MS)
            }
            throw IOException("no running instance answered", last)
        }

        private const val HANDOFF_ATTEMPTS = 20
        private const val HANDOFF_RETRY_MS = 150L
        private const val HANDOFF_TIMEOUT_MS = 2_000
    }
}
