package io.ymusic.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ymusic.desktop.db.AppDatabase
import kmpcommon.log.Logger
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * The desktop's small durable store, `app.db`: settings, and [secret]s — the sealed cookie jar and
 * the OS key material it is sealed with. SQLite commits are atomic and synced (`synchronous=FULL`),
 * so a crash or power loss leaves the previous value, never an empty or torn one.
 *
 * Blocking: call it off the main thread.
 */
internal class AppStore private constructor(private val database: AppDatabase) {
    private val settingQueries = database.settingQueries
    private val secretQueries = database.secretQueries

    fun settings(): Map<String, String> = settingQueries.selectAll().executeAsList().associate { it.key to it.value_ }

    fun putSetting(key: String, value: String) {
        settingQueries.put(key, value)
    }

    fun removeSetting(key: String) {
        settingQueries.remove(key)
    }

    fun secret(name: String): ByteArray? = secretQueries.select(name).executeAsOneOrNull()

    fun putSecret(name: String, value: ByteArray) {
        secretQueries.put(name, value)
    }

    /** [value] unless [name] already holds one — then that one. Two first launches agree on one key. */
    fun secretOrPut(name: String, value: ByteArray): ByteArray {
        secretQueries.putIfAbsent(name, value)
        return checkNotNull(secret(name))
    }

    fun deleteSecret(name: String) {
        secretQueries.delete(name)
    }

    /** One-time import of what 4.0.6 and earlier kept as loose files in [dataDir]; each file goes
     *  once its rows commit. Rows already here win: a crash between commit and delete re-imports. */
    private fun importLegacyFiles(dataDir: File, log: Logger?) {
        val settingsFile = File(dataDir, LEGACY_SETTINGS)
        val secretFiles = LEGACY_SECRETS.map { File(dataDir, it) }
        if (!settingsFile.exists() && secretFiles.none { it.exists() }) return
        val settings = if (!settingsFile.exists()) emptyMap() else try {
            Properties().apply { settingsFile.inputStream().use(::load) }.entries.associate { (key, value) -> key.toString() to value.toString() }
        } catch (error: Exception) {
            log?.log(Logger.Level.WARN, "legacy $LEGACY_SETTINGS unreadable, starting on defaults", error)
            emptyMap()
        }
        database.transaction {
            for ((key, value) in settings) settingQueries.putIfAbsent(key, value)
            // An empty file is what a power loss left behind: nothing to keep.
            for (file in secretFiles) {
                if (file.length() > 0) secretOrPut(file.name, file.readBytes())
            }
        }
        (listOf(settingsFile) + secretFiles + LEGACY_LEFTOVERS.map { File(dataDir, it) }).forEach { it.delete() }
        log?.log(Logger.Level.INFO, "imported legacy settings and cookie files into $DB_FILE")
    }

    companion object {
        private const val DB_FILE = "app.db"
        private const val LEGACY_SETTINGS = "settings.properties"

        /** Each imported as the secret of its own name. */
        private val LEGACY_SECRETS = listOf(COOKIE_JAR, DPAPI_KEY_SECRET, FALLBACK_KEY_SECRET)
        private val LEGACY_LEFTOVERS = listOf("$COOKIE_JAR.tmp", "$COOKIE_JAR.reseal", "$DPAPI_KEY_SECRET.tmp")

        fun open(dataDir: File, log: Logger): AppStore {
            val properties = Properties().apply {
                setProperty("journal_mode", "WAL")
                setProperty("synchronous", "FULL")
                setProperty("busy_timeout", "5000")
            }
            val database = openDatabase(File(dataDir.apply { mkdirs() }, DB_FILE), log.fork("data-dir")) { file ->
                createOwnerOnly(file)
                AppDatabase(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", properties, AppDatabase.Schema))
            }
            return AppStore(database).also { it.importLegacyFiles(dataDir, log) }
        }

        /** Owner read/write only where the OS has the notion; SQLite gives its -wal/-shm the same mode. */
        private fun createOwnerOnly(file: File) {
            if (file.exists()) return
            try {
                Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } catch (_: UnsupportedOperationException) {
                // Windows: no POSIX modes; the user's profile folder is already private.
            } catch (_: FileAlreadyExistsException) {
                // Another launch made it first: same file, same mode.
            }
        }
    }
}
