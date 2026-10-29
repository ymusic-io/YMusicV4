package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtChip
import io.ymusic.dynamicengine.yt.YtComment
import io.ymusic.dynamicengine.yt.YtCommentPage
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtVideoDetails
import io.ymusic.dynamicengine.yt.commentAction
import io.ymusic.dynamicengine.yt.createComment
import io.ymusic.dynamicengine.yt.editComment
import io.ymusic.dynamicengine.yt.extractComments
import io.ymusic.dynamicengine.yt.extractVideoDetails
import io.ymusic.dynamicengine.yt.rateVideo
import io.ymusic.dynamicengine.yt.replyToComment
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_close
import io.ymusic.ui.resources.cd_more
import io.ymusic.ui.resources.cd_share
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.retry
import io.ymusic.ui.resources.show_less
import io.ymusic.ui.resources.show_more
import io.ymusic.ui.resources.video_details
import io.ymusic.ui.resources.video_details_comment_hint
import io.ymusic.ui.resources.video_details_comments
import io.ymusic.ui.resources.video_details_comments_off
import io.ymusic.ui.resources.video_details_delete
import io.ymusic.ui.resources.video_details_delete_message
import io.ymusic.ui.resources.video_details_delete_title
import io.ymusic.ui.resources.video_details_deleted
import io.ymusic.ui.resources.video_details_dislike
import io.ymusic.ui.resources.video_details_edit
import io.ymusic.ui.resources.video_details_edited
import io.ymusic.ui.resources.video_details_hide_replies
import io.ymusic.ui.resources.video_details_like
import io.ymusic.ui.resources.video_details_more_replies
import io.ymusic.ui.resources.video_details_no_comments
import io.ymusic.ui.resources.video_details_post
import io.ymusic.ui.resources.video_details_posted
import io.ymusic.ui.resources.video_details_replies
import io.ymusic.ui.resources.video_details_reply
import io.ymusic.ui.resources.video_details_reply_posted
import io.ymusic.ui.resources.video_details_sign_in_to_comment
import io.ymusic.ui.resources.video_details_sign_in_to_rate
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.errorCode

/** One comments list — a video's top-level comments, or one thread's replies. */
internal class CommentList {
    val comments = mutableStateListOf<YtComment>()
    var continuation by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var errorCode by mutableStateOf<String?>(null)
}

/** The watch page minus the player for [videoId]: details, then comments paged on demand. */
internal class VideoDetailsState(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    val videoId: String,
    parentLog: Logger?,
) {
    private val log = parentLog?.fork("VideoDetails", mapOf("videoId" to videoId))

    var details by mutableStateOf<YtVideoDetails?>(null)
    var detailsErrorCode by mutableStateOf<String?>(null)

    /** The first comments page's header (count, sort chips, composer). */
    var header by mutableStateOf<YtCommentPage?>(null)
    val comments = CommentList()
    val replies = mutableStateMapOf<String, CommentList>()
    var posting by mutableStateOf(false)
    var postErrorCode by mutableStateOf<String?>(null)
    var likeStatus by mutableStateOf(YtVideoDetails.INDIFFERENT)

    /** A failed rate / comment rating / delete, for the UI to surface once. */
    var actionErrorCode by mutableStateOf<String?>(null)

    /** The viewer's comment ratings made in this dialog, over each comment's served state. */
    val commentRating = mutableStateMapOf<String, String>()

    /** New text of the viewer's comments edited in this dialog. */
    val editedText = mutableStateMapOf<String, String>()
    var editingId by mutableStateOf<String?>(null)
    var editPosting by mutableStateOf(false)
    var editErrorCode by mutableStateOf<String?>(null)

    /** The comment awaiting delete confirmation. */
    var pendingDelete by mutableStateOf<YtComment?>(null)

    /** The comment whose reply box is open. */
    var replyingTo by mutableStateOf<String?>(null)
    var replyPosting by mutableStateOf(false)
    var replyErrorCode by mutableStateOf<String?>(null)

    fun load() {
        detailsErrorCode = null
        scope.launch {
            try {
                val loaded = graph.engines.extractVideoDetails(videoId, log)
                likeStatus = loaded.likeStatus
                details = loaded
                loaded.commentsContinuation?.let { reloadComments(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "video details failed", error)
                detailsErrorCode = error.errorCode() ?: "E20094"
            }
        }
    }

    /** Replace the list from [token] — the first page or a sort chip. */
    fun reloadComments(token: String) {
        comments.comments.clear()
        comments.continuation = token
        loadMore(comments, keepHeader = false)
    }

    fun loadMore(list: CommentList, keepHeader: Boolean = true) {
        val token = list.continuation ?: return
        if (list.loading) return
        list.loading = true
        list.errorCode = null
        scope.launch {
            try {
                val page = graph.engines.extractComments(token, log)
                if (!keepHeader && list === comments) header = page
                list.comments.addAll(page.comments)
                list.continuation = page.continuation
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "comments page failed", error)
                list.errorCode = error.errorCode() ?: "E20095"
            } finally {
                list.loading = false
            }
        }
    }

    fun toggleReplies(comment: YtComment) {
        if (replies.remove(comment.id) != null) return
        val list = CommentList().apply { continuation = comment.repliesContinuation }
        replies[comment.id] = list
        loadMore(list)
    }

    /** Toggle [status] (LIKE / DISLIKE) optimistically; a failure restores the previous rating. */
    fun toggleRating(status: String) {
        val target = if (likeStatus == status) YtVideoDetails.INDIFFERENT else status
        val token = details?.rateTokens?.get(target) ?: return
        val previous = likeStatus
        likeStatus = target
        scope.launch {
            try {
                graph.engines.rateVideo(videoId, target, token, log)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "rate video failed", error)
                if (likeStatus == target) likeStatus = previous
                actionErrorCode = error.errorCode() ?: "E20097"
            }
        }
    }

    fun ratingOf(comment: YtComment): String = commentRating[comment.id] ?: when {
        comment.liked -> YtVideoDetails.LIKE
        comment.disliked -> YtVideoDetails.DISLIKE
        else -> YtVideoDetails.INDIFFERENT
    }

    /** Toggle [target] (LIKE / DISLIKE) on [comment] optimistically; a failure restores the rating. */
    fun rateComment(comment: YtComment, target: String) {
        val current = ratingOf(comment)
        val undo = current == target
        val token = when {
            undo && target == YtVideoDetails.LIKE -> comment.unlikeToken
            undo -> comment.undislikeToken
            target == YtVideoDetails.LIKE -> comment.likeToken
            else -> comment.dislikeToken
        } ?: return
        commentRating[comment.id] = if (undo) YtVideoDetails.INDIFFERENT else target
        scope.launch {
            try {
                graph.engines.commentAction(token, log)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "comment rating failed", error)
                commentRating[comment.id] = current
                actionErrorCode = error.errorCode() ?: "E20099"
            }
        }
    }

    fun textOf(comment: YtComment): String = editedText[comment.id] ?: comment.text

    fun startEdit(comment: YtComment) {
        editingId = comment.id
        editErrorCode = null
    }

    fun saveEdit(comment: YtComment, text: String, onSaved: () -> Unit) {
        val token = comment.editToken ?: return
        if (editPosting) return
        editPosting = true
        editErrorCode = null
        scope.launch {
            try {
                graph.engines.editComment(token, text, log)
                editedText[comment.id] = text
                editingId = null
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "edit comment failed", error)
                editErrorCode = error.errorCode() ?: "E20102"
            } finally {
                editPosting = false
            }
        }
    }

    fun delete(comment: YtComment, onDeleted: () -> Unit) {
        val token = comment.deleteToken ?: return
        scope.launch {
            try {
                graph.engines.commentAction(token, log)
                comments.comments.removeAll { it.id == comment.id }
                replies.values.forEach { list -> list.comments.removeAll { it.id == comment.id } }
                replies.remove(comment.id)
                onDeleted()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "delete comment failed", error)
                actionErrorCode = error.errorCode() ?: "E20101"
            }
        }
    }

    fun openReply(comment: YtComment) {
        replyingTo = if (replyingTo == comment.id) null else comment.id
        replyErrorCode = null
    }

    /** Reply to [parent] (a top-level comment); the reply lands at the top of its open thread. */
    fun postReply(parent: YtComment, text: String, onPosted: () -> Unit) {
        val token = parent.replyToken ?: return
        if (replyPosting) return
        replyPosting = true
        replyErrorCode = null
        scope.launch {
            try {
                val posted = graph.engines.replyToComment(token, text, log)
                val thread = replies.getOrPut(parent.id) { CommentList().apply { continuation = parent.repliesContinuation } }
                thread.comments.add(0, posted)
                replyingTo = null
                onPosted()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "reply failed", error)
                replyErrorCode = error.errorCode() ?: "E20100"
            } finally {
                replyPosting = false
            }
        }
    }

    fun post(text: String, onPosted: () -> Unit) {
        val token = header?.createToken ?: return
        if (posting) return
        posting = true
        postErrorCode = null
        scope.launch {
            try {
                val posted = graph.engines.createComment(token, text, log)
                comments.comments.add(0, posted)
                onPosted()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "post comment failed", error)
                postErrorCode = error.errorCode() ?: "E20096"
            } finally {
                posting = false
            }
        }
    }
}

/** Video details + comments in the app's [ElevatedBarDialog] chrome. */
@Composable
internal fun VideoDetailsDialog(
    graph: AppGraph,
    videoId: String,
    onOpenChannel: (url: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(videoId) { VideoDetailsState(graph, scope, videoId, graph.logger) }
    LaunchedEffect(state) { state.load() }
    val listState = rememberLazyListState()
    ElevatedBarDialog(
        title = stringResource(Res.string.video_details),
        onDismiss = onDismiss,
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cd_close), color = DesignColors.Accent) }
        },
        maxWidth = 720.dp,
        dismissOnClickOutside = false,
    ) {
        VideoDetailsContent(state, listState, onOpenChannel, Modifier.fillMaxWidth())
    }
}

@Composable
private fun VideoDetailsContent(
    state: VideoDetailsState,
    listState: LazyListState,
    onOpenChannel: (url: String, title: String) -> Unit,
    modifier: Modifier,
) {
    val details = state.details
    val snackbar = LocalSnackbar.current
    val actionError = state.actionErrorCode?.let { errorMessage(it) }
    LaunchedEffect(actionError) {
        if (actionError != null) {
            snackbar(actionError)
            state.actionErrorCode = null
        }
    }
    DeleteCommentConfirm(state)
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.comments.comments.size) {
        if (nearEnd && state.comments.errorCode == null) state.loadMore(state.comments)
    }
    LazyColumn(modifier, state = listState) {
        when {
            details != null -> item("details") { DetailsHeader(state, details, onOpenChannel) }
            state.detailsErrorCode != null -> item("error") {
                ErrorState(state.detailsErrorCode!!, Modifier.fillMaxWidth().heightIn(min = 240.dp)) { state.load() }
            }
            else -> item("loading") { Spinner(Modifier.heightIn(min = 240.dp)) }
        }
        if (details != null) commentsSection(state, details, onOpenChannel)
    }
}

@Composable
private fun DetailsHeader(state: VideoDetailsState, details: YtVideoDetails, onOpenChannel: (url: String, title: String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
    ) {
        details.title?.let {
            Text(it, style = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkStrong))
        }
        if (details.factoids.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                details.factoids.forEach { factoid ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(factoid.value, style = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink))
                        Text(factoid.label, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name = details.channelTitle
            val url = details.channelUrl
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (name != null && url != null) Modifier.clickable { onOpenChannel(url, name) } else Modifier),
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (name != null) {
                    Avatar(details.channelThumbs, 40.dp)
                    Column {
                        Text(
                            name,
                            style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        details.subscribers?.let {
                            Text(it, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
                RatingPill(state, details)
                LocalShareYoutube.current?.let { share -> SharePill { share(YtFeedItem.KIND_VIDEO, state.videoId) } }
            }
        }
        details.description?.takeIf { it.isNotBlank() }?.let { Description(it) }
        HorizontalDivider(color = DesignColors.Divider)
    }
}

/** Like (with count) | dislike. Signed out, a tap explains that rating needs sign-in. */
@Composable
private fun RatingPill(state: VideoDetailsState, details: YtVideoDetails) {
    val snackbar = LocalSnackbar.current
    val signIn = stringResource(Res.string.video_details_sign_in_to_rate)
    val canRate = details.rateTokens.isNotEmpty()
    val tap = { status: String -> if (canRate) state.toggleRating(status) else snackbar(signIn) }
    val liked = state.likeStatus == YtVideoDetails.LIKE
    val disliked = state.likeStatus == YtVideoDetails.DISLIKE
    val ink = DesignColors.Ink
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(DesignColors.SurfaceDim),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val likeCount = if (liked) details.likeCountLiked ?: details.likeCount else details.likeCount
        Row(
            Modifier
                .toggleable(value = liked, role = Role.Checkbox) { tap(YtVideoDetails.LIKE) }
                .a11yActionLabel(listOfNotNull(stringResource(Res.string.video_details_like), likeCount).joinToString(", "))
                .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            Icon(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, null, Modifier.size(18.dp), tint = ink)
            likeCount?.let {
                Text(
                    it,
                    style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink),
                    modifier = Modifier.a11ySilent(),
                )
            }
        }
        Box(Modifier.size(width = 1.dp, height = 20.dp).background(DesignColors.Divider))
        Box(
            Modifier
                .toggleable(value = disliked, role = Role.Checkbox) { tap(YtVideoDetails.DISLIKE) }
                .a11yActionLabel(stringResource(Res.string.video_details_dislike))
                .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
        ) {
            Icon(if (disliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, null, Modifier.size(18.dp), tint = ink)
        }
    }
}

@Composable
private fun SharePill(onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(DesignColors.SurfaceDim)
            .clickable(onClick = onClick)
            .a11yActionLabel(stringResource(Res.string.cd_share))
            .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
    ) {
        Icon(Icons.Outlined.Share, null, Modifier.size(18.dp), tint = DesignColors.Ink)
    }
}

@Composable
private fun Description(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DesignColors.SurfaceDim)
            .padding(DesignSpacing.Space16),
    ) {
        SelectionContainer {
            Text(
                text,
                style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = DesignColors.Ink),
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            )
        }
        if (overflows || expanded) {
            Text(
                stringResource(if (expanded) Res.string.show_less else Res.string.show_more),
                style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                modifier = Modifier.padding(top = DesignSpacing.Space8).clickable { expanded = !expanded },
            )
        }
    }
}

private fun LazyListScope.commentsSection(
    state: VideoDetailsState,
    details: YtVideoDetails,
    onOpenChannel: (url: String, title: String) -> Unit,
) {
    item("comments-header") {
        val header = state.header
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            Text(
                header?.countText ?: listOfNotNull(stringResource(Res.string.video_details_comments), details.commentsCount).joinToString(" "),
                style = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkStrong),
            )
            if (details.commentsContinuation == null) {
                Text(stringResource(Res.string.video_details_comments_off), style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary))
            }
            if (header != null && header.sort.size > 1) SortChips(header.sort) { chip -> chip.token?.let { state.reloadComments(it) } }
            if (header != null) Composer(state, header)
        }
    }
    items(state.comments.comments, key = { "c:" + it.id }) { comment ->
        Column {
            CommentRow(state, comment, onOpenChannel, indent = false)
            if (state.replyingTo == comment.id) ReplyComposer(state, comment)
            if (comment.repliesContinuation != null) RepliesToggle(state, comment)
            state.replies[comment.id]?.let { list ->
                list.comments.forEach { reply -> CommentRow(state, reply, onOpenChannel, indent = true) }
                ListFooter(list, indent = true, moreLabel = stringResource(Res.string.video_details_more_replies)) { state.loadMore(list) }
            }
        }
    }
    item("comments-footer") {
        val list = state.comments
        when {
            list.loading || (list.errorCode == null && list.continuation != null) -> Spinner(Modifier.heightIn(min = 72.dp))
            list.errorCode != null -> ListFooter(list, indent = false, moreLabel = null) { state.loadMore(list) }
            state.header != null && list.comments.isEmpty() -> Text(
                stringResource(Res.string.video_details_no_comments),
                style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(DesignSpacing.Space16),
            )
        }
    }
}

@Composable
private fun SortChips(sort: List<YtChip>, onSelect: (YtChip) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
        sort.forEach { chip ->
            FeedChip(chip.title, selected = chip.selected) { if (!chip.selected) onSelect(chip) }
        }
    }
}

@Composable
private fun Composer(state: VideoDetailsState, header: YtCommentPage) {
    if (header.createToken == null) {
        Text(stringResource(Res.string.video_details_sign_in_to_comment), style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary))
        return
    }
    val snackbar = LocalSnackbar.current
    val posted = stringResource(Res.string.video_details_posted)
    CommentField(
        placeholder = header.composerPlaceholder ?: stringResource(Res.string.video_details_comment_hint),
        posting = state.posting,
        errorCode = state.postErrorCode,
    ) { text, clear -> state.post(text) { clear(); snackbar(posted) } }
}

@Composable
private fun ReplyComposer(state: VideoDetailsState, parent: YtComment) {
    val snackbar = LocalSnackbar.current
    val posted = stringResource(Res.string.video_details_reply_posted)
    Box(Modifier.padding(start = DesignSpacing.Space48 + DesignSpacing.Space16, end = DesignSpacing.Space16)) {
        CommentField(
            placeholder = parent.replyPlaceholder ?: stringResource(Res.string.video_details_comment_hint),
            posting = state.replyPosting,
            errorCode = state.replyErrorCode,
            autoFocus = true,
        ) { text, clear -> state.postReply(parent, text) { clear(); snackbar(posted) } }
    }
}

/** A text box + send. [onSend] gets the trimmed text and a callback clearing the draft on success. */
@Composable
private fun CommentField(
    placeholder: String,
    posting: Boolean,
    errorCode: String?,
    autoFocus: Boolean = false,
    initial: String = "",
    onCancel: (() -> Unit)? = null,
    onSend: (text: String, clear: () -> Unit) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    val send = {
        val text = draft.trim()
        if (text.isNotEmpty()) onSend(text) { draft = "" }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppTextField(
            value = draft,
            onValueChange = { draft = it },
            label = placeholder,
            modifier = Modifier.weight(1f),
            singleLine = false,
            autoFocus = autoFocus,
            isError = errorCode != null,
            supportingText = errorCode?.let { errorMessage(it) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        if (posting) {
            CircularProgressIndicator(Modifier.padding(DesignSpacing.Space8).size(24.dp), strokeWidth = 2.dp)
        } else {
            onCancel?.let {
                IconButton(onClick = it, modifier = Modifier.a11yActionLabel(stringResource(Res.string.dialog_cancel))) {
                    Icon(Icons.Default.Close, null, tint = DesignColors.InkSecondary)
                }
            }
            IconButton(
                onClick = send,
                enabled = draft.isNotBlank() && draft.trim() != initial,
                modifier = Modifier.a11yActionLabel(stringResource(Res.string.video_details_post)),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, tint = DesignColors.Accent)
            }
        }
    }
}

@Composable
private fun CommentRow(
    state: VideoDetailsState,
    comment: YtComment,
    onOpenChannel: (url: String, title: String) -> Unit,
    indent: Boolean,
) {
    val author = comment.author
    val authorUrl = comment.authorChannelUrl
    val openAuthor = if (author != null && authorUrl != null) Modifier.clickable { onOpenChannel(authorUrl, author) } else Modifier
    Row(
        Modifier.fillMaxWidth().padding(
            start = if (indent) DesignSpacing.Space48 + DesignSpacing.Space16 else DesignSpacing.Space16,
            end = DesignSpacing.Space16,
            top = DesignSpacing.Space8,
            bottom = DesignSpacing.Space8,
        ),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
    ) {
        Box(openAuthor) { Avatar(comment.authorThumbs, if (indent) 24.dp else 32.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
            comment.pinned?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
                    Icon(Icons.Outlined.PushPin, null, Modifier.size(12.dp), tint = DesignColors.InkSecondary)
                    Text(it, style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = DesignColors.InkSecondary))
                }
            }
            Text(
                listOfNotNull(author, comment.published).joinToString(" · "),
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp,
                    fontWeight = if (comment.byCreator) FontWeight.Bold else FontWeight.Normal,
                    color = DesignColors.InkSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = openAuthor,
            )
            if (state.editingId == comment.id) {
                val snackbar = LocalSnackbar.current
                val saved = stringResource(Res.string.video_details_edited)
                CommentField(
                    placeholder = stringResource(Res.string.video_details_comment_hint),
                    posting = state.editPosting,
                    errorCode = state.editErrorCode,
                    autoFocus = true,
                    initial = state.textOf(comment),
                    onCancel = { state.editingId = null },
                ) { text, _ -> state.saveEdit(comment, text) { snackbar(saved) } }
            } else {
                SelectionContainer {
                    Text(state.textOf(comment), style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = DesignColors.Ink))
                }
                CommentActions(state, comment, canReply = !indent)
            }
        }
        if (comment.editToken != null || comment.deleteToken != null) OwnCommentMenu(state, comment)
    }
}

/** ⋮ on the viewer's own comments: Edit / Delete. */
@Composable
private fun OwnCommentMenu(state: VideoDetailsState, comment: YtComment) {
    var open by remember { mutableStateOf(false) }
    Box {
        ActionIcon(Icons.Default.MoreVert, stringResource(Res.string.cd_more), tint = DesignColors.InkSecondary) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (comment.editToken != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.video_details_edit)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = { open = false; state.startEdit(comment) },
                )
            }
            if (comment.deleteToken != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.video_details_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = { open = false; state.pendingDelete = comment },
                )
            }
        }
    }
}

@Composable
private fun DeleteCommentConfirm(state: VideoDetailsState) {
    val comment = state.pendingDelete ?: return
    val snackbar = LocalSnackbar.current
    val deleted = stringResource(Res.string.video_details_deleted)
    AlertDialog(
        onDismissRequest = { state.pendingDelete = null },
        title = { Text(stringResource(Res.string.video_details_delete_title)) },
        text = { Text(stringResource(Res.string.video_details_delete_message)) },
        confirmButton = {
            TextButton(onClick = {
                state.pendingDelete = null
                state.delete(comment) { snackbar(deleted) }
            }) { Text(stringResource(Res.string.video_details_delete), color = DesignColors.Accent) }
        },
        dismissButton = { DialogSecondaryButton(stringResource(Res.string.dialog_cancel)) { state.pendingDelete = null } },
    )
}

/** Like (count) · dislike — toggles when signed in, else just the count — · creator heart · Reply
 *  (signed in, top level). */
@Composable
private fun CommentActions(state: VideoDetailsState, comment: YtComment, canReply: Boolean) {
    val rating = state.ratingOf(comment)
    val liked = rating == YtVideoDetails.LIKE
    val disliked = rating == YtVideoDetails.DISLIKE
    val canLike = comment.likeToken != null && comment.unlikeToken != null
    val canDislike = comment.dislikeToken != null && comment.undislikeToken != null
    val count = if (liked) comment.likesLiked ?: comment.likes else comment.likes
    val replyable = canReply && comment.replyToken != null
    if (!canLike && !canDislike && count == null && !comment.hearted && !replyable) return
    val muted = DesignColors.InkSecondary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
        if (canLike || count != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .then(
                        if (canLike) {
                            Modifier.toggleable(value = liked, role = Role.Checkbox) { state.rateComment(comment, YtVideoDetails.LIKE) }
                        } else {
                            Modifier
                        },
                    )
                    .a11yActionLabel(listOfNotNull(stringResource(Res.string.video_details_like), count).joinToString(", "))
                    .padding(vertical = DesignSpacing.Space4, horizontal = if (canLike) DesignSpacing.Space4 else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
            ) {
                Icon(
                    if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    null,
                    Modifier.size(14.dp),
                    tint = if (liked) DesignColors.Ink else muted,
                )
                count?.let {
                    Text(it, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = muted), modifier = Modifier.a11ySilent())
                }
            }
        }
        if (canDislike) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .toggleable(value = disliked, role = Role.Checkbox) { state.rateComment(comment, YtVideoDetails.DISLIKE) }
                    .a11yActionLabel(stringResource(Res.string.video_details_dislike))
                    .padding(DesignSpacing.Space4),
            ) {
                Icon(
                    if (disliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    null,
                    Modifier.size(14.dp),
                    tint = if (disliked) DesignColors.Ink else muted,
                )
            }
        }
        if (comment.hearted) Icon(Icons.Filled.Favorite, null, Modifier.padding(horizontal = DesignSpacing.Space4).size(14.dp), tint = DesignColors.Accent)
        if (replyable) {
            Text(
                stringResource(Res.string.video_details_reply),
                style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { state.openReply(comment) }
                    .padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space4),
            )
        }
    }
}

@Composable
private fun RepliesToggle(state: VideoDetailsState, comment: YtComment) {
    val open = state.replies.containsKey(comment.id)
    Text(
        if (open) stringResource(Res.string.video_details_hide_replies) else stringResource(Res.string.video_details_replies, comment.replyCount.orEmpty()),
        style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
        modifier = Modifier
            .padding(start = DesignSpacing.Space48 + DesignSpacing.Space16)
            .clip(RoundedCornerShape(999.dp))
            .clickable { state.toggleReplies(comment) }
            .padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space4),
    )
}

/** Under a list: spinner while a page loads, the error with a retry, or a "more" action. */
@Composable
private fun ListFooter(list: CommentList, indent: Boolean, moreLabel: String?, onMore: () -> Unit) {
    val start = if (indent) DesignSpacing.Space48 + DesignSpacing.Space16 else DesignSpacing.Space16
    when {
        list.loading -> Spinner(Modifier.padding(start = start).heightIn(min = 48.dp))
        list.errorCode != null -> Row(Modifier.padding(start = start), verticalAlignment = Alignment.CenterVertically) {
            Text(errorMessage(list.errorCode!!), style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
            TextButton(onClick = onMore) { Text(stringResource(Res.string.retry)) }
        }
        list.continuation != null && moreLabel != null -> TextButton(onClick = onMore, modifier = Modifier.padding(start = start)) {
            Text(moreLabel, color = DesignColors.Accent)
        }
    }
}

@Composable
private fun Avatar(thumbs: List<io.ymusic.dynamicengine.yt.YtThumb>, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        ThumbImage(thumbs, CircleShape) { ThumbPlaceholder(Icons.Outlined.Person, size / 2) }
    }
}

@Composable
private fun Spinner(modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}
