# `:quickjs`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **Binding** — our own QuickJS binding (vendored Bellard QuickJS, VERSION 2026-06-04, plus marked "YMusic
  patch" sites in `quickjs.c`: V8-shaped `Function.prototype.toString` / `Error.stack` / `eval(TrustedScript)` / error messages,
  Chrome `Array#join` cycle guard, `JS_NewStringLatin1` for `atob`, and flat-string storage access
  for the JSON encoder at the end of the file; re-apply on upgrade) replacing the abandoned quickjs-kt.
  Host extras sit outside the vendor tree: `__nativeFn` (`quickjs_hostfn.c`), WHATWG
  `atob`/`btoa`/`TextEncoder`/`TextDecoder`/`performance` (`quickjs_web.c`), MSE trampolines
  (`quickjs_mse.c`). CSS camelCase empty-string lives in the player env (`cssStyle` Proxy), not in
  this VM. Catalog/trace JSONL stays in js-black-box-discover.

- **FFI boundary** — a small **duplex pipe transport** (open / write→accepted / closeWrite(reason) / closeRead /
  DRAIN — no framing header; command/reason cross as native strings): JNI on jvm/android (`native/quickjs_jni.c`),
  cinterop on ios/macos/linux (`native/quickjs_native.c`), behind a shared `expect`/`actual` `NativeEngine` +
  `EngineCallbacks`.

- **Kotlin routing** — lives in `commonMain` (`QuickJsRuntime`): a multiplexed `Pipe` layer where either side opens
  command pipes (`openPipe`/`ymix.open`) or serves them (`onAccept`, removable registration), streaming raw bytes —
  async, binary-safe, one receiver per pipe.

- **Pipe semantics** — a `Pipe` is the **pair of ends** this side holds, like `pipe(2)` fds: `source` (receive +
  buffered reads + `close()` = abandon reading) and `sink` (send + buffered writes + `close(reason?)`), closed
  independently.

- **Flow control** — each direction is **bounded (64 KB) + flow-controlled** (partial writes park until DRAIN).
  - The **host→JS direction lives entirely in C** (`native/quickjs_pipe.c` `PipeBuf`: pooled 8 KB segment chain +
    the pending read job — host writes never enter JS, and every JS read is a thin `__pb*` native returning the
    value when buffered or a Promise C settles).
  - JS→Kotlin lands in an okio `Buffer`.
  - `sink.close(reason)` is a RESET the peer's read **throws** (`PipeResetException`).

- **JSON** — native in `native/quickjs_json.c`:
  - A **streaming** parser (C-level `qjs_jstream_*`, driving `source.readJsonObject()` / `.readJsonArray()`) that
    parses chunk-by-chunk without ever buffering the whole body, validates the root type on the first byte, and
    stops at the value's end so trailing data (`{json}tail`) stays readable.
  - Plus one-shot `__parseJson`/`__stringifyJson` and a resumable encoder behind `sink.writeObjectAsJson`.
  - Astral-correct by construction (own UTF-8↔UTF-16).

- **Native build** — C is compiled per target by the Gradle build (host CMake for JVM, per-target clang for native,
  NDK for Android jniLibs via `:quickjs:stageAndroidJni`); `native/pipe_stress.c` is an ASan/UBSan harness for the
  C pipe layer.

- **Ships no JS** — `QuickJsRuntime.create(script, appVersion, configure)` requires the engine script (the
  `:engine-js` bundle; its `transport.ts` module is this transport's JS half), evaluated once at creation — the
  only place a script is accepted, since C captures the `__js*` globals right after that eval; `configure` runs on
  the fresh runtime before the eval, so `onAccept` handlers registered there already serve the bundle's boot-time
  opens.

- **`awaitIdle()`** — suspends until the pipe map is empty (everything in flight is a pipe) — the drain condition a
  supervisor waits on before closing a retired runtime.

- **Bytecode** — the bundle can be **precompiled**: `QuickJsRuntime.compile(script)` emits QuickJS bytecode with
  per-function source text stripped (`Function.prototype.toString` → `[native code]` placeholder; line-number debug
  kept — ~61% smaller than unstripped) and `create(bytecode|script, appVersion)` runs it — C installs the frozen
  non-writable `versions` global (`{app}`) before the eval (the version can never be baked into the blob).

- **Tests** — embed the real bundle (`TEST_ENGINE_JS`, generated from the `:engine-js` artifact); pipe_stress evals
  the built bundle too.

- **JS support & perf** — what the vendored engine does/doesn't support and what's slow:
  [`JS_FEATURES.md`](JS_FEATURES.md) (pinned by `JsFeatureMatrixTest`).

- **Design** — [`PIPE_STREAMING.md`](PIPE_STREAMING.md).
