package kmpcommon.uri

/**
 * Build a `file://` [Uri] from an absolute filesystem [path], percent-encoding it so a name with a
 * space / `#` / `%` / non-ASCII round-trips and never mis-parses (a raw `#` would otherwise start a
 * fragment). Always use this instead of string-concatenating `"file://" + path`.
 * A Windows drive path `C:\a\b` becomes `file:///C:/a/b`; a UNC path `\\server\share\a` becomes
 * `file://server/share/a`.
 */
fun fileUri(path: String): Uri {
    if (isUncPath(path)) {
        val rest = path.substring(2).replace('\\', '/')
        val slash = rest.indexOf('/')
        return UriBuilder()
            .scheme("file")
            .authority(PercentEncoder.canonicalizeHost(if (slash < 0) rest else rest.substring(0, slash)).toString())
            .path(if (slash < 0) "/" else rest.substring(slash))
            .build()
    }
    val p = if (isDrivePath(path, 0)) path.replace('\\', '/') else path
    return UriBuilder()
        .scheme("file")
        .authority("")
        .path(if (p.startsWith('/')) p else "/$p")
        .build()
}

/**
 * The decoded filesystem path of this uri (percent-decoded). Use instead of `removePrefix("file://")`,
 * which leaves `%20` etc. in the path. `file:///C:/a/b` gives `C:/a/b`; `file://server/share/a` gives
 * `\\server\share\a`.
 */
fun Uri.filePath(): String {
    val p = path.toString()
    val host = authority?.takeIf { it.isNotEmpty() && !it.contentEquals("localhost", ignoreCase = true) }
    if (host != null) return """\\""" + PercentEncoder.decode(host) + p.replace('/', '\\')
    return if (p.startsWith('/') && isDrivePath(p, 1)) p.substring(1) else p
}

private fun isUncPath(s: String): Boolean = s.length > 2 && s[0] == '\\' && s[1] == '\\' && s[2] != '\\'

private fun isDrivePath(s: String, at: Int): Boolean =
    s.length >= at + 2 && (s[at] in 'A'..'Z' || s[at] in 'a'..'z') && s[at + 1] == ':' &&
        (s.length == at + 2 || s[at + 2] == '/' || s[at + 2] == '\\')

/**
 * The canonical form for IDENTITY comparison — normalized percent-encoding + a lowercased scheme — so
 * two uris that denote the same resource (e.g. `My Song.mp3` vs `My%20Song.mp3`) compare equal. Store
 * and match uris through [canonicalString], never the raw string.
 */
fun Uri.canonical(): Uri = UriBuilder(this).build()

fun Uri.canonicalString(): String = canonical().toString()
