package io.ymusic.download.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

// File-backed JdbcSqliteDriver opens one connection per thread; one thread = one connection, no SQLITE_BUSY.
private val dbDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "download-db").apply { isDaemon = true } }
        .asCoroutineDispatcher()

internal actual fun downloadDbDispatcher(): CoroutineDispatcher = dbDispatcher
