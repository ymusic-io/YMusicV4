package io.ymusic.mediastore.internal

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.db.MediaStoreDatabase
import io.ymusic.mediastore.model.BucketDraft
import io.ymusic.mediastore.model.MediaItemDraft
import io.ymusic.mediastore.model.MediaStoreBucket
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.mediastore.model.Playlist
import io.ymusic.mediastore.model.PlaylistItem
import io.ymusic.mediastore.model.PlaylistItemDetail
import io.ymusic.mediastore.model.PlaylistSnapshot
import kmpcommon.uri.Uri
import kmpcommon.uri.canonicalString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal class BetterMediaStoreImpl(
    private val driver: SqlDriver,
    // storeDispatcher() confines all DB access to a single dedicated thread, so the store uses one
    // connection to one database instance no matter how concurrently it's driven (the scanner uses a
    // worker pool). Required by JdbcSqliteDriver's thread-local connection, and the simplest correct
    // model on every platform — SQLite serializes writers anyway.
    private val io: CoroutineDispatcher = storeDispatcher(),
) : BetterMediaStore {

    private val db = MediaStoreDatabase(driver)
    private val bucketQ = db.bucketQueries
    private val itemQ = db.mediaItemQueries
    private val playlistQ = db.playlistQueries
    private val plItemQ = db.playlistItemQueries

    @OptIn(ExperimentalTime::class)
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    // ---- buckets ----

    override suspend fun upsertBucket(draft: BucketDraft): MediaStoreBucket = withContext(io) {
        db.transactionWithResult {
            // Canonical form so a differently-encoded-but-equivalent uri (My Song vs My%20Song, scheme
            // case) matches the stored row instead of inserting a duplicate. Reserved encoding (%2F/%3A
            // in a SAF content:// id) is preserved, so playback/IO is unaffected.
            val uri = draft.uri.canonicalString()
            val existing = bucketQ.findBucketByUriIncludingDeleted(uri).executeAsOneOrNull()
            val ts = now()
            // A removed parent is no parent: the bucket would be live yet unreachable from the roots.
            val parentId = draft.parentId?.takeIf { bucketQ.findBucketById(it).executeAsOneOrNull() != null }
            val id = if (existing != null) {
                bucketQ.updateBucket(parentId, draft.name, draft.aliasName, ts, existing.id)
                existing.id
            } else {
                bucketQ.insertBucket(parentId, uri, draft.name, draft.aliasName, ts, ts)
                bucketQ.lastInsertRowId().executeAsOne()
            }
            bucketQ.findBucketById(id).executeAsOne().toModel()
        }
    }

    override suspend fun setBucketAlias(bucketId: Long, alias: String?): Unit = withContext(io) {
        bucketQ.setBucketAlias(alias, now(), bucketId)
    }

    override suspend fun setBucketExcluded(bucketId: Long, excluded: Boolean): Unit = withContext(io) {
        db.transaction {
            val ts = now()
            if (excluded) {
                // Excluding cleans up the subtree: hard-delete the descendant subfolder rows (the tree
                // under an excluded folder can be huge) but soft-delete their items so playlist links
                // and a later re-scan can revive them. The excluded folder's own row is kept (flagged)
                // so it still shows greyed and can be re-included; the scan skips it (excludedBucketUris)
                // and so never revives or re-creates its contents until it's included again.
                deleteExcludedDescendants(bucketId, ts)
                itemQ.setItemsInBucketDeleted(ts, bucketId) // the excluded folder's own direct items
                bucketQ.setBucketExcluded(1, ts, bucketId)
            } else {
                bucketQ.setBucketExcluded(0, ts, bucketId)
            }
        }
    }

    // Post-order: descend first, then orphan+tombstone each descendant's items and hard-delete the
    // (now item-free) bucket. Orphaning before the delete keeps the items alive past the FK cascade.
    private fun deleteExcludedDescendants(bucketId: Long, ts: Long) {
        bucketQ.childBuckets(bucketId).executeAsList().forEach { child ->
            deleteExcludedDescendants(child.id, ts)
            itemQ.orphanAndDeleteItemsInBucket(ts, child.id)
            bucketQ.hardDeleteBucket(child.id)
        }
    }

    override suspend fun excludedBucketUris(): Set<Uri> = withContext(io) {
        bucketQ.excludedBucketUris().executeAsList().mapTo(HashSet()) { Uri.parse(it) }
    }

    override suspend fun findBucket(bucketId: Long): MediaStoreBucket? = withContext(io) {
        bucketQ.findBucketById(bucketId).executeAsOneOrNull()?.toModel()
    }

    override suspend fun findBucketByUri(uri: Uri): MediaStoreBucket? = withContext(io) {
        bucketQ.findBucketByUri(uri.canonicalString()).executeAsOneOrNull()?.toModel()
    }

    override suspend fun childBuckets(parentId: Long?): List<MediaStoreBucket> = withContext(io) {
        val rows = if (parentId == null) bucketQ.rootBuckets() else bucketQ.childBuckets(parentId)
        rows.executeAsList().map { it.toModel() }
    }

    override suspend fun allBuckets(): List<MediaStoreBucket> = withContext(io) {
        bucketQ.allBuckets().executeAsList().map { it.toModel() }
    }

    override suspend fun deleteBucket(bucketId: Long, hard: Boolean): Unit = withContext(io) {
        db.transaction {
            if (hard) {
                // FK ON DELETE CASCADE removes child buckets, items, and their playlist links.
                bucketQ.hardDeleteBucket(bucketId)
            } else {
                // Soft delete mirrors the hard-delete cascade: tombstone the whole subtree, so a
                // removed folder never leaves orphaned descendant buckets/items live in queries.
                softDeleteSubtree(bucketId, now())
            }
        }
    }

    private fun softDeleteSubtree(bucketId: Long, ts: Long) {
        bucketQ.childBuckets(bucketId).executeAsList().forEach { child ->
            softDeleteSubtree(child.id, ts)
        }
        itemQ.setItemsInBucketDeleted(ts, bucketId)
        bucketQ.setBucketDeleted(1, ts, bucketId)
    }

    // ---- items ----

    override suspend fun upsertItem(bucketId: Long?, draft: MediaItemDraft): MediaStoreItem =
        withContext(io) {
            db.transactionWithResult {
                // Canonical form so a differently-encoded-but-equivalent uri (My Song vs My%20Song, scheme
                // case) matches the stored row instead of inserting a duplicate. Reserved encoding (%2F/%3A
                // in a SAF content:// id) is preserved, so playback/IO is unaffected.
                val uri = draft.uri.canonicalString()
                val ts = now()
                val existingByUri = itemQ.findItemByUriIncludingDeleted(uri).executeAsOneOrNull()
                val id = if (existingByUri != null) {
                    updateItem(bucketId, draft, ts, existingByUri.id)
                    existingByUri.id
                } else {
                    insertItem(bucketId, draft, ts)
                    itemQ.lastInsertRowId().executeAsOne()
                }
                itemQ.findItemById(id).executeAsOne().toModel()
            }
        }

    private fun insertItem(bucketId: Long?, d: MediaItemDraft, ts: Long) = itemQ.insertItem(
        bucketId = bucketId,
        uri = d.uri.canonicalString(),
        title = d.title,
        mimeType = d.mimeType,
        artist = d.artist,
        album = d.album,
        genre = d.genre,
        trackNumber = d.trackNumber?.toLong(),
        year = d.year?.toLong(),
        durationMs = d.durationMs,
        audioCodec = d.audioCodec,
        videoCodec = d.videoCodec,
        subtitleCodec = d.subtitleCodec,
        bitrate = d.bitrate?.toLong(),
        width = d.width?.toLong(),
        height = d.height?.toLong(),
        thumbnailStreamIndex = d.thumbnailStreamIndex?.toLong(),
        youtubeVid = d.youtubeVid,
        youtubeItags = d.youtubeItags,
        fileSize = d.fileSize,
        lastModified = d.lastModified,
        createdAt = ts,
        updatedAt = ts,
        loudnessGainDb = d.loudnessGainDb,
    )

    private fun updateItem(bucketId: Long?, d: MediaItemDraft, ts: Long, id: Long) = itemQ.updateItem(
        bucketId = bucketId,
        uri = d.uri.canonicalString(),
        title = d.title,
        mimeType = d.mimeType,
        artist = d.artist,
        album = d.album,
        genre = d.genre,
        trackNumber = d.trackNumber?.toLong(),
        year = d.year?.toLong(),
        durationMs = d.durationMs,
        audioCodec = d.audioCodec,
        videoCodec = d.videoCodec,
        subtitleCodec = d.subtitleCodec,
        bitrate = d.bitrate?.toLong(),
        width = d.width?.toLong(),
        height = d.height?.toLong(),
        thumbnailStreamIndex = d.thumbnailStreamIndex?.toLong(),
        youtubeVid = d.youtubeVid,
        youtubeItags = d.youtubeItags,
        fileSize = d.fileSize,
        lastModified = d.lastModified,
        updatedAt = ts,
        loudnessGainDb = d.loudnessGainDb,
        id = id,
    )

    override suspend fun findItem(itemId: Long): MediaStoreItem? = withContext(io) {
        itemQ.findItemById(itemId).executeAsOneOrNull()?.toModel()
    }

    override suspend fun findItemByUri(uri: Uri): MediaStoreItem? = withContext(io) {
        itemQ.findItemByUri(uri.canonicalString()).executeAsOneOrNull()?.toModel()
    }

    override suspend fun findItemsByVideoId(videoId: String): List<MediaStoreItem> = withContext(io) {
        itemQ.findItemByVideoId(videoId).executeAsList().map { it.toModel() }
    }

    override suspend fun itemsInBucket(bucketId: Long?): List<MediaStoreItem> = withContext(io) {
        val rows = if (bucketId == null) itemQ.orphanItems() else itemQ.itemsInBucket(bucketId)
        rows.executeAsList().map { it.toModel() }
    }

    override suspend fun youtubeVideoIds(): Set<String> = withContext(io) {
        itemQ.youtubeVideoIds().executeAsList().filterNotNull().toSet()
    }

    override suspend fun allItems(): List<MediaStoreItem> = withContext(io) {
        itemQ.allItems().executeAsList().map { it.toModel() }
    }

    override suspend fun deleteItem(itemId: Long, hard: Boolean): Unit = withContext(io) {
        if (hard) itemQ.hardDeleteItem(itemId) else itemQ.setItemDeleted(now(), itemId)
    }

    override suspend fun relocateItem(itemId: Long, uri: Uri): Unit = withContext(io) {
        val target = uri.canonicalString()
        db.transaction {
            itemQ.findItemByUriIncludingDeleted(target).executeAsOneOrNull()
                ?.takeIf { it.id != itemId }
                ?.let { other ->
                    check(other.deleted != 0L) { "another live item already has $target" }
                    itemQ.hardDeleteItem(other.id)
                }
            itemQ.updateItemUri(target, now(), itemId)
        }
    }

    override suspend fun purgeDeletedItems(olderThan: Long): Int = withContext(io) {
        // The generated DELETE returns the number of affected rows.
        itemQ.purgeDeleted(olderThan).value.toInt()
    }

    // ---- playlists ----

    override suspend fun createPlaylist(name: String): Playlist = withContext(io) {
        db.transactionWithResult {
            val ts = now()
            playlistQ.insertPlaylist(name, ts, ts)
            val id = playlistQ.lastInsertRowId().executeAsOne()
            playlistQ.findPlaylist(id).executeAsOne().toModel()
        }
    }

    override suspend fun renamePlaylist(playlistId: Long, name: String): Unit = withContext(io) {
        playlistQ.renamePlaylist(name, now(), playlistId)
    }

    override suspend fun deletePlaylist(playlistId: Long): Unit = withContext(io) {
        // User playlists carry positive AUTOINCREMENT ids; the system playlists are computed, not
        // rows, so there is nothing here to delete for a non-positive id.
        require(playlistId > 0) { "Cannot delete a non-user playlist $playlistId" }
        playlistQ.deletePlaylist(playlistId)
    }

    override suspend fun playlists(): List<Playlist> = withContext(io) {
        playlistQ.allPlaylists().executeAsList().map { it.toModel() }
    }

    // Monotonic sortKey source for appends: seeded from the wall clock (so it exceeds any persisted
    // key after a restart) and strictly increasing within the process. Safe without a lock because
    // all store operations run on the single confinement thread (storeDispatcher).
    private var lastSortKey = 0L

    override suspend fun addToPlaylist(playlistId: Long, items: List<MediaStoreItem>) =
        addCodesToPlaylist(playlistId, items.map { it.playlistCode })

    override suspend fun addCodesToPlaylist(playlistId: Long, codes: List<String>): Unit =
        withContext(io) {
            if (codes.isEmpty()) return@withContext
            db.transaction {
                val ts = now()
                for (code in codes) {
                    lastSortKey = maxOf(now(), lastSortKey + 1)
                    plItemQ.addPlaylistItem(playlistId, code, lastSortKey, ts)
                }
            }
        }

    override suspend fun removeFromPlaylist(playlistId: Long, code: String): Unit = withContext(io) {
        plItemQ.removePlaylistItem(playlistId, code)
    }

    override suspend fun reorderPlaylist(playlistId: Long, orderedCodes: List<String>): Unit =
        withContext(io) {
            db.transaction {
                orderedCodes.forEachIndexed { index, code ->
                    plItemQ.setSortKey(index.toLong(), playlistId, code)
                }
            }
        }

    override suspend fun restorePlaylists(snapshots: List<PlaylistSnapshot>, replaceAll: Boolean): Unit =
        withContext(io) {
            db.transaction {
                val ts = now()
                if (replaceAll) {
                    playlistQ.deleteAllPlaylists()
                    for (snapshot in snapshots) {
                        playlistQ.insertPlaylist(snapshot.name, snapshot.createdAt, ts)
                        val id = playlistQ.lastInsertRowId().executeAsOne()
                        snapshot.items.forEachIndexed { index, item ->
                            plItemQ.addPlaylistItem(id, item.code, index.toLong(), item.addedAt)
                        }
                    }
                    return@transaction
                }
                val existing = playlistQ.allPlaylists().executeAsList()
                    .groupBy { it.name.trim().lowercase() }
                    .mapValues { (_, rows) -> rows.minOf { it.id } }
                    .toMutableMap()
                for (snapshot in snapshots) {
                    val key = snapshot.name.trim().lowercase()
                    val id = existing[key] ?: run {
                        playlistQ.insertPlaylist(snapshot.name.trim(), snapshot.createdAt, ts)
                        playlistQ.lastInsertRowId().executeAsOne().also { existing[key] = it }
                    }
                    for (item in snapshot.items) {
                        lastSortKey = maxOf(now(), lastSortKey + 1)
                        plItemQ.addPlaylistItem(id, item.code, lastSortKey, item.addedAt)
                    }
                }
            }
        }

    override suspend fun playlistItems(playlistId: Long): List<PlaylistItem> = withContext(io) {
        plItemQ.playlistItemsOrdered(playlistId).executeAsList().map { it.toModel() }
    }

    override suspend fun playlistItemDetails(playlistId: Long): List<PlaylistItemDetail> =
        withContext(io) {
            plItemQ.playlistItemsOrdered(playlistId).executeAsList().mapNotNull { row ->
                val item = resolveCode(row.code) ?: return@mapNotNull null
                PlaylistItemDetail(
                    playlistId = row.playlistId,
                    code = row.code,
                    addedAt = row.addedAt,
                    mediaItem = item,
                )
            }
        }

    // Resolve a playlist code to the item it points at — inverse of MediaStoreItem.playlistCode.
    // A videoId code → findItemByVideoId; a "@fileSize:durationSec" code → findItemByFingerprint.
    private fun resolveCode(code: String): MediaStoreItem? {
        if (code.startsWith("@")) {
            val sep = code.indexOf(':')
            if (sep < 0) return null
            val fileSize = code.substring(1, sep).toLongOrNull() ?: return null
            val durationSec = code.substring(sep + 1).toLongOrNull() ?: return null
            return itemQ.findItemByFingerprint(fileSize, durationSec).executeAsList().firstOrNull()?.toModel()
        }
        return itemQ.findItemByVideoId(code).executeAsList().firstOrNull()?.toModel()
    }

    // ---- reactive ----

    override fun itemsChanges(): Flow<Unit> = tableChanges("mediaItem")
    override fun bucketsChanges(): Flow<Unit> = tableChanges("bucket")

    override fun playlistChanges(playlistId: Long): Flow<List<PlaylistItem>> =
        plItemQ.playlistItemsOrdered(playlistId)
            .asFlow()
            .mapToList(io)
            .map { rows -> rows.map { it.toModel() } }

    private fun tableChanges(table: String): Flow<Unit> = callbackFlow {
        val listener = Query.Listener { trySend(Unit) }
        driver.addListener(table, listener = listener)
        awaitClose { driver.removeListener(table, listener = listener) }
    }
}
