# :log-store

The app log: every line from every layer in one SQLite table (`logs.db`), written by `DbLogger`
(a `kmpcommon.log` sink) through `LogStore`.

- **Writing** — `enqueue` timestamps on the caller and never blocks (QuickJS-engine-thread safe); a bounded
  queue drops the oldest line under a burst. One coroutine on `Dispatchers.IO.limitedParallelism(1)` does all
  database work: queued lines go in one transaction per batch, and every query runs after the lines queued
  before it.
- **Rows** — `seq` (order across launches), `ts` (epoch ms), `level`, `launch` (32-hex id of the app open,
  `LogStore.launch`, also used as the Sentry trace id), `trail` (the logger's crumb path), `message`
  (stack trace appended for an error). The newest `maxLines` (50 000) are kept.
- **Error events** — `claimUnsent(limit, minLevel)` returns the newest lines no earlier claim returned and moves
  the sent mark past everything stored, in one transaction, so a line is never sent twice. `:telemetry`'s
  `installErrorLogs` sends them with each Sentry error event.
- **Feedback** — `exportGzip(fs, path)` dumps every line as text (local time, level, trail, message; a
  `--- launch <id> ---` header per app open) into a gzip file the Feedback report attaches.
- **Drivers** — `androidLogDatabaseDriver(context)`, `jvmLogDatabaseDriver(path)`, `nativeLogDatabaseDriver()`.
