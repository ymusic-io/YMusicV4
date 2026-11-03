package io.ymusic.download

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ymusic.download.db.DownloadDatabase
import java.util.Properties

/**
 * JVM (JDBC) [SqlDriver] for the download queue. Pass `jdbc:sqlite:/path/downloads.db` for a persistent
 * queue, or the default in-memory URL for tests. Handing the schema in makes creation idempotent
 * (create-if-new, migrate-if-older). Keeps the JDBC dep inside `:download` — callers see only [SqlDriver].
 */
fun downloadDatabaseJdbcDriver(jdbcUrl: String = JdbcSqliteDriver.IN_MEMORY): SqlDriver {
    // Pragmas must ride Properties: xerial applies them to every connection the driver opens.
    val properties = Properties().apply {
        if (jdbcUrl != JdbcSqliteDriver.IN_MEMORY) {
            setProperty("journal_mode", "WAL")
            setProperty("synchronous", "NORMAL")
            setProperty("busy_timeout", "5000")
        }
    }
    return JdbcSqliteDriver(jdbcUrl, properties, DownloadDatabase.Schema)
}
