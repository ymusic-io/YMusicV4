package io.ymusic.remoteconfig

import kmphttp.HttpStatusException
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import okio.Buffer
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.blackholeSink
import okio.buffer
import okio.use

/** The downloaded file is not the published one: wrong size or hash. Its partial file is gone. */
class DownloadMismatchException(message: String) : Exception(message)

/**
 * Downloads [url] into [part], continuing whatever an interrupted attempt (network failure, app
 * killed) left there: an existing partial file is resumed with `Range: bytes=<its size>-` and
 * appended to only on a `206`; any other answer abandons it and the download starts over from
 * byte 0. A partial file already [size] long (killed before the caller moved it into place) is
 * just verified.
 *
 * The finished file must be exactly [size] bytes hashing to [sha256] (hex), else
 * [DownloadMismatchException] and [part] is deleted, so the next attempt starts clean. A non-2xx
 * answer throws [HttpStatusException]. Any other failure leaves [part] for the next attempt to
 * resume. Name [part] after the release's version AND hash: bytes of one release must never be
 * continued with another's.
 */
suspend fun downloadResumable(
    client: KmpHttpClient,
    url: String,
    part: Path,
    fileSystem: FileSystem,
    size: Long,
    sha256: String,
    onProgress: (done: Long) -> Unit,
) {
    var offset = fileSystem.metadataOrNull(part)?.size ?: 0L
    if (offset > size) {
        fileSystem.delete(part)
        offset = 0L
    }
    if (offset == size) {
        if (sha256Of(fileSystem, part) == sha256) return
        fileSystem.delete(part)
        offset = 0L
    }

    // no-store: the shared client's disk cache must not keep a copy of the installer.
    suspend fun request(from: Long) = client.execute(
        RequestBuilder().url(url).header("Cache-Control", "no-store")
            .apply { if (from > 0) header("Range", "bytes=$from-") }
            .get().build(),
    )

    var response = request(offset)
    if (offset > 0 && response.code != 206) {
        response.close()
        fileSystem.delete(part)
        offset = 0L
        response = request(0L)
    }
    try {
        if (!response.isSuccessful) throw HttpStatusException(response.code, "HTTP ${response.code} fetching $url")
        val body = response.body.asyncSource()
        (if (offset > 0) fileSystem.appendingSink(part) else fileSystem.sink(part)).buffer().use { out ->
            val chunk = Buffer()
            var done = offset
            onProgress(done)
            while (true) {
                val read = body.read(chunk, 64 * 1024L)
                if (read == -1L) break
                out.write(chunk, read)
                done += read
                if (done > size) throw DownloadMismatchException("more than the published $size bytes")
                onProgress(done)
            }
        }
    } catch (mismatch: DownloadMismatchException) {
        fileSystem.delete(part) // after the sink closed: Windows cannot delete an open file
        throw mismatch
    } finally {
        response.close()
    }

    val hashed = sha256Of(fileSystem, part)
    if (hashed != sha256) {
        fileSystem.delete(part)
        throw DownloadMismatchException("hashed $hashed, published $sha256")
    }
}

private fun sha256Of(fileSystem: FileSystem, file: Path): String {
    val hashing = HashingSink.sha256(blackholeSink())
    hashing.buffer().use { out -> fileSystem.source(file).use { out.writeAll(it) } }
    return hashing.hash.hex()
}
