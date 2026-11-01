package io.ymusic.logstore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ymusic.logstore.db.LogDatabase
import java.util.Properties

/** The JVM (JDBC) driver for [LogStore] at [path], schema created or migrated as needed. */
fun jvmLogDatabaseDriver(path: String): SqlDriver =
    JdbcSqliteDriver("jdbc:sqlite:$path", Properties().apply { put("journal_mode", "WAL") }, LogDatabase.Schema)
