package io.ymusic.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.ymusic.ui.NOW_PLAYING_ART_PX
import io.ymusic.ui.artImageRequest
import kotlinx.coroutines.runBlocking
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.Path
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * `content://<pkg>.artwork/art?uri=<encoded target>&sig=<hmac>` — read-only artwork for
 * cross-process consumers that cannot fetch our urls themselves. Android Auto / AAOS resolve queue
 * and browse icons ONLY from local content uris (they never download https, and `art://` is
 * app-internal), so [PlaybackService] publishes every artworkUri in this wrapped form.
 *
 * The provider is exported (Auto holds no permission of ours), so [openFile] serves ONLY uris this
 * install minted: [artworkContentUri] signs the target with a per-install secret
 * (HMAC-SHA256, [artworkSignature]); a request whose signature doesn't verify 404s before anything
 * is fetched. Another app can relay a uri it saw (that's the point — the car host does exactly
 * that) but can never compose its own. The target must additionally pass [isAllowedArtworkTarget]
 * — our own `art://pic/<id>` or an https image on a YouTube cdn host — defense in depth should the
 * salt ever leak with a backup.
 *
 * The served file IS the shared app ImageLoader's scaled disk cache entry
 * ([io.ymusic.imageloader.ImageLoader.loadFile]): no provider-local storage — the loader's journaled
 * LRU owns write atomicity and eviction (a local item's fallback art is the loader's transient file).
 * A path can be evicted between resolve and open; one re-resolve covers that race.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("artwork is read-only")
        val context = requireNotNull(context)
        val target = uri.getQueryParameter(ARTWORK_URI_PARAM)
            ?.takeIf { verifySignature(context, it, uri.getQueryParameter(ARTWORK_SIG_PARAM)) }
            ?.takeIf { isAllowedArtworkTarget(it) }
            ?: throw FileNotFoundException("refused artwork target: $uri")
        var firstMiss: FileNotFoundException? = null
        repeat(2) { attempt ->
            val path = resolve(target)
            try {
                return ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: FileNotFoundException) {
                if (attempt == 1) { // evicted twice in a row: give up
                    firstMiss?.let(e::addSuppressed)
                    throw e
                }
                firstMiss = e
            }
        }
        throw FileNotFoundException(target) // unreachable
    }

    /** Binder thread; the app ImageLoader is the async boundary, so blocking here is the contract
     *  (Auto shows its loading placeholder until the descriptor arrives). */
    private fun resolve(target: String): Path {
        val app = requireNotNull(context).applicationContext as YMusicApplication
        try {
            return runBlocking {
                app.services.await().imageLoader.loadFile(artImageRequest(target, ARTWORK_MAX_PX, ARTWORK_MAX_PX))
            } ?: throw FileNotFoundException("no scaled disk cache to serve artwork from")
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            // FileNotFoundException is the one exception a ContentProvider open may throw; keep the
            // real cause on its chain instead of flattening it into the message.
            throw FileNotFoundException("artwork load failed for $target").apply { initCause(e) }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("artwork is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("artwork is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("artwork is read-only")
}

/** Authority of [ArtworkProvider] for this install (manifest: `${applicationId}.artwork`). */
fun artworkAuthority(context: Context): String = "${context.packageName}.artwork"

/** [url] (an https thumb or `art://` uri) wrapped as a signed content uri Android Auto can
 *  resolve — [ArtworkProvider] serves only uris carrying this install's [artworkSignature]. */
fun artworkContentUri(context: Context, url: String): Uri =
    Uri.Builder()
        .scheme("content")
        .authority(artworkAuthority(context))
        .path("art")
        .appendQueryParameter(ARTWORK_URI_PARAM, url)
        .appendQueryParameter(ARTWORK_SIG_PARAM, artworkSignature(context, url))
        .build()

/** HMAC-SHA256 of [target] under this install's secret salt — the mint mark on every exposed uri. */
internal fun artworkSignature(context: Context, target: String): String =
    target.encodeUtf8().hmacSha256(artworkSalt(context)).hex()

/** Constant-time signature check; a missing or malformed [sig] never matches. */
internal fun verifySignature(context: Context, target: String, sig: String?): Boolean {
    if (sig == null) return false
    val expected = artworkSignature(context, target)
    return MessageDigest.isEqual(sig.lowercase().encodeToByteArray(), expected.encodeToByteArray())
}

/** The per-install artwork secret: generated once on first use, kept in its own prefs file. Only
 *  uris minted by this install verify — a reinstall invalidates old uris, which are ephemeral
 *  session metadata anyway. */
private fun artworkSalt(context: Context): ByteString {
    cachedSalt?.let { return it }
    synchronized(saltLock) {
        cachedSalt?.let { return it }
        val prefs = context.getSharedPreferences("artwork_provider", Context.MODE_PRIVATE)
        val salt = prefs.getString(SALT_KEY, null)?.decodeHex()
            ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.toByteString()
                .also { prefs.edit().putString(SALT_KEY, it.hex()).apply() }
        cachedSalt = salt
        return salt
    }
}

@Volatile private var cachedSalt: ByteString? = null
private val saltLock = Any()
private const val SALT_KEY = "salt"

/** True for targets the exported provider serves: our own `art://pic/<media-id>` uris, or https
 *  images on YouTube's cdns (video thumbs / music covers / channel avatars). Everything else —
 *  arbitrary hosts, other schemes, other apps' content — is refused; an exported provider must
 *  not be an open image proxy. */
internal fun isAllowedArtworkTarget(target: String): Boolean {
    val uri = Uri.parse(target)
    return when (uri.scheme) {
        "art" -> uri.authority == "pic" && uri.lastPathSegment?.toLongOrNull() != null
        "https" -> {
            val host = uri.host ?: return false
            ALLOWED_IMAGE_HOSTS.any { host == it || host.endsWith(".$it") }
        }
        else -> false
    }
}

internal const val ARTWORK_URI_PARAM = "uri"
internal const val ARTWORK_SIG_PARAM = "sig"

/** Session/notification/Auto artwork target size, shared with Now Playing; the loader never upscales. */
internal const val ARTWORK_MAX_PX = NOW_PLAYING_ART_PX

private val ALLOWED_IMAGE_HOSTS = listOf("ytimg.com", "ggpht.com", "googleusercontent.com")
