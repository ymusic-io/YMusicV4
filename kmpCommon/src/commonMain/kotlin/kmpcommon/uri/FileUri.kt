package kmpcommon.uri

/**
 * Build a `file://` [Uri] from an absolute filesystem [path], percent-encoding it so a name with a
 * space / `#` / `%` / non-ASCII round-trips and never mis-parses (a raw `#` would otherwise start a
 * fragment). Always use this instead of string-concatenating `"file://" + path`.
 * A Windows drive path `C:\a\b` becomes `file:///C:/a/b`.
 */
fun fileUri(path: String): Uri {
    val p = if (isDrivePath(path, 0)) path.replace('\\', '/') else path
    return UriBuilder()
        .scheme("file")
        .authority("")
        .path(if (p.startsWith('/')) p else "/$p")
        .build()
}

/**
 * The decoded filesystem path of this uri (percent-decoded). Use instead of `removePrefix("file://")`,
 * which leaves `%20` etc. in the path. `file:///C:/a/b` gives `C:/a/b`.
 */
fun Uri.filePath(): String {
    val p = path.toString()
    return if (p.startsWith('/') && isDrivePath(p, 1)) p.substring(1) else p
}

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
