package io.ymusic.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.ymusic.download.DownloadService
import io.ymusic.download.DownloadState
import io.ymusic.download.DownloadTask
import io.ymusic.download.DownloadTasksAggregator
import io.ymusic.imageloader.ImageLoader
import io.ymusic.ui.artImageRequest
import io.ymusic.ui.downloadErrorMessage
import kmpcommon.log.Logger
import kmpcommon.log.debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Foreground host for active downloads — the dataSync twin of [PlaybackService]'s pattern:
 * [YMusicApplication] (re)starts it whenever work lands in the queue, and it exits by itself when
 * the queue drains. The download engine itself lives on the app graph and neither starts nor
 * stops with this service — the service only buys the process foreground standing while work runs.
 *
 * Notification model — the service OWNS cards only for tasks it has seen ACTIVE (isActive
 * includes queued), all in one group:
 *  - An active task gets an ONGOING card (title, thumbnail, status in the header, progress),
 *    updated only when its rendered content changes — an unchanged task never touches the shade.
 *  - When a task leaves the active set (finished / failed / paused) its card morphs ONCE into a
 *    dismissible card on the same id, and the service FORGETS it — from then on the card is the
 *    user's: swiped stays swiped, and only re-entering the active set (resume, retry) creates a
 *    card again. Historical tasks are never posted at all, so a service restart cannot resurrect
 *    anything by construction. Canceled/removed tasks take their card along.
 *  - The group summary is the foreground notification while working. On drain it DETACHES and
 *    morphs into a roll-up of what this run produced — canceling a group summary cancels its
 *    children (AOSP group semantics), so it is never removed while morphs may sit in the shade.
 *
 * Android 15 caps dataSync foreground time (~6h/day): [onTimeout] demotes instead of crashing —
 * downloads continue while the process lives, and the queue is store-persisted either way.
 */
class DownloadForegroundService : Service() {

    // Set in onCreate — getApplication() is null until the service is attached.
    private var log: Logger? = null

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> log?.fork("uncaught")?.captureError(e) },
    )
    // notify/cancel are binder calls into the notification service, which can block for seconds on a
    // slow system (an ANR when made from main). One lane keeps each card's updates in order; it is not
    // cancelled with the service, so the posts queued by finish() still land after stopSelf().
    private val notifyLane = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1) +
            CoroutineExceptionHandler { _, e ->
                // The system server died; this process goes down with it, so it's not a failure of ours.
                if (e.cause is android.os.DeadSystemException) {
                    log?.fork("notify")?.log(Logger.Level.WARN, "system died while posting a notification", e)
                } else {
                    log?.fork("notify")?.captureError(e)
                }
            },
    )
    private var downloads: DownloadService? = null
    private var imageLoader: ImageLoader? = null
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Cards this run owns: active-task id → last rendered signature. Leaving the active set
     *  removes the entry (after the one dismissible morph). */
    private val owned = HashMap<String, Int>()

    /** Loaded thumbnails by owned-task id (null = load in flight). */
    private val icons = HashMap<String, Bitmap?>()

    /** What this run produced, for the drained-queue summary. */
    private var completedRun = 0
    private var failedRun = 0
    private var pausedRun = 0

    private var lastSummarySignature = Int.MIN_VALUE
    private var summaryPostedAt = 0L
    private var summaryJob: Job? = null
    private var pendingSummary: List<DownloadTask>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        log = (application as YMusicApplication).rootLogger.fork("download-service")
        createChannel()
        serviceScope.launch {
            val graph = (application as YMusicApplication).graphAsync().await()
            val downloads = graph.downloads ?: run { stopSelf(); return@launch }
            this@DownloadForegroundService.downloads = downloads
            imageLoader = graph.imageLoader
            DownloadTasksAggregator({ downloads.snapshot() }, downloads.changes).tasks
                .conflate()
                .collect { tasks ->
                    render(tasks)
                    // Progress events arrive per chunk; conflate + this pace = ≤2 renders/s.
                    delay(UPDATE_THROTTLE_MS)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) {
            // Arrives via the notification action on an already-started service — no foreground
            // contract to honor. The collector sees the states flip and winds the service down.
            downloads?.pauseAll()
            return START_NOT_STICKY
        }
        // The startForegroundService contract: enter foreground now; the collector replaces this
        // placeholder with real progress (or finishes) as soon as the graph is up. Every task turning
        // active restarts the service; once foreground there is no contract left (AMS drops fgRequired).
        if (!foreground) {
            startForeground(SUMMARY_NOTIFICATION_ID, placeholderSummary())
            summaryPostedAt = SystemClock.uptimeMillis()
            foreground = true
        }
        return START_NOT_STICKY
    }

    /** Android 15's dataSync time cap: demote, never crash — the queue survives in the store. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelSummary()
        stopForegroundSafely(ServiceCompat.STOP_FOREGROUND_DETACH, log)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        // Teardown steps are independent: one failing must not skip the rest (a held wake lock
        // outliving the service is the costliest leak here).
        runCatching { serviceScope.cancel() }
        releaseLocks()
        icons.clear()
        super.onDestroy()
    }

    private suspend fun render(tasks: List<DownloadTask>) {
        val byId = tasks.associateBy { it.id }

        // Owned cards whose task left the active set: one dismissible morph, then forget — the
        // card is the user's now. A canceled/removed task takes its card along instead.
        owned.keys.iterator().let { iter ->
            while (iter.hasNext()) {
                val id = iter.next()
                val task = byId[id]
                when {
                    task == null || task.state == DownloadState.CANCELED -> {
                        cancelCard(childId(id))
                        icons.remove(id)
                        iter.remove()
                    }
                    !task.state.isActive -> {
                        post(childId(id), taskCard(task))
                        when (task.state) {
                            DownloadState.COMPLETED -> completedRun++
                            DownloadState.FAILED -> failedRun++
                            DownloadState.PAUSED -> pausedRun++
                            else -> Unit
                        }
                        icons.remove(id)
                        iter.remove()
                    }
                }
            }
        }

        // Active tasks: own them; post on first sight, re-post only when the rendered content
        // (state, bar position, icon arrival) actually changed.
        for (task in tasks) {
            if (!task.state.isActive) continue
            ensureIcon(task)
            val signature = signature(task)
            if (owned[task.id] != signature) {
                post(childId(task.id), taskCard(task))
                owned[task.id] = signature
            }
        }

        if (owned.isEmpty()) {
            finish()
        } else {
            holdLocks()
            // The summary is content-gated the same way as the cards.
            val summarySignature = owned.size * 100_000 + aggregatePermille(tasks)
            if (!foreground || summarySignature != lastSummarySignature) {
                scheduleSummary(tasks)
                lastSummarySignature = summarySignature
            }
        }
    }

    /** Posts the working summary at most once per [SUMMARY_MIN_INTERVAL_MS], always the latest. */
    private fun scheduleSummary(tasks: List<DownloadTask>) {
        pendingSummary = tasks
        if (summaryJob?.isActive == true) return
        summaryJob = serviceScope.launch {
            delay(summaryPostedAt + SUMMARY_MIN_INTERVAL_MS - SystemClock.uptimeMillis())
            val latest = pendingSummary ?: return@launch
            pendingSummary = null
            startForeground(SUMMARY_NOTIFICATION_ID, workingSummary(latest))
            summaryPostedAt = SystemClock.uptimeMillis()
            foreground = true
        }
    }

    private fun cancelSummary() {
        summaryJob?.cancel()
        pendingSummary = null
    }

    /** Queue drained: drop the foreground contract but NEVER cancel the summary while this run's
     *  morphed cards may remain — canceling a group summary cancels its children (AOSP group
     *  semantics). Morph it into the run's roll-up instead; remove it when this run produced
     *  nothing dismissible. */
    private fun finish() {
        cancelSummary()
        releaseLocks()
        if (completedRun + failedRun + pausedRun > 0) {
            stopForegroundSafely(ServiceCompat.STOP_FOREGROUND_DETACH, log)
            post(SUMMARY_NOTIFICATION_ID, idleSummary())
        } else {
            stopForegroundSafely(ServiceCompat.STOP_FOREGROUND_REMOVE, log)
        }
        foreground = false
        stopSelf()
    }

    private fun post(id: Int, notification: Notification) {
        notifyLane.launch { NotificationManagerCompat.from(applicationContext).notify(id, notification) }
    }

    private fun cancelCard(id: Int) {
        notifyLane.launch { NotificationManagerCompat.from(applicationContext).cancel(id) }
    }

    // ---- per-task cards ----

    /** Stable per-task notification id, so every state morphs the same card in place. */
    private fun childId(taskId: String): Int = CHILD_ID_BASE + (taskId.hashCode() and 0x7FFFFF)

    /** What an owned card renders, packed for change detection: state + bar permille + icon
     *  presence (owned cards are ongoing, so an icon-driven re-post is invisible). */
    private fun signature(task: DownloadTask): Int {
        val permille = ((task.progress ?: -1f) * 1000).toInt() + 1000
        val icon = if (icons[task.id] != null) 1 else 0
        return task.state.ordinal * 100_000 + permille * 10 + icon
    }

    private suspend fun taskCard(task: DownloadTask): Notification {
        val status = statusLine(task)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                when (task.state) {
                    DownloadState.COMPLETED -> android.R.drawable.stat_sys_download_done
                    DownloadState.FAILED -> android.R.drawable.stat_notify_error
                    else -> android.R.drawable.stat_sys_download
                },
            )
            .setContentTitle(task.title)
            // The platform template gives the progress bar the content-text row, so the status
            // rides the HEADER (subText) where it is always visible; contentText still covers
            // the barless states (finished/failed).
            .setContentText(status)
            .setSubText(status)
            .setLargeIcon(icons[task.id])
            .setGroup(GROUP_KEY)
            // Non-dismissible while in flight (isActive includes queued); a card that left the
            // active set is the user's to swipe.
            .setOngoing(task.state.isActive)
            .setAutoCancel(!task.state.isActive)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent)
            .apply {
                val progress = task.progress
                when {
                    task.state == DownloadState.DOWNLOADING && progress != null ->
                        setProgress(1000, (progress * 1000).toInt(), false)
                    task.state == DownloadState.DOWNLOADING ||
                        task.state == DownloadState.PREPARING ||
                        task.state == DownloadState.PROCESSING ->
                        setProgress(0, 0, true)
                    // A paused card keeps its bar frozen where it stopped.
                    task.state == DownloadState.PAUSED && progress != null ->
                        setProgress(1000, (progress * 1000).toInt(), false)
                    // QUEUED / terminal: no bar.
                }
            }
            .build()
    }

    private suspend fun statusLine(task: DownloadTask): String = when (task.state) {
        DownloadState.QUEUED -> getString(R.string.download_state_queued)
        DownloadState.PREPARING -> getString(R.string.download_state_preparing)
        DownloadState.DOWNLOADING -> getString(R.string.download_state_downloading)
        DownloadState.PROCESSING -> getString(R.string.download_state_processing)
        DownloadState.PAUSED -> getString(R.string.download_state_paused)
        DownloadState.COMPLETED -> getString(R.string.notification_download_finished)
        DownloadState.FAILED -> downloadErrorMessage(task)
        DownloadState.CANCELED -> "" // never rendered
    }

    /** Kick a one-time thumbnail load for an owned card; the next render tick picks it up via the
     *  signature (icon bit) — no direct posting from the load callback. */
    private fun ensureIcon(task: DownloadTask) {
        val url = task.thumbnailUrl ?: return
        val loader = imageLoader ?: return
        if (icons.containsKey(task.id)) return
        icons[task.id] = null
        serviceScope.launch {
            icons[task.id] = try {
                loader.load(artImageRequest(url, THUMB_PX, THUMB_PX), cacheInMemory = false).asAndroidBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log?.debug { "download icon load failed for $url: $e" }
                null
            }
        }
    }

    // ---- the group summary (doubles as the foreground notification) ----

    private fun aggregatePermille(tasks: List<DownloadTask>): Int {
        val sized = tasks.filter { it.state.isActive && it.totalBytes > 0 }
        val total = sized.sumOf { it.totalBytes }
        return if (total > 0) (sized.sumOf { it.downloadedBytes } * 1000 / total).toInt() else -1
    }

    private fun workingSummary(tasks: List<DownloadTask>): Notification {
        val active = tasks.filter { it.state.isActive }
        val permille = aggregatePermille(tasks)
        return summaryBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_downloads_multi, active.size))
            .setOngoing(true)
            .apply {
                if (permille >= 0) setProgress(1000, permille, false)
                else setProgress(0, 0, true)
            }
            .addAction(0, getString(R.string.downloads_pause_all), pauseAllIntent)
            .build()
    }

    private fun placeholderSummary(): Notification = summaryBuilder()
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(getString(R.string.notification_downloads_running))
        .setOngoing(true)
        .setProgress(0, 0, true)
        .build()

    /** The drained-queue roll-up of what THIS run produced ("3 finished · 1 failed · 2 paused").
     *  Dismissing it clears the whole group — the standard "clear finished" affordance. */
    private fun idleSummary(): Notification {
        val text = listOfNotNull(
            completedRun.takeIf { it > 0 }?.let { getString(R.string.downloads_summary_finished, it) },
            failedRun.takeIf { it > 0 }?.let { getString(R.string.downloads_summary_failed, it) },
            pausedRun.takeIf { it > 0 }?.let { getString(R.string.downloads_summary_paused, it) },
        ).joinToString(" · ")
        return summaryBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.download_notification_channel))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
    }

    private fun summaryBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent)

    // ---- locks / plumbing ----

    /** Downloads are always network work: partial wake + high-perf wifi, held while the queue
     *  works, released the moment it drains — no pause grace like playback, the queue state is
     *  the whole truth here. */
    private fun holdLocks() {
        if (wakeLock == null) {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YMusic:downloads")
                .apply { setReferenceCounted(false) }

            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YMusic:downloads")
                .apply { setReferenceCounted(false) }
        }
        if (wakeLock?.isHeld != true) {
            @Suppress("WakelockTimeout")
            wakeLock?.acquire()
        }
        if (wifiLock?.isHeld != true) wifiLock?.acquire()
    }

    private fun releaseLocks() {
        // release() throws on a racy double-release; a swallowed race beats a crash loop here.
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
    }

    /** Lands on the Download manager. The distinct action keeps this PendingIntent from aliasing
     *  the playback notification's plain launch intent (filterEquals compares actions). */
    private val openAppIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DOWNLOADS),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val pauseAllIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            PAUSE_REQUEST_CODE,
            Intent(this, DownloadForegroundService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.download_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "io.ymusic.downloads"
        const val GROUP_KEY = "io.ymusic.downloads.GROUP"
        const val SUMMARY_NOTIFICATION_ID = 2001
        const val CHILD_ID_BASE = 0x100000
        const val ACTION_PAUSE_ALL = "io.ymusic.android.downloads.PAUSE_ALL"
        const val PAUSE_REQUEST_CODE = 200
        const val OPEN_REQUEST_CODE = 201
        const val UPDATE_THROTTLE_MS = 500L
        const val THUMB_PX = 256

        // Some OEM SystemUIs (One UI 7) race their own inflation of one notification against the
        // next update of it and crash the app with "Couldn't inflate contentViews".
        const val SUMMARY_MIN_INTERVAL_MS = 1_000L
    }
}
