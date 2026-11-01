package io.ymusic.logstore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.ymusic.logstore.db.LogDatabase

/** The native (sqliter) driver for [LogStore]; [name] is the file in the platform's database dir. */
fun nativeLogDatabaseDriver(name: String = "logs.db"): SqlDriver =
    NativeSqliteDriver(schema = LogDatabase.Schema, name = name)
