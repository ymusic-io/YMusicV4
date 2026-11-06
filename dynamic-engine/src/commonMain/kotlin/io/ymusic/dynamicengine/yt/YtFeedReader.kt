package io.ymusic.dynamicengine.yt

import kmphttp.json.JsonReader
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Hand-parse the wire shapes (yt/wire.ts) straight off the streaming pipe with :kmpHttp:json's pull
// reader — no reflective deserializer, nothing buffered whole. Unknown keys are skipped and absent
// fields fall to their defaults, so a newer remote bundle that adds fields never breaks an old app.

/** Read one scalar as a string (null for JSON null / a non-primitive value). */
private suspend fun JsonReader.stringOrNull(): String? = (nextValue() as? JsonPrimitive)?.contentOrNull

/** Read one scalar as a boolean (false for anything non-boolean). */
private suspend fun JsonReader.boolean(): Boolean = (nextValue() as? JsonPrimitive)?.booleanOrNull ?: false

/** Read one scalar as an int (null for anything non-numeric). */
private suspend fun JsonReader.intOrNull(): Int? = (nextValue() as? JsonPrimitive)?.intOrNull

/** Read one scalar as a long (null for anything non-numeric). */
private suspend fun JsonReader.longOrNull(): Long? = (nextValue() as? JsonPrimitive)?.longOrNull

private suspend inline fun <T> JsonReader.readArrayOf(element: (JsonReader) -> T): List<T> {
    val out = mutableListOf<T>()
    beginArray()
    while (hasNextArrayElement()) out.add(element(this))
    endArray()
    return out
}

internal suspend fun readFeedPage(r: JsonReader): YtFeedPage {
    var sections: List<YtSection> = emptyList()
    var continuation: String? = null
    var chips: List<YtChip> = emptyList()
    var sort: List<YtChip> = emptyList()
    var chipsWrap = false
    var emptyTitle: String? = null
    var emptyMessage: String? = null
    var filterGroups: List<YtFilterGroup> = emptyList()
    var pageHeader: YtPageHeader? = null
    var pageTabs: List<YtPageTab> = emptyList()
    var autoplayVideoId: String? = null
    var partialErrorCode: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "sections" -> sections = r.readArrayOf { readSection(it) }
            "continuation" -> continuation = r.stringOrNull()
            "chips" -> chips = r.readArrayOf { readChip(it) }
            "sort" -> sort = r.readArrayOf { readChip(it) }
            "chipsWrap" -> chipsWrap = r.boolean()
            "emptyTitle" -> emptyTitle = r.stringOrNull()
            "emptyMessage" -> emptyMessage = r.stringOrNull()
            "filterGroups" -> filterGroups = r.readArrayOf { readFilterGroup(it) }
            "pageHeader" -> pageHeader = readPageHeader(r)
            "pageTabs" -> pageTabs = r.readArrayOf { readPageTab(it) }
            "autoplayVideoId" -> autoplayVideoId = r.stringOrNull()
            "partialErrorCode" -> partialErrorCode = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtFeedPage(
        sections, continuation, chips, sort, chipsWrap, emptyTitle, emptyMessage,
        filterGroups, pageHeader, pageTabs, autoplayVideoId, partialErrorCode,
    )
}

private suspend fun readPageHeader(r: JsonReader): YtPageHeader {
    var title: String? = null
    var subtitleLine1: String? = null
    var subtitleLine2: String? = null
    var thumbs: List<YtThumb> = emptyList()
    var banner: List<YtThumb> = emptyList()
    var itemCount: Int? = null
    var channelId: String? = null
    var subscribed: Boolean? = null
    var savePlaylistId: String? = null
    var saved: Boolean? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull()
            "subtitleLine1" -> subtitleLine1 = r.stringOrNull()
            "subtitleLine2" -> subtitleLine2 = r.stringOrNull()
            "thumbs" -> thumbs = r.readArrayOf { readThumb(it) }
            "banner" -> banner = r.readArrayOf { readThumb(it) }
            "itemCount" -> itemCount = r.intOrNull()
            "channelId" -> channelId = r.stringOrNull()
            "subscribed" -> subscribed = r.boolean()
            "savePlaylistId" -> savePlaylistId = r.stringOrNull()
            "saved" -> saved = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtPageHeader(title, subtitleLine1, subtitleLine2, thumbs, banner, itemCount, channelId, subscribed, savePlaylistId, saved)
}

private suspend fun readPageTab(r: JsonReader): YtPageTab {
    var title = ""
    var params: String? = null
    var url: String? = null
    var home = false
    var selected = false
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull().orEmpty()
            "params" -> params = r.stringOrNull()
            "url" -> url = r.stringOrNull()
            "home" -> home = r.boolean()
            "selected" -> selected = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtPageTab(title, params, url, home, selected)
}

private suspend fun readFilterGroup(r: JsonReader): YtFilterGroup {
    var title = ""
    var options: List<YtFilterOption> = emptyList()
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull().orEmpty()
            "options" -> options = r.readArrayOf { readFilterOption(it) }
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtFilterGroup(title, options)
}

private suspend fun readFilterOption(r: JsonReader): YtFilterOption {
    var label = ""
    var params: String? = null
    var selected = false
    var disabled = false
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "label" -> label = r.stringOrNull().orEmpty()
            "params" -> params = r.stringOrNull()
            "selected" -> selected = r.boolean()
            "disabled" -> disabled = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtFilterOption(label, params, selected, disabled)
}

private suspend fun readSection(r: JsonReader): YtSection {
    var header: YtSectionHeader? = null
    var layout = YtSection.LAYOUT_LIST
    var items: List<YtFeedItem> = emptyList()
    var collapsible = false
    var collapsedRows: List<Int> = emptyList()
    var continuation: String? = null
    var playMode: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "header" -> header = readSectionHeader(r)
            "layout" -> layout = r.stringOrNull() ?: YtSection.LAYOUT_LIST
            "items" -> items = r.readArrayOf { readItem(it) }
            "collapsible" -> collapsible = r.boolean()
            "collapsedRows" -> collapsedRows = r.readArrayOf { it.intOrNull() }.filterNotNull()
            "continuation" -> continuation = r.stringOrNull()
            "playMode" -> playMode = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtSection(header, layout, items, collapsible, collapsedRows, continuation, playMode)
}

private suspend fun readSectionHeader(r: JsonReader): YtSectionHeader {
    var title: String? = null
    var titleKey: String? = null
    var iconThumbs: List<YtThumb> = emptyList()
    var count: String? = null
    var ctaText: String? = null
    var ctaToken: String? = null
    var ctaDeeplink: String? = null
    var ctaAction: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull()
            "titleKey" -> titleKey = r.stringOrNull()
            "iconThumbs" -> iconThumbs = r.readArrayOf { readThumb(it) }
            "count" -> count = r.stringOrNull()
            "ctaText" -> ctaText = r.stringOrNull()
            "ctaToken" -> ctaToken = r.stringOrNull()
            "ctaDeeplink" -> ctaDeeplink = r.stringOrNull()
            "ctaAction" -> ctaAction = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtSectionHeader(title, titleKey, iconThumbs, count, ctaText, ctaToken, ctaDeeplink, ctaAction)
}

private suspend fun readThumb(r: JsonReader): YtThumb {
    var url = ""
    var w: Int? = null
    var h: Int? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "url" -> url = r.stringOrNull() ?: ""
            "w" -> w = r.intOrNull()
            "h" -> h = r.intOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtThumb(url, w, h)
}

private suspend fun readItem(r: JsonReader): YtFeedItem {
    var kind = ""
    var id = ""
    var title = ""
    var subtitleLine1: String? = null
    var subtitleLine2: String? = null
    var channelId: String? = null
    var channelTitle: String? = null
    var channelUrl: String? = null
    var url: String? = null
    var thumbs: List<YtThumb> = emptyList()
    var duration: String? = null
    var durationMs: Long? = null
    var itemCount: String? = null
    var live = false
    var musicVideo = false
    var watchedPercent: Int? = null
    var album = false
    var ownerBadge: String? = null
    var accent: String? = null
    var removeToken: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "kind" -> kind = r.stringOrNull() ?: ""
            "id" -> id = r.stringOrNull() ?: ""
            "title" -> title = r.stringOrNull() ?: ""
            "subtitleLine1" -> subtitleLine1 = r.stringOrNull()
            "subtitleLine2" -> subtitleLine2 = r.stringOrNull()
            "channelId" -> channelId = r.stringOrNull()
            "channelTitle" -> channelTitle = r.stringOrNull()
            "channelUrl" -> channelUrl = r.stringOrNull()
            "url" -> url = r.stringOrNull()
            "thumbs" -> thumbs = r.readArrayOf { readThumb(it) }
            "duration" -> duration = r.stringOrNull()
            "durationMs" -> durationMs = r.longOrNull()
            "itemCount" -> itemCount = r.stringOrNull()
            "live" -> live = r.boolean()
            "musicVideo" -> musicVideo = r.boolean()
            "watchedPercent" -> watchedPercent = r.intOrNull()
            "album" -> album = r.boolean()
            "ownerBadge" -> ownerBadge = r.stringOrNull()
            "accent" -> accent = r.stringOrNull()
            "removeToken" -> removeToken = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtFeedItem(
        kind, id, title, subtitleLine1, subtitleLine2, channelId, channelTitle, channelUrl, url, thumbs,
        duration, durationMs, itemCount, live, musicVideo, watchedPercent, album, ownerBadge,
        accent, removeToken,
    )
}

private suspend fun readChip(r: JsonReader): YtChip {
    var title = ""
    var token: String? = null
    var selected = false
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull() ?: ""
            "token" -> token = r.stringOrNull()
            "selected" -> selected = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtChip(title, token, selected)
}

/** The `yt/suggest` result: `{"suggestions":["…"]}`. */
internal suspend fun readSuggestions(r: JsonReader): List<String> {
    var suggestions: List<String> = emptyList()
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "suggestions" -> suggestions = r.readArrayOf { it.stringOrNull() ?: "" }
            else -> r.skipValue()
        }
    }
    r.endObject()
    return suggestions
}

/** The `yt/account` result — the signed-in account card (absent fields default). */
internal suspend fun readAccount(r: JsonReader): YtAccountInfo {
    var signedIn = false
    var name: String? = null
    var email: String? = null
    var handle: String? = null
    var channelId: String? = null
    var thumbs: List<YtThumb> = emptyList()
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "signedIn" -> signedIn = r.boolean()
            "name" -> name = r.stringOrNull()
            "email" -> email = r.stringOrNull()
            "handle" -> handle = r.stringOrNull()
            "channelId" -> channelId = r.stringOrNull()
            "thumbs" -> thumbs = r.readArrayOf { readThumb(it) }
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtAccountInfo(signedIn, name, email, handle, channelId, thumbs)
}

/** The `yt/login/status` result — the cookie-only sign-in gate: `{"signedIn":bool}`. */
internal suspend fun readSignedIn(r: JsonReader): Boolean {
    var signedIn = false
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "signedIn" -> signedIn = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return signedIn
}

/** The `yt/login/url` result: `{"url":"…"}`. */
internal suspend fun readLoginUrl(r: JsonReader): String {
    var url = ""
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "url" -> url = r.stringOrNull() ?: ""
            else -> r.skipValue()
        }
    }
    r.endObject()
    return url
}

/** The `yt/verify/target` result: `{"url","userAgent"}`. */
internal suspend fun readVerifyTarget(r: JsonReader): YtVerifyTarget {
    var url = ""
    var userAgent = ""
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "url" -> url = r.stringOrNull() ?: ""
            "userAgent" -> userAgent = r.stringOrNull() ?: ""
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtVerifyTarget(url, userAgent)
}

/** The `yt/locale` / `yt/locales` result: `{"hl","gl","languages"?,"regions"?}`. */
internal suspend fun readLocales(r: JsonReader): YtLocales {
    var gl = ""
    var languages: List<YtLocaleOption> = emptyList()
    var regions: List<YtLocaleOption> = emptyList()
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "gl" -> gl = r.stringOrNull() ?: ""
            "languages" -> languages = r.readArrayOf { readLocaleOption(it) }
            "regions" -> regions = r.readArrayOf { readLocaleOption(it) }
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtLocales(gl, languages, regions)
}

private suspend fun readLocaleOption(r: JsonReader): YtLocaleOption {
    var code = ""
    var label = ""
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "code" -> code = r.stringOrNull() ?: ""
            "label" -> label = r.stringOrNull() ?: ""
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtLocaleOption(code, label)
}

/** The `yt/video-details` result. */
internal suspend fun readVideoDetails(r: JsonReader): YtVideoDetails {
    var title: String? = null
    var channelTitle: String? = null
    var channelUrl: String? = null
    var channelThumbs: List<YtThumb> = emptyList()
    var subscribers: String? = null
    var factoids: List<YtFactoid> = emptyList()
    var likeCount: String? = null
    var likeCountLiked: String? = null
    var likeStatus = YtVideoDetails.INDIFFERENT
    var rateTokens: Map<String, String> = emptyMap()
    var description: String? = null
    var commentsCount: String? = null
    var commentsContinuation: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull()
            "channelTitle" -> channelTitle = r.stringOrNull()
            "channelUrl" -> channelUrl = r.stringOrNull()
            "channelThumbs" -> channelThumbs = r.readArrayOf { readThumb(it) }
            "subscribers" -> subscribers = r.stringOrNull()
            "factoids" -> factoids = r.readArrayOf { readFactoid(it) }
            "likeCount" -> likeCount = r.stringOrNull()
            "likeCountLiked" -> likeCountLiked = r.stringOrNull()
            "likeStatus" -> likeStatus = r.stringOrNull() ?: YtVideoDetails.INDIFFERENT
            "rateTokens" -> rateTokens = r.readStringMap()
            "description" -> description = r.stringOrNull()
            "commentsCount" -> commentsCount = r.stringOrNull()
            "commentsContinuation" -> commentsContinuation = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtVideoDetails(
        title, channelTitle, channelUrl, channelThumbs, subscribers, factoids, likeCount, likeCountLiked, likeStatus,
        rateTokens, description, commentsCount, commentsContinuation,
    )
}

private suspend fun JsonReader.readStringMap(): Map<String, String> {
    val out = mutableMapOf<String, String>()
    beginObject()
    while (hasNextObjectKey()) {
        val key = nextKey()
        stringOrNull()?.let { out[key] = it }
    }
    endObject()
    return out
}

private suspend fun readFactoid(r: JsonReader): YtFactoid {
    var value = ""
    var label = ""
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "value" -> value = r.stringOrNull() ?: ""
            "label" -> label = r.stringOrNull() ?: ""
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtFactoid(value, label)
}

/** The `yt/comments` result. */
internal suspend fun readCommentPage(r: JsonReader): YtCommentPage {
    var countText: String? = null
    var sort: List<YtChip> = emptyList()
    var composerPlaceholder: String? = null
    var createToken: String? = null
    var comments: List<YtComment> = emptyList()
    var continuation: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "countText" -> countText = r.stringOrNull()
            "sort" -> sort = r.readArrayOf { readChip(it) }
            "composerPlaceholder" -> composerPlaceholder = r.stringOrNull()
            "createToken" -> createToken = r.stringOrNull()
            "comments" -> comments = r.readArrayOf { readComment(it) }
            "continuation" -> continuation = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtCommentPage(countText, sort, composerPlaceholder, createToken, comments, continuation)
}

/** One `WireComment` — a `yt/comments` entry, or the whole `yt/comment/create` result. */
internal suspend fun readComment(r: JsonReader): YtComment {
    var id = ""
    var text = ""
    var author: String? = null
    var authorThumbs: List<YtThumb> = emptyList()
    var authorChannelUrl: String? = null
    var published: String? = null
    var likes: String? = null
    var likesLiked: String? = null
    var liked = false
    var disliked = false
    var likeToken: String? = null
    var unlikeToken: String? = null
    var dislikeToken: String? = null
    var undislikeToken: String? = null
    var deleteToken: String? = null
    var editToken: String? = null
    var replyToken: String? = null
    var replyPlaceholder: String? = null
    var replyCount: String? = null
    var repliesContinuation: String? = null
    var pinned: String? = null
    var hearted = false
    var byCreator = false
    var verified = false
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "id" -> id = r.stringOrNull() ?: ""
            "text" -> text = r.stringOrNull() ?: ""
            "author" -> author = r.stringOrNull()
            "authorThumbs" -> authorThumbs = r.readArrayOf { readThumb(it) }
            "authorChannelUrl" -> authorChannelUrl = r.stringOrNull()
            "published" -> published = r.stringOrNull()
            "likes" -> likes = r.stringOrNull()
            "likesLiked" -> likesLiked = r.stringOrNull()
            "liked" -> liked = r.boolean()
            "likeToken" -> likeToken = r.stringOrNull()
            "unlikeToken" -> unlikeToken = r.stringOrNull()
            "disliked" -> disliked = r.boolean()
            "dislikeToken" -> dislikeToken = r.stringOrNull()
            "undislikeToken" -> undislikeToken = r.stringOrNull()
            "deleteToken" -> deleteToken = r.stringOrNull()
            "editToken" -> editToken = r.stringOrNull()
            "replyToken" -> replyToken = r.stringOrNull()
            "replyPlaceholder" -> replyPlaceholder = r.stringOrNull()
            "replyCount" -> replyCount = r.stringOrNull()
            "repliesContinuation" -> repliesContinuation = r.stringOrNull()
            "pinned" -> pinned = r.stringOrNull()
            "hearted" -> hearted = r.boolean()
            "byCreator" -> byCreator = r.boolean()
            "verified" -> verified = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtComment(
        id, text, author, authorThumbs, authorChannelUrl, published, likes, likesLiked, liked, disliked,
        likeToken, unlikeToken, dislikeToken, undislikeToken, deleteToken, editToken, replyToken, replyPlaceholder, replyCount, repliesContinuation,
        pinned, hearted, byCreator, verified,
    )
}

internal suspend fun readPlaylistTargets(r: JsonReader): List<YtPlaylistTarget> {
    var playlists: List<YtPlaylistTarget> = emptyList()
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "playlists" -> playlists = r.readArrayOf { readPlaylistTarget(it) }
            else -> r.skipValue()
        }
    }
    r.endObject()
    return playlists
}

private suspend fun readPlaylistTarget(r: JsonReader): YtPlaylistTarget {
    var playlistId = ""
    var title = ""
    var privacy: String? = null
    var containsVideo = false
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "playlistId" -> playlistId = r.stringOrNull().orEmpty()
            "title" -> title = r.stringOrNull().orEmpty()
            "privacy" -> privacy = r.stringOrNull()
            "containsVideo" -> containsVideo = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtPlaylistTarget(playlistId, title, privacy, containsVideo)
}

internal suspend fun readCreatedPlaylistId(r: JsonReader): String {
    var playlistId = ""
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "playlistId" -> playlistId = r.stringOrNull().orEmpty()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return playlistId
}

/** The `yt/resolve-link` result; null for "none" or a kind this app version doesn't know. */
internal suspend fun readLinkTarget(r: JsonReader): YtLinkTarget? {
    var kind = ""
    var item: YtFeedItem? = null
    var url: String? = null
    var startMs: Long? = null
    var query: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "kind" -> kind = r.stringOrNull() ?: ""
            "item" -> item = readItem(r)
            "url" -> url = r.stringOrNull()
            "startMs" -> startMs = r.longOrNull()
            "query" -> query = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return when (kind) {
        "video" -> item?.let { YtLinkTarget.Video(it, url, startMs) }
        "playlist" -> url?.let(YtLinkTarget::Playlist)
        "channel" -> url?.let(YtLinkTarget::Channel)
        "music" -> url?.let(YtLinkTarget::MusicBrowse)
        "search" -> query?.let(YtLinkTarget::Search)
        else -> null
    }
}
