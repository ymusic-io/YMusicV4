package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.player.PlayableItem
import io.ymusic.player.QueueState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A queue row's drag handle reorders the queue live through `Player.move`, one swap per row crossed. */
@OptIn(ExperimentalTestApi::class)
class NowPlayingQueueTest {

    @Test
    fun dragging_the_handle_reorders_the_queue() {
        val client = CannedHttp { request, _ -> textResponse(request, "{}", "application/json") }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        val player = StubPlayer()
        val items = (0 until 8).map { PlayableItem.LocalMedia("file:///m/song$it.mp3") }
        player.queueState.value = QueueState(items = items, currentIndex = 0)
        try {
            runComposeUiTest {
                setContent {
                    val graph = AppGraph(engines = engines, player = player, playerSurface = {})
                    val coordinator = PlaybackCoordinator(graph, rememberCoroutineScope())
                    // Portrait bounds: the phone layout with the sticky Up-next header above the rows.
                    Box(Modifier.requiredSize(400.dp, 800.dp)) {
                        NowPlayingScreen(graph, coordinator, onClose = {})
                    }
                }
                waitForIdle()

                // The handle is the row's unlabelled leading icon: 16dp row padding, then 18dp of glyph.
                onAllNodes(hasText("song0.mp3", substring = true) and hasClickAction()).onFirst().performTouchInput {
                    down(centerLeft + Offset(25.dp.toPx(), 0f))
                    repeat(8) { moveBy(Offset(0f, 30f)) }
                    up()
                }
                waitForIdle()

                assertTrue(player.moves.isNotEmpty(), "handle drag produced no Player.move")
                assertEquals(0 to 1, player.moves.first(), "first swap moves row 0 down one slot")
                // Swaps go one row at a time, so the dragged item lands one slot deeper per move;
                // ~240px of drag (minus touch slop) crosses at least two 64dp rows.
                val landed = player.queueState.value.items.indexOf(items[0])
                assertEquals(player.moves.size, landed)
                assertTrue(landed >= 2, "dragged two row-heights but landed at $landed")
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
