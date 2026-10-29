package app.remotely.player

import app.remotely.core.Clock
import app.remotely.core.EventLog
import app.remotely.data.api.ArchiveUnavailableException
import app.remotely.data.api.BadCredentialsException
import app.remotely.data.api.Channel
import app.remotely.data.api.Programme
import app.remotely.data.repo.StreamSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FakeEngine : MediaEngine {
    var l: MediaEngine.Listener? = null
    val opened = mutableListOf<Pair<String, Long>>()
    var stops = 0
    override var state = EngineState.IDLE
    override var playWhenReady = false
    override var positionMs = 0L
    override val durationMs = 0L
    override var progressMarker = 0L
    var liveSeeks = 0
    override var renderedFrames: Long? = null
    var videoRestarts = 0
    override fun restartVideo() { videoRestarts++ }
    override fun setListener(listener: MediaEngine.Listener?) { l = listener }
    override fun open(url: String, isLive: Boolean, startPositionMs: Long) {
        opened += url to startPositionMs; state = EngineState.BUFFERING; playWhenReady = true
    }
    override fun stop() { stops++; state = EngineState.IDLE; playWhenReady = false }
    override fun setPaused(paused: Boolean) { playWhenReady = !paused }
    override fun seekTo(positionMs: Long) { this.positionMs = positionMs }
    override fun seekToLive() { liveSeeks++ }
    override fun setMaxHeight(height: Int) = Unit
    fun ready() { state = EngineState.READY; l?.onStateChanged(EngineState.READY) }
}

class FakeStreams : StreamSource {
    var failures = 0
    var error: () -> Exception = { IOException("net") }
    var n = 0
    var delayMs = 0L
    override suspend fun liveUrl(ch: Channel): String {
        if (failures > 0) { failures--; throw error() }
        return "live-${ch.code}-${++n}"
    }
    override suspend fun archiveUrl(ch: Channel, p: Programme): String {
        kotlinx.coroutines.delay(delayMs)
        if (failures > 0) { failures--; throw error() }
        return "rec-${p.code}-${++n}"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerControllerTest {
    private val ch = Channel("c1", "ONT", 2, "3", "0803", "1", null, true)
    private val prog = Programme("P", "c1", "T", "", "", 0, 3600_000, true)

    private fun TestScope.setup(): Triple<PlayerController, FakeEngine, FakeStreams> {
        val engine = FakeEngine()
        val streams = FakeStreams()
        val clock = Clock { testScheduler.currentTime }
        val c = PlayerController(engine, streams, clock, backgroundScope, EventLog(clock))
        return Triple(c, engine, streams)
    }

    @Test fun breakInBroadcastShowsNoStreamAndOpensWhenOver() = runTest {
        val engine = FakeEngine()
        val clock = Clock { testScheduler.currentTime }
        var until: Long? = 60_000
        val c = PlayerController(engine, FakeStreams(), clock, backgroundScope, EventLog(clock), blackoutUntil = { until })
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        assertTrue(engine.opened.isEmpty())
        assertEquals(Phase.BLACKOUT, c.state.value.phase)
        assertEquals(60_000L, c.state.value.blackoutUntilMs)
        until = null
        advanceTimeBy(61_001)
        runCurrent()
        assertEquals(1, engine.opened.size)
        assertEquals(Phase.CONNECTING, c.state.value.phase)
    }

    @Test fun breakStartingWhileWatchingStopsTheStream() = runTest {
        val engine = FakeEngine()
        val clock = Clock { testScheduler.currentTime }
        var until: Long? = null
        val c = PlayerController(engine, FakeStreams(), clock, backgroundScope, EventLog(clock), blackoutUntil = { until })
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        engine.ready()
        until = 3600_000
        assertTrue(c.recheckBlackout())
        assertEquals(Phase.BLACKOUT, c.state.value.phase)
        assertTrue(engine.stops > 0)
        assertEquals(false, c.recheckBlackout())
    }

    @Test fun playsAndBecomesPlaying() = runTest {
        val (c, e, _) = setup()
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        assertEquals("live-c1-1", e.opened.single().first)
        e.ready()
        assertEquals(Phase.PLAYING, c.state.value.phase)
    }

    @Test fun sixRetriesThenUserRetryThenSucceeds() = runTest {
        val (c, e, s) = setup()
        s.failures = 100
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        assertEquals(Phase.RECONNECTING, c.state.value.phase)
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(Phase.RECONNECTING, c.state.value.phase)
        advanceTimeBy(1_000 + 10)
        runCurrent()
        assertEquals(Phase.NEEDS_USER_RETRY, c.state.value.phase)
        advanceTimeBy(600_000) // no hammering while dialog is shown
        assertEquals(Phase.NEEDS_USER_RETRY, c.state.value.phase)
        assertEquals(0, e.opened.size)
        s.failures = 0
        c.retry()
        runCurrent()
        assertEquals(1, e.opened.size)
    }

    @Test fun networkReturnTriggersOneAttempt() = runTest {
        val (c, e, s) = setup()
        c.onNetworkChanged(false)
        s.failures = 1
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        assertEquals(Phase.NO_NETWORK, c.state.value.phase)
        advanceTimeBy(120_000)
        assertEquals(0, e.opened.size) // waits for network
        c.onNetworkChanged(true)
        runCurrent()
        assertEquals(1, e.opened.size)
    }

    @Test fun badCredentialsNoRetry() = runTest {
        val (c, e, s) = setup()
        s.failures = 1
        s.error = { BadCredentialsException("70116206") }
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        advanceTimeBy(120_000)
        assertEquals(Phase.BAD_CREDENTIALS, c.state.value.phase)
        assertEquals(0, e.opened.size)
    }

    @Test fun stallRestartsWithFreshUrl() = runTest {
        val (c, e, _) = setup()
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        e.ready()
        advanceTimeBy(14_000) // marker never moves
        runCurrent()
        assertEquals(Phase.RECONNECTING, c.state.value.phase)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, e.opened.size)
        assertTrue(e.opened[1].first.endsWith("-2"))
    }

    /** Sound plays (media time moves) but no new video frames. */
    private suspend fun TestScope.freezePicture(e: FakeEngine, ms: Long) {
        repeat((ms / 1_000).toInt()) {
            e.progressMarker += 1_000
            advanceTimeBy(1_000)
            runCurrent()
        }
    }

    @Test fun frozenPictureRestartsVideoThenStream() = runTest {
        val (c, e, _) = setup()
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        e.ready()
        e.renderedFrames = 100
        freezePicture(e, 5_000)
        assertEquals(0, e.videoRestarts) // short hiccup is fine
        freezePicture(e, 3_000)
        assertEquals(1, e.videoRestarts)
        assertEquals(1, e.opened.size)
        freezePicture(e, 8_000) // decoder restart did not help
        assertEquals(1, e.videoRestarts)
        assertEquals(2, e.opened.size)
        assertTrue(e.opened[1].first.endsWith("-2"))
    }

    @Test fun movingPictureOrPauseOrNoVideoIsNotFrozen() = runTest {
        val (c, e, _) = setup()
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        e.ready()
        e.renderedFrames = 0
        repeat(30) {
            e.renderedFrames = e.renderedFrames!! + 25
            freezePicture(e, 1_000)
        }
        c.setPaused(true)
        advanceTimeBy(30_000)
        runCurrent()
        c.setPaused(false)
        e.renderedFrames = null // e.g. radio: no video track
        freezePicture(e, 30_000)
        assertEquals(0, e.videoRestarts)
        assertEquals(1, e.opened.size)
    }

    @Test fun rareFreezesOnlyRestartVideo() = runTest {
        val (c, e, _) = setup()
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        e.ready()
        e.renderedFrames = 1
        freezePicture(e, 8_000)
        repeat(90) { e.renderedFrames = e.renderedFrames!! + 25; freezePicture(e, 1_000) }
        freezePicture(e, 8_000)
        assertEquals(2, e.videoRestarts)
        assertEquals(1, e.opened.size)
    }

    @Test fun behindLiveWindowJustSeeks() = runTest {
        val (c, e, _) = setup()
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        e.l!!.onError(true, "behind")
        assertEquals(1, e.liveSeeks)
        assertEquals(1, e.opened.size)
    }

    @Test fun archiveReconnectResumesPosition() = runTest {
        val (c, e, _) = setup()
        c.play(PlaybackTarget.Archive(ch, prog, 600_000))
        runCurrent()
        assertEquals(600_000L, e.opened[0].second)
        e.ready()
        e.positionMs = 700_000
        e.progressMarker = 1
        advanceTimeBy(1_100)
        e.l!!.onError(false, "http 500")
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(700_000L, e.opened[1].second)
    }

    private fun TestScope.events(c: PlayerController): MutableList<PlayerEvent> {
        val events = mutableListOf<PlayerEvent>()
        backgroundScope.launch { c.events.toList(events) }
        runCurrent()
        return events
    }

    @Test fun missingRecordingAsksOtherServersThenGivesUp() = runTest {
        val (c, e, _) = setup()
        val events = events(c)
        c.play(PlaybackTarget.Archive(ch, prog, 0))
        runCurrent()
        e.l!!.onError(false, "404", NotFound.MANIFEST)
        assertEquals(Phase.CONNECTING, c.state.value.phase) // no "Восстанавливаем" for this
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, e.opened.size)
        e.ready() // another server has it
        assertTrue(events.isEmpty())

        c.play(PlaybackTarget.Archive(ch, prog, 0))
        runCurrent()
        repeat(4) {
            e.l!!.onError(false, "404", NotFound.MANIFEST)
            advanceTimeBy(5_000)
            runCurrent()
        }
        assertTrue(events.single() is PlayerEvent.RecordingUnavailable)
        assertEquals(2 + 1 + 3, e.opened.size)
    }

    @Test fun holeInRecordingIsSkipped() = runTest {
        val (c, e, _) = setup()
        val events = events(c)
        c.play(PlaybackTarget.Archive(ch, prog, 0))
        runCurrent()
        e.ready()
        e.positionMs = 600_000
        repeat(4) { // the piece at 10:00 is missing on every server
            e.l!!.onError(false, "404", NotFound.SEGMENT)
            advanceTimeBy(5_000)
            runCurrent()
        }
        assertEquals(620_000L, e.opened.last().second)
        e.l!!.onError(false, "404", NotFound.SEGMENT) // hole goes on: skip again at once
        runCurrent()
        assertEquals(640_000L, e.opened.last().second)
        assertTrue(events.isEmpty())
        e.positionMs = 3_590_000 // a hole at the very end ends the recording
        e.l!!.onError(false, "404", NotFound.SEGMENT)
        runCurrent()
        assertTrue(events.single() is PlayerEvent.RecordingEnded)
    }

    @Test fun recordingStartsWhereAskedNotWhereLiveWas() = runTest {
        val (c, e, s) = setup()
        c.play(PlaybackTarget.Live(ch))
        runCurrent()
        e.ready()
        e.positionMs = 66_000 // live window position
        s.delayMs = 3_000 // portal is slow to give the recording link
        c.play(PlaybackTarget.Archive(ch, prog, 0))
        advanceTimeBy(1_000)
        assertEquals(0L, c.positionMs) // header shows the recording time, not the old live position
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(0L, e.opened.last().second)
    }

        @Test fun archiveUnavailableEmitsEvent() = runTest {
        val (c, _, s) = setup()
        s.failures = 1
        s.error = { ArchiveUnavailableException("98") }
        val events = mutableListOf<PlayerEvent>()
        backgroundScope.launch { c.events.toList(events) }
        runCurrent()
        c.play(PlaybackTarget.Archive(ch, prog, 0))
        runCurrent()
        assertTrue(events.single() is PlayerEvent.RecordingUnavailable)
    }
}
