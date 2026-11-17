# Image for running the kmpHttp Kotlin/Native linuxX64 test suite (handy from a macOS dev host).
#
# Base: cirruslabs/android-sdk — a popular, maintained image that already ships the Android SDK
# (platform android-35 + build-tools + accepted licenses), sdkmanager, JDK 21, and apt. We need the
# Android SDK because the root project's composite build evaluates includeBuild("YMusicLegacy"), an
# Android app whose :YMusicNative module must *configure* (it pins an NDK + externalNativeBuild). The
# kmpHttp Kotlin/Native compile uses Konan's own bundled toolchain — it does NOT use the Android NDK.
#
# Kotlin/Native downloads its own LLVM toolchain + sysroot (cached in KONAN_DATA_DIR via a volume),
# so on top of the base we only add:
#   - libcurl4-openssl-dev: the curl engine links the system libcurl (headers + .so) — no vendored libs
#   - libssl-dev:           the openssl cinterop (custom-CA merge via X509_STORE_add_cert) binds OpenSSL
#   - pkg-config:           locates both for the cinterop defs (HostNativeLibrary in buildSrc)
#   - libunistring-dev:     supplies the libunistring.so dev symlink that curl's link pulls in
#                           transitively (curl -> libidn2 -> libunistring); only the .so.N ships by default
#   - openjdk-17-jdk-headless: YMusicLegacy pins a Java toolchain 17; Gradle auto-detects it in /usr/lib/jvm
#   - git, ca-certificates: composite build evaluation + system-default-trust TLS test (public root)
#   - nodejs: runs the local test-server/ (Node + Fastify) that the suite hits instead of live hosts;
#             `:startTestServer` boots it here too. Node 20 (from NodeSource) — Fastify 5 needs >= 20.
FROM ghcr.io/cirruslabs/android-sdk:35

RUN apt-get update && apt-get install -y --no-install-recommends \
        libcurl4-openssl-dev \
        libssl-dev \
        pkg-config \
        libunistring-dev \
        openjdk-17-jdk-headless \
        git \
        ca-certificates \
        curl \
        openssl \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Bake in the NDK that YMusicLegacy's :YMusicNative declares, so AGP doesn't re-download it (~GBs) on
# every container run. It is only needed to *configure* that module; kmpHttp's native build ignores it.
RUN yes | sdkmanager "ndk;28.2.13676358" >/dev/null

WORKDIR /workspace
# :quickjs compiles its QuickJS static lib for linuxX64 with the image's existing `cc` (gcc) + `ar`
# — no extra packages needed. (QuickJS builds cleanly with gcc; Bellard's own Makefile uses it.)
