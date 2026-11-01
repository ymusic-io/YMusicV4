# :log-store

The app log: every line from every layer in one SQLite table (`logs.db`), written by `DbLogger`
(a `kmpcommon.log` sink) through `LogStore`.

- **Writing** — `enqueue` timestamps on the caller and never blocks (QuickJS-engine-thread safe); a bounded
  queue drops the oldest line under a burst. One coroutine on `Dispatchers.IO.limitedParallelism(1)` does all
  database work: queued lines are inserted one by one, and every query runs after the lines queued before it.
- **Rows** — `seq` (order across launches), `ts` (epoch ms), `level`, `launch` (32-hex id of the app open,
  `LogStore.launch`, also used as the Sentry trace id), `trail` (the logger's crumb path), `message`
  (stack trace appended for an error). `setMaxLines(n)` caps it: once the table is 10% over `n`, the oldest
  go down to `n`. The shells set it from remote config `telemetry.logLines` (default 5 000)
  after the config has loaded; until then nothing is trimmed.
- **Error events** — `claimUnsent(launch, limit)` marks the unsent lines among that launch's
  newest `limit` pending, in one transaction, so a line is never sent twice and a claim never reaches back
  past lines an earlier one took; `pending(limit, afterSeq)` pages
  through claimed lines of every launch until `markSent` settles them. `:telemetry`'s `installErrorLogs` claims, for
  each Sentry error event, the lines of the launch whose trace id the event carries, and a worker sends
  whatever is pending.
- **Feedback** — `exportGzip(fs, path)` dumps every line as text (local time, level, trail, message; a
  `--- launch <id> ---` header per app open) into a gzip file the Feedback report attaches.
- **Drivers** — `androidLogDatabaseDriver(context)`, `jvmLogDatabaseDriver(path)`, `nativeLogDatabaseDriver()`.
