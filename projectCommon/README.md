# :projectCommon

Small helpers shared by the app's modules, grouped by package. Pure Kotlin, no platform code.

- `projectcommon.json` — `JsonObject` field readers over kotlinx.serialization. Required readers
  (`string`, `int`, `long`, `bool`) throw when the key is missing or the wrong type; `*OrNull`
  readers return null instead. Numbers and booleans parse the primitive's content, so a JSON number
  and a numeric string both read. `objects(key)` is the JSON-object elements of an array field.
- `projectcommon.error` — the app's error-code model: `CodedError` / `TechnicalException`
  (`E#####` site codes or well-known categories), `codedError()` / `errorCode()` (the deepest code
  in the cause chain; an uncoded kmpHttp transport failure — `isTransportFailure()` — counts as the
  `"network"` category, so the UI, download failures and telemetry's network filter agree), and `runSuspendCatching` (a `runCatching` that rethrows cancellation).
- `projectcommon.log` — app telemetry plumbing over `kmpcommon.log.Logger`: `TrailMark` /
  `markTrail` / `trailMark()` (a logger trail riding a throw as a suppressed marker),
  `captureUnlessNetwork` (a job's crash record, or a warning when the network ended it),
  and `FileAttachment` (a `captureError` prop attached by path). The app log itself is
  [`:log-store`](../log-store/README.md).

Library-shaped code with its own identity (`:kmpCommon`, `:kmpHttp`) stays out of this module — and app logic stays out of those: they are meant to be publishable.
