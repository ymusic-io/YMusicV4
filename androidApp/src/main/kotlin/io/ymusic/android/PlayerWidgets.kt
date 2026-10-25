package io.ymusic.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import io.ymusic.imageloader.ImageLoader
import io.ymusic.player.isPlaying
import io.ymusic.ui.AppGraph
import io.ymusic.ui.artImageRequest
import kmpcommon.WorkerThread
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import androidx.media3.session.R as Media3R

internal const val ACTION_WIDGET_PLAY = "io.ymusic.android.widget.PLAY"
internal const val ACTION_WIDGET_PAUSE = "io.ymusic.android.widget.PAUSE"
internal const val ACTION_WIDGET_NEXT = "io.ymusic.android.widget.NEXT"
internal const val ACTION_WIDGET_PREV = "io.ymusic.android.widget.PREV"
internal val WIDGET_ACTIONS = setOf(ACTION_WIDGET_PLAY, ACTION_WIDGET_PAUSE, ACTION_WIDGET_NEXT, ACTION_WIDGET_PREV)

abstract class PlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refresh(context)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = refresh(context)

    private fun refresh(context: Context) {
        val app = context.applicationContext as YMusicApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.widgets.refresh()
            } finally {
                try {
                    pending.finish()
                } catch (e: IllegalStateException) {
                    // Some ROMs finish a goAsync broadcast themselves before the receiver does.
                    app.rootLogger.fork("widget").log(Logger.Level.WARN, "widget broadcast was already finished", e)
                }
            }
        }
    }
}

class SmallPlayerWidget : PlayerWidgetProvider()

class CompactPlayerWidget : PlayerWidgetProvider()

class LargePlayerWidget : PlayerWidgetProvider()

class SquarePlayerWidget : PlayerWidgetProvider()

private enum class WidgetKind(val provider: Class<out AppWidgetProvider>, val layout: Int) {
    SMALL(SmallPlayerWidget::class.java, R.layout.widget_small),
    COMPACT(CompactPlayerWidget::class.java, R.layout.widget_compact),
    LARGE(LargePlayerWidget::class.java, R.layout.widget_large),
    SQUARE(SquarePlayerWidget::class.java, R.layout.widget_square),
}

/** A null track on [WidgetState] means nothing was ever played. */
internal data class WidgetTrack(val title: String, val subtitle: String?, val artUrl: String?)

internal data class WidgetState(val track: WidgetTrack?, val playing: Boolean)

private data class WidgetPalette(val card: Int, val title: Int, val subtitle: Int, val playBg: Int, val playIcon: Int)

/** Everything the layouts take from one cover, derived once per track. */
private class WidgetArt(
    val url: String,
    val backdrop: Bitmap,
    val softBackdrop: Bitmap,
    val panel: Bitmap,
    val cover: Bitmap,
    val palette: WidgetPalette,
)

/**
 * The home-screen player widgets. Live while the graph is up ([attach]): the current item, or with an
 * empty queue the newest "Play again" session, whose Play resumes it (PlaybackService). Every render
 * with a track is snapshotted to disk so a widget refreshed in a dead process ([refresh]) still shows
 * the last song, paused.
 */
class PlayerWidgets(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: Logger,
) {
    private val manager = AppWidgetManager.getInstance(context)
    private val snapshots = WidgetSnapshotStore(context)
    private val mutex = Mutex()

    // All guarded by [mutex].
    private var live: WidgetState? = null
    private var imageLoader: ImageLoader? = null
    private var artTriedFor: String? = null
    private var art: WidgetArt? = null
    private var snapshotLoaded = false
    private var snapshotTrack: WidgetTrack? = null
    private var persisted: Pair<WidgetTrack, String?>? = null

    fun attach(graph: AppGraph) {
        scope.launch {
            states(graph).collectLatest { state ->
                mutex.withLock {
                    imageLoader = graph.imageLoader
                    live = state
                    render(state)
                }
            }
        }
    }

    suspend fun refresh() = mutex.withLock {
        val state = live ?: run {
            loadSnapshot()
            WidgetState(snapshotTrack, playing = false)
        }
        render(state)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun states(graph: AppGraph): Flow<WidgetState> {
        val player = graph.player
        val store = graph.playHistory?.playAgain
        val lastPlayed: Flow<WidgetTrack?> = if (store == null) {
            flowOf(null)
        } else {
            store.changes.onStart { emit(Unit) }.map {
                store.sessions().firstOrNull()?.let { WidgetTrack(it.title, it.subtitle, it.thumbnailUrl) }
            }
        }
        val fallback = player.queue.map { it.currentItem == null }.distinctUntilChanged()
            .flatMapLatest { empty -> if (empty) lastPlayed else flowOf(null) }
        return combine(player.status, player.queue, fallback) { status, queue, last ->
            val track = queue.currentItem?.let { WidgetTrack(it.displayTitle, it.displaySubtitle, it.displayThumbnailUrl) }
            WidgetState(track ?: last, isPlaying(status, queue))
        }.distinctUntilChanged()
    }

    private suspend fun loadSnapshot() {
        if (snapshotLoaded) return
        snapshotLoaded = true
        val (track, saved) = withContext(Dispatchers.IO) { snapshots.read() } ?: return
        snapshotTrack = track
        if (art == null && saved != null) {
            art = saved
            artTriedFor = saved.url
        }
        persisted = track to saved?.url
    }

    private suspend fun render(state: WidgetState) = withContext(Dispatchers.Default) {
        val ids = WidgetKind.entries.associateWith { manager.getAppWidgetIds(ComponentName(context, it.provider)) }
        if (ids.values.all { it.isEmpty() }) return@withContext
        val url = state.track?.artUrl
        val loader = imageLoader
        if (url != null && url != artTriedFor && loader != null) {
            artTriedFor = url
            art = loadArt(url, loader)
        }
        val shown = art?.takeIf { it.url == url }
        val track = state.track
        if (track != null && persisted != (track to shown?.url)) {
            persisted = track to shown?.url
            withContext(Dispatchers.IO) { snapshots.write(track, shown) }
        }
        for ((kind, kindIds) in ids) {
            for (id in kindIds) manager.updateAppWidget(id, build(kind, id, state, shown))
        }
    }

    private suspend fun loadArt(url: String, loader: ImageLoader): WidgetArt? = try {
        val source = loader.load(artImageRequest(url, ART_SOURCE_PX, ART_SOURCE_PX), cacheInMemory = false).asAndroidBitmap()
        deriveArt(url, source)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.log(Logger.Level.WARN, "widget artwork failed for $url", e)
        null
    }

    private fun build(kind: WidgetKind, id: Int, state: WidgetState, art: WidgetArt?): RemoteViews {
        val views = RemoteViews(context.packageName, kind.layout)
        val track = state.track
        views.setTextViewText(R.id.widget_title, track?.title ?: context.getString(R.string.widget_nothing_playing))
        val subtitle = if (track == null) context.getString(R.string.widget_open_to_start) else track.subtitle
        views.setTextViewText(R.id.widget_subtitle, subtitle.orEmpty())
        views.setViewVisibility(R.id.widget_subtitle, if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE)

        // Compact's play/pause is a bare icon; the others wrap it in a circle (widget_play_button).
        val playTarget = if (kind == WidgetKind.COMPACT) R.id.widget_play_icon else R.id.widget_play
        views.setImageViewResource(
            R.id.widget_play_icon,
            if (state.playing) Media3R.drawable.media3_icon_pause else Media3R.drawable.media3_icon_play,
        )
        views.setContentDescription(
            playTarget,
            context.getString(if (state.playing) R.string.notification_pause else R.string.notification_play),
        )
        views.setOnClickPendingIntent(android.R.id.background, openApp())
        // What the shown icon says, never a toggle: a repeated tap on a stale Pause stays paused.
        views.setOnClickPendingIntent(playTarget, command(if (state.playing) ACTION_WIDGET_PAUSE else ACTION_WIDGET_PLAY))
        views.setOnClickPendingIntent(R.id.widget_next, command(ACTION_WIDGET_NEXT))
        if (kind != WidgetKind.SMALL) views.setOnClickPendingIntent(R.id.widget_prev, command(ACTION_WIDGET_PREV))

        val minWidthDp = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        when (kind) {
            WidgetKind.SMALL, WidgetKind.COMPACT -> {
                if (art != null) {
                    views.setImageViewBitmap(R.id.widget_backdrop, art.softBackdrop)
                } else {
                    views.setImageViewResource(R.id.widget_backdrop, 0)
                }
                if (kind == WidgetKind.COMPACT) {
                    views.setViewVisibility(R.id.widget_prev, visibleIf(minWidthDp == 0 || minWidthDp >= COMPACT_PREV_MIN_DP))
                }
            }
            WidgetKind.LARGE, WidgetKind.SQUARE -> {
                val palette = art?.palette ?: FALLBACK_PALETTE
                views.setInt(R.id.widget_card, "setColorFilter", palette.card)
                views.setTextColor(R.id.widget_title, palette.title)
                views.setTextColor(R.id.widget_subtitle, palette.subtitle)
                views.setInt(R.id.widget_prev, "setColorFilter", palette.title)
                views.setInt(R.id.widget_next, "setColorFilter", palette.title)
                views.setInt(R.id.widget_play_bg, "setColorFilter", palette.playBg)
                views.setInt(R.id.widget_play_icon, "setColorFilter", palette.playIcon)
                if (kind == WidgetKind.SQUARE) {
                    if (art != null) {
                        views.setImageViewBitmap(R.id.widget_backdrop, art.backdrop)
                    } else {
                        views.setImageViewResource(R.id.widget_backdrop, 0)
                    }
                }
                val bitmap = if (kind == WidgetKind.LARGE) art?.panel else art?.cover
                val roomForArt = kind == WidgetKind.SQUARE || minWidthDp == 0 || minWidthDp >= LARGE_ART_MIN_DP
                val showArt = bitmap != null && roomForArt
                if (showArt) views.setImageViewBitmap(R.id.widget_art, bitmap)
                views.setViewVisibility(R.id.widget_art, visibleIf(showArt))
                if (kind == WidgetKind.LARGE) {
                    views.setImageViewBitmap(R.id.widget_art_fade, FADE_EDGE)
                    views.setInt(R.id.widget_art_fade, "setColorFilter", palette.card)
                    views.setViewVisibility(R.id.widget_art_fade, visibleIf(showArt))
                    views.setViewVisibility(R.id.widget_art_space, visibleIf(showArt))
                }
            }
        }
        return views
    }

    private fun visibleIf(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** A foreground-service start: a widget tap may be what revives a dead process. */
    private fun command(action: String): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java).setAction(action)
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private companion object {
        const val ART_SOURCE_PX = 384
        const val COMPACT_PREV_MIN_DP = 250
        const val LARGE_ART_MIN_DP = 260
        val FALLBACK_PALETTE = WidgetPalette(
            card = 0xFF5A2E2A.toInt(),
            title = 0xFFFFE9DF.toInt(),
            subtitle = 0xFFF0B9A6.toInt(),
            playBg = 0xFFFFB59A.toInt(),
            playIcon = 0xFF5A2E2A.toInt(),
        )
    }
}

private const val BACKDROP_PX = 64
private const val BLUR_RADIUS = 4
private const val BLUR_PASSES = 3
private const val SOFT_BACKDROP_PX = 128
private const val SOFT_BLUR_RADIUS = 3
private const val SOFT_BLUR_PASSES = 2
private const val BACKDROP_DIM = 0.58f
private const val PANEL_W = 288
private const val PANEL_H = 320
private const val FADE_EDGE_PX = 128

/**
 * Opaque white at the leading edge to clear, tinted to the card. Smoothstep, not a linear shape
 * gradient: a linear ramp's abrupt end reads as a seam.
 */
private val FADE_EDGE: Bitmap by lazy {
    val pixels = IntArray(FADE_EDGE_PX) { x ->
        val t = x / (FADE_EDGE_PX - 1f)
        val eased = t * t * (3 - 2 * t)
        Color.argb(((1 - eased) * 255).toInt(), 255, 255, 255)
    }
    Bitmap.createBitmap(pixels, FADE_EDGE_PX, 1, Bitmap.Config.ARGB_8888)
}
private const val COVER_PX = 192
private const val COVER_RADIUS_PX = 32f

private fun deriveArt(url: String, source: Bitmap): WidgetArt {
    // Palette and getPixels need CPU-readable pixels.
    val src = if (Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        source
    }
    return WidgetArt(
        url = url,
        backdrop = blurredBackdrop(src, BACKDROP_PX, BLUR_RADIUS, BLUR_PASSES),
        softBackdrop = blurredBackdrop(src, SOFT_BACKDROP_PX, SOFT_BLUR_RADIUS, SOFT_BLUR_PASSES),
        panel = centerCrop(src, PANEL_W, PANEL_H),
        cover = roundedCover(src),
        palette = Palette.from(src).generate().toWidgetPalette(),
    )
}

private fun Palette.toWidgetPalette(): WidgetPalette {
    val base = (darkMutedSwatch ?: darkVibrantSwatch ?: dominantSwatch)?.rgb ?: 0xFF5A2E2A.toInt()
    var card = base
    var darken = 0f
    while (ColorUtils.calculateLuminance(card) > CARD_MAX_LUMINANCE && darken < 1f) {
        darken += 0.1f
        card = ColorUtils.blendARGB(base, Color.BLACK, darken)
    }
    val accent = (lightVibrantSwatch ?: vibrantSwatch ?: lightMutedSwatch)?.rgb ?: 0xFFFFB59A.toInt()
    return WidgetPalette(
        card = card,
        title = ColorUtils.blendARGB(Color.WHITE, accent, 0.12f),
        subtitle = lightenUntilLegible(ColorUtils.blendARGB(Color.WHITE, accent, 0.45f), card, 4.5),
        playBg = lightenUntilLegible(accent, card, 3.0),
        playIcon = card,
    )
}

private const val CARD_MAX_LUMINANCE = 0.06

private fun lightenUntilLegible(color: Int, background: Int, minContrast: Double): Int {
    var out = color
    var lighten = 0f
    while (ColorUtils.calculateContrast(out, background) < minContrast && lighten < 1f) {
        lighten += 0.1f
        out = ColorUtils.blendARGB(color, Color.WHITE, lighten)
    }
    return out
}

private fun centerCrop(src: Bitmap, width: Int, height: Int): Bitmap {
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
    val cropW = (width / scale).toInt()
    val cropH = (height / scale).toInt()
    val left = (src.width - cropW) / 2
    val top = (src.height - cropH) / 2
    Canvas(out).drawBitmap(
        src,
        Rect(left, top, left + cropW, top + cropH),
        Rect(0, 0, width, height),
        Paint(Paint.FILTER_BITMAP_FLAG),
    )
    return out
}

/** RemoteViews can't blur or dim, so both are baked: a small box-blurred copy the launcher scales up. */
private fun blurredBackdrop(src: Bitmap, size: Int, radius: Int, passes: Int): Bitmap {
    val small = centerCrop(src, size, size)
    val pixels = IntArray(size * size)
    small.getPixels(pixels, 0, size, 0, 0, size, size)
    val scratch = IntArray(pixels.size)
    repeat(passes) {
        boxBlurPass(pixels, scratch, size, size, radius, horizontal = true)
        boxBlurPass(scratch, pixels, size, size, radius, horizontal = false)
    }
    for (i in pixels.indices) {
        val color = pixels[i]
        pixels[i] = Color.rgb(
            (Color.red(color) * BACKDROP_DIM).toInt(),
            (Color.green(color) * BACKDROP_DIM).toInt(),
            (Color.blue(color) * BACKDROP_DIM).toInt(),
        )
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun boxBlurPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val window = 2 * radius + 1
    for (line in 0 until lines) {
        fun pixel(index: Int): Int {
            val clamped = index.coerceIn(0, length - 1)
            return src[if (horizontal) line * width + clamped else clamped * width + line]
        }
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val color = pixel(offset)
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
        }
        for (index in 0 until length) {
            dst[if (horizontal) line * width + index else index * width + line] =
                Color.rgb(red / window, green / window, blue / window)
            val leaving = pixel(index - radius)
            val entering = pixel(index + radius + 1)
            red += Color.red(entering) - Color.red(leaving)
            green += Color.green(entering) - Color.green(leaving)
            blue += Color.blue(entering) - Color.blue(leaving)
        }
    }
}

/** RemoteViews can't clip a child to rounded corners, so the Square's cover is pre-rounded. */
private fun roundedCover(src: Bitmap): Bitmap {
    val square = centerCrop(src, COVER_PX, COVER_PX)
    val out = Bitmap.createBitmap(COVER_PX, COVER_PX, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    Canvas(out).drawRoundRect(RectF(0f, 0f, COVER_PX.toFloat(), COVER_PX.toFloat()), COVER_RADIUS_PX, COVER_RADIUS_PX, paint)
    return out
}

/** The last rendered track and its derived art, for a widget refreshed before the graph is up. */
private class WidgetSnapshotStore(context: Context) {
    private val prefs = context.getSharedPreferences("player_widget", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "widget")

    @WorkerThread
    fun write(track: WidgetTrack, art: WidgetArt?) {
        if (art != null) {
            dir.mkdirs()
            writePng(BACKDROP_FILE, art.backdrop)
            writePng(SOFT_BACKDROP_FILE, art.softBackdrop)
            writePng(PANEL_FILE, art.panel)
            writePng(COVER_FILE, art.cover)
        }
        prefs.edit().apply {
            putString(KEY_TITLE, track.title)
            putString(KEY_SUBTITLE, track.subtitle)
            putString(KEY_ART_URL, track.artUrl)
            putString(KEY_ART_FOR, art?.url)
            art?.palette?.let { palette ->
                putInt(KEY_CARD, palette.card)
                putInt(KEY_TITLE_COLOR, palette.title)
                putInt(KEY_SUBTITLE_COLOR, palette.subtitle)
                putInt(KEY_PLAY_BG, palette.playBg)
                putInt(KEY_PLAY_ICON, palette.playIcon)
            }
        }.commit()
    }

    @WorkerThread
    fun read(): Pair<WidgetTrack, WidgetArt?>? {
        val title = prefs.getString(KEY_TITLE, null) ?: return null
        val track = WidgetTrack(title, prefs.getString(KEY_SUBTITLE, null), prefs.getString(KEY_ART_URL, null))
        val artFor = prefs.getString(KEY_ART_FOR, null)?.takeIf { it == track.artUrl } ?: return track to null
        val backdrop = readPng(BACKDROP_FILE)
        val softBackdrop = readPng(SOFT_BACKDROP_FILE)
        val panel = readPng(PANEL_FILE)
        val cover = readPng(COVER_FILE)
        if (backdrop == null || softBackdrop == null || panel == null || cover == null) return track to null
        val palette = WidgetPalette(
            card = prefs.getInt(KEY_CARD, 0),
            title = prefs.getInt(KEY_TITLE_COLOR, 0),
            subtitle = prefs.getInt(KEY_SUBTITLE_COLOR, 0),
            playBg = prefs.getInt(KEY_PLAY_BG, 0),
            playIcon = prefs.getInt(KEY_PLAY_ICON, 0),
        )
        return track to WidgetArt(artFor, backdrop, softBackdrop, panel, cover, palette)
    }

    private fun writePng(name: String, bitmap: Bitmap) {
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun readPng(name: String): Bitmap? = BitmapFactory.decodeFile(File(dir, name).path)

    private companion object {
        const val BACKDROP_FILE = "backdrop.png"
        const val SOFT_BACKDROP_FILE = "soft_backdrop.png"
        const val PANEL_FILE = "panel.png"
        const val COVER_FILE = "cover.png"
        const val KEY_TITLE = "title"
        const val KEY_SUBTITLE = "subtitle"
        const val KEY_ART_URL = "art_url"
        const val KEY_ART_FOR = "art_for"
        const val KEY_CARD = "card"
        const val KEY_TITLE_COLOR = "title_color"
        const val KEY_SUBTITLE_COLOR = "subtitle_color"
        const val KEY_PLAY_BG = "play_bg"
        const val KEY_PLAY_ICON = "play_icon"
    }
}
