package io.ymusic.desktop

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import kmpcommon.log.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.Closeable
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
    Quit("--player=quit"),
    ;

    companion object {
        fun of(args: List<String>): InstanceCommand = entries.firstOrNull { it.arg != null && it.arg in args } ?: Show
    }
}

/** How a launch's [SingleInstance.claim] ended. */
internal sealed interface InstanceClaim {
    /** This process is the app instance. */
    class Owner(val instance: SingleInstance) : InstanceClaim

    /** The command went to the running instance; this process has nothing left to do. */
    data object HandedOver : InstanceClaim
}

/** Another process holds the instance claim but did not take the handed-over command. */
internal class InstanceHandoffException(cause: IOException?) : IOException("no running instance answered", cause)

/**
 * One app process per data dir: the first launch holds `instance.lock` and listens on a loopback
 * port; a later launch hands its [InstanceCommand] over and exits. The port file carries a random
 * token so only a process that can read the data dir is listened to.
 */
internal class SingleInstance private constructor(
    private val lock: FileLock,
    private val server: ServerSocket,
    private val token: String,
    private val logger: Logger,
) {
    private val received = Channel<InstanceCommand>(Channel.UNLIMITED)

    /** Commands handed over by later launches; held until collected, so a boot-time task is not lost. */
    val commands: Flow<InstanceCommand> = received.receiveAsFlow()

    private fun serve() {
        Thread({
            while (true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = HANDOFF_TIMEOUT_MS
                        val lines = socket.getInputStream().bufferedReader().readLines()
                        if (lines.firstOrNull() != token) return@use
                        val command = InstanceCommand.entries.firstOrNull { it.name == lines.getOrNull(1) } ?: return@use
                        received.trySend(command)
                    }
                } catch (e: IOException) {
                    if (server.isClosed) break
                    logger.log(Logger.Level.WARN, "instance handoff failed", e)
                }
            }
        }, "single-instance").apply { isDaemon = true }.start()
    }

    /** Gives the claim up, so a later launch becomes the instance instead of handing over to this one. */
    fun release() {
        // Listener first: a launch that finds nobody listening retries and claims the freed lock.
        try {
            server.close()
        } finally {
            lock.channel().close()
        }
    }

    companion object {
        /**
         * Claims this process as the app instance, or hands [command] to the running one.
         *
         * @throws InstanceHandoffException another process holds the claim but never took [command].
         * @throws IOException this process could not set the claim up (lock file, socket, port file).
         */
        fun claim(dir: File, command: InstanceCommand, logger: Logger): InstanceClaim {
            dir.mkdirs()
            val lockFile = File(dir, "instance.lock").toPath()
            val portFile = File(dir, "instance.port")
            var last: IOException? = null
            // The running instance may still be starting (no port file yet) or quitting (its lock about to free up).
            repeat(HANDOFF_ATTEMPTS) { attempt ->
                if (attempt > 0) Thread.sleep(HANDOFF_RETRY_MS)
                val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val lock = try {
                    channel.tryLock()
                } catch (e: IOException) {
                    channel.closeAfter(e)
                }
                if (lock != null) return InstanceClaim.Owner(listen(lock, portFile, logger))
                channel.close()
                if (attempt == 0 && command == InstanceCommand.Show) shareForeground(logger)
                try {
                    handOff(portFile, command)
                    return InstanceClaim.HandedOver
                } catch (e: IOException) {
                    last = e
                } catch (e: RuntimeException) { // a half-written port file
                    last = IOException("bad instance.port", e)
                }
            }
            throw InstanceHandoffException(last)
        }

        private fun listen(lock: FileLock, portFile: File, logger: Logger): SingleInstance {
            try {
                val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
                try {
                    val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                    portFile.writeText("${server.localPort} $token")
                    return SingleInstance(lock, server, token, logger).also { it.serve() }
                } catch (e: IOException) {
                    server.closeAfter(e)
                }
            } catch (e: IOException) {
                lock.channel().closeAfter(e)
            }
        }

        /** Closes this after [failure] (a close error rides along as suppressed) and rethrows [failure]. */
        private fun Closeable.closeAfter(failure: IOException): Nothing {
            try {
                close()
            } catch (e: IOException) {
                failure.addSuppressed(e)
            }
            throw failure
        }

        /** Windows lets only the process the user just launched take the foreground: pass that on to the running app. */
        private fun shareForeground(logger: Logger) {
            try {
                val allowed = NativeLibrary.getInstance("user32")
                    .getFunction("AllowSetForegroundWindow", Function.ALT_CONVENTION)
                    .invokeInt(arrayOf(ASFW_ANY))
                if (allowed == 0) logger.log(Logger.Level.INFO, "AllowSetForegroundWindow refused")
            } catch (e: LinkageError) {
                logger.log(Logger.Level.WARN, "AllowSetForegroundWindow unavailable", e)
            }
        }

        private fun handOff(portFile: File, command: InstanceCommand) {
            val (port, token) = portFile.readText().trim().split(' ', limit = 2)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port.toInt()), HANDOFF_TIMEOUT_MS)
                socket.getOutputStream().write("$token\n${command.name}\n".toByteArray())
            }
        }

        private const val ASFW_ANY = -1
        private const val HANDOFF_ATTEMPTS = 20
        private const val HANDOFF_RETRY_MS = 150L
        private const val HANDOFF_TIMEOUT_MS = 2_000
    }
}
