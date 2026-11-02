package io.ymusic.logstore

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ymusic.logstore.db.LogDatabase

/** The Android driver for [LogStore], schema created or migrated as needed. */
fun androidLogDatabaseDriver(context: Context, name: String = "logs.db"): SqlDriver =
    AndroidSqliteDriver(schema = LogDatabase.Schema, context = context, name = name)
