# kmpHttp Architecture

`kmpHttp` is a port of the OkHttp API to Kotlin Multiplatform. It exposes an OkHttp-shaped public API (`Request`, `Response`, `Headers`, `HttpUrl`, `Cookie`, `MediaType`, interceptors) but coroutine-based instead of blocking, and runs on **JVM, Apple, and desktop-native (Linux/macOS via libcurl)**.

## Build & Test

`buildSrc/src/main/kotlin/KmpTargets.kt` gates which KMP targets are configured:
- **`kmpTargets=host` is the default** (`gradle.properties`): only targets the current host can build are configured, so Gradle/the IDE never generates another OS's cinterop (e.g. the Linux libcurl/openssl bindings on macOS). Pass **`-PkmpTargets=all`** to configure every target (mainly for publishing; host-incompatible ones won't build locally).

Common commands (run from repo root):
```bash
# Full kmpHttp test suite (the canonical "all tests pass" command):
./gradlew :kmpHttp:core:allTests :kmpHttp:json:allTests

# Per-platform tests (substitute target: jvmTest / macosArm64Test / macosX64Test / linuxX64Test):
./gradlew :kmpHttp:core:jvmTest

# Single test class / method:
./gradlew :kmpHttp:core:jvmTest --tests "kmphttp.CookieTest"

./gradlew clean
```

Notes:
- Integration tests live in **`:kmpHttp:core`** (commonTest `HttpClientSmokeTests`, `InMemoryCookieJarTest`, `SslTest`; desktopTest `CurlSslTest`) and hit the **live** `https://httpbun.com` / `https://badssl.com` — they need network access; there is no local mock server. badssl can be flaky ("SSL connect error") under rapid sequential handshakes.
- Versions/coordinates are centralized in `buildSrc/src/main/kotlin/Dependencies.kt` (`Versions`/`Libs`). KMP target/sourceset boilerplate is shared via the `KmpHttpCommon.gradle.kts` convention plugin and `kmpHttpCommonConfig()` / `withAppleTargets()` / `withLinuxTargets()` / `withMacosTargets()` / `withDesktopTargets()` in `KmpTargets.kt`.
- **linuxX64 tests from a non-Linux host:** `docker compose run --rm linux-test` runs the Linux native suite in a container (`docker/linux-test.Dockerfile` + `docker-compose.yml`), pinned to `linux/amd64`, with `libcurl4-openssl-dev` + `libssl-dev` + `libunistring-dev` (curl engine + openssl cinterop link deps). Gradle/Konan caches persist in named volumes so re-runs skip the toolchain download.

## Modules

Three published modules + one benchmark, all under `kmpHttp/`:
- **`:kmpHttp:core`** — the public API and all platform `KmpHttpClient` implementations.
- **`:kmpHttp:curl`** — a standalone Kotlin wrapper over libcurl (`CurlMultiApi`/`EasyHandle`); no dependency on `:core`; consumed by core's desktopMain.
- **`:kmpHttp:json`** — a streaming, suspending JSON reader built on core's async source.
- **`:kmpHttp:json-benchmark`** — JMH/kotlinx-benchmark comparisons (incl. JITWatch task).

## Client per platform (there is no `HttpEngine` abstraction)
Each platform has its own `KmpHttpClient` implementation; the network call is the terminal of that client's interceptor chain. Construct via `KmpHttpClient.create { ... }` (the `expect fun KmpHttpClient.Companion.Builder()` supplies the platform default) or a concrete `Builder()`.

| Platform | Client | Source set | Network backend |
|----------|--------|-----------|-----------------|
| JVM | `OkKmpHttpClient` | `jvmMain` | real `okhttp3.OkHttpClient` |
| Apple | `UrlSessionKmpHttpClient` | `appleMain` | NSURLSession |
| Linux/macOS native | `CurlKmpHttpClient` | `core` `desktopMain` | libcurl (`CurlExecutor` over `:kmpHttp:curl`) |

**JVM is special: the public types are `actual typealias` straight to OkHttp** (`Request.jvm.kt → okhttp3.Request`, likewise Response/Headers/HttpUrl/Cookie/MediaType/…), and `core` re-exports OkHttp via `api(...)` — so on JVM this is a thin coroutine adapter over OkHttp, which runs its own interceptor/cache/redirect machinery. On non-JVM platforms that machinery is reimplemented in `nonJvmMain`.

## Source-set hierarchy
```
commonMain  (AsyncInterceptor/Chain, AsyncSource, expect API types, KmpHttpClient interface)
├── jvmMain        → typealias to OkHttp + OkKmpHttpClient
└── nonJvmMain     (actual API impls, built-in interceptors, cache/, idn/, publicsuffix/, CookieJar)
    └── nativeMain (DAFSA-based PSL, SystemFileSystem.native, IDN)
        ├── appleMain   → UrlSessionKmpHttpClient (iosMain, macosMain)
        ├── linuxMain ──┐
        └── desktopMain ┘  (CurlKmpHttpClient + CurlExecutor; depends on :kmpHttp:curl) — macos+linux
```

## Key abstractions in `commonMain`
- **`AsyncInterceptor`** (`fun interface … { suspend fun intercept(chain): Response }`) + nested `Chain` — the OkHttp `Interceptor`/`Interceptor.Chain` analog, made `suspend`. `internal/AsyncChain.kt` is the `RealInterceptorChain` analog (recurses by index, then calls the terminal network lambda). **There is no `Call` object** — `client.execute(request)` replaces `newCall().execute()`.
- **`AsyncSource` / `BufferedAsyncSource`** — a suspend re-implementation of Okio's `Source`/`BufferedSource`. All streaming request/response bodies flow through this.

## Built-in interceptor chain (non-JVM clients)
Each non-JVM client builds its chain in `execute()`: user `interceptors` → `FollowUpInterceptor` (redirects) → `BridgeInterceptor` (cookies + user-agent) → `CacheInterceptor` (HTTP cache) → user `networkInterceptors` → network terminal. See `core/src/nonJvmMain/kotlin/kmphttp/internal/http/`.

## Cache subsystem (`core/src/nonJvmMain/kotlin/kmphttp/cache/`)
A port of OkHttp's cache: `Cache` → `DefaultCache` → `DiskLruCache` (+ `Journal`), with `CacheStrategy` for freshness and `CacheInterceptor` for serve/revalidate/write-back. `SystemFileSystem` is an `expect object` (native actual).

## Public Suffix List (`core/.../internal/publicsuffix/`)
Computes registrable domain (eTLD+1) for cookie domain-scoping. **Native** uses an embedded **DAFSA** blob (`PslDafsa.kt` + `DafsaLookup.kt`, regenerated by `scripts/*psl*.py`) with IDNA/punycode in `internal/idn/`. **JVM** uses OkHttp's own.

## `:kmpHttp:json`
A non-recursive, suspending `JsonReader(source: BufferedAsyncSource)` that stream-parses chunked input into kotlinx-serialization `JsonElement`s and can **skip** parts of the document via `JsonStructFilter` (glob path matching). Consumes core's `BufferedAsyncSource`; does not extend the `Request`/`Response` API.

## `:kmpHttp:curl`
All code in `desktopMain` (no `commonMain`). Two cinterops (`src/nativeInterop/cinterop/libcurl.def`, `openssl.def`) bind the **build host's libcurl + OpenSSL** — no vendored headers or libs, and no paths in the checked-in defs. Per target, `generate{Libcurl,Openssl}Def<Target>` (`HostNativeLibrary.kt` in buildSrc) locates each library when it runs and writes a def with its `compilerOpts`/`linkerOpts` (cinterop drops DSL `linkerOpts`; only the def's reach the klib and every binary linking it). First hit wins:
1. the override: `-PcurlPrefix` / `CURL_PREFIX`, `-PopensslPrefix` / `OPENSSL_PREFIX` (a prefix with `include/` and `lib/`; a wrong one fails, no fallback);
2. OpenSSL on macOS only: the copy the located `libcurl.dylib` links (`otool -L`). It must be the same OpenSSL or the `SSL_CTX` ABI won't match, and pkg-config's linked copy can be another major version (Homebrew `openssl@3` vs `openssl@4`);
3. `pkg-config libcurl` / `pkg-config openssl` (honours `PKG_CONFIG_PATH`; system dirs are kept, since Kotlin/Native builds against its own sysroot; Debian multiarch include dirs are added). On macOS the OS's own libcurl in `/usr/lib` is skipped: it is not built on OpenSSL;
4. macOS: `brew --prefix --installed curl` / `openssl`.

Nothing found fails that task with what was searched and how to install (`brew install curl`; `apt install pkg-config libcurl4-openssl-dev libssl-dev`) or override. Windows configures no curl target.

`EasyHandle` wraps a libcurl easy handle driven by `CurlMultiApi` (its own event-loop worker thread, pause/resume backpressure). `CurlExecutor` (in `:core` desktopMain) is the network terminal.

## TLS / `SslConfig`
`SslConfig` (custom `trustedCAs`, `trustSystemDefaultCAs`, `pinCertificates`, `clientCerts`) is applied per client. For curl, `CurlSslConfig` pre-digests it into curl's byte forms once per client. Custom CA + `trustSystemDefaultCAs=true` is merged with the system roots via OpenSSL `X509_STORE_add_cert` (`CURLOPT_SSL_CTX_FUNCTION`); `trustSystemDefaultCAs=false` replaces the store (`CURLOPT_CAINFO_BLOB`). curl uses PEM client certs (no PKCS#12). **Known limitation:** NSURLSession mutual-TLS is skipped on a macOS host (`SecPKCS12Import` blocks on the login keychain headlessly) — use `CurlKmpHttpClient` there.
