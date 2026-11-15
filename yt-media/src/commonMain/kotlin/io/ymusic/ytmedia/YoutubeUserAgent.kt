package io.ymusic.ytmedia

/**
 * The User-Agent every host-made YouTube / googlevideo request sends. Must equal `DESKTOP_UA` in
 * engine-js `logic/yt/session.ts`: the PO token is minted under that UA, and googlevideo sees a
 * different one on the same visitor as another client.
 */
const val YOUTUBE_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
