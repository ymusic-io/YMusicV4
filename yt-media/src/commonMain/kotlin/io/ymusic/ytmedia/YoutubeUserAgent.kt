package io.ymusic.ytmedia

/**
 * The User-Agent a host-made YouTube / googlevideo request falls back to when the engine names none:
 * a bundle older than `SabrStreamSpec.userAgent`, or a proxied request the bundle sent without one.
 * The engine's own `DESKTOP_UA` wins wherever it is given, since the PO token is minted under it.
 */
const val YOUTUBE_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
