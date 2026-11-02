package app.remotely.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamQualityLabelTest {
    @Test fun `shows the height of the frame`() {
        assertEquals("720p", StreamTracks(videoHeight = 720).qualityLabel)
        assertEquals("432p", StreamTracks(videoHeight = 432).qualityLabel)
    }

    @Test fun `nothing until the picture is known`() {
        assertNull(StreamTracks().qualityLabel)
    }
}

class StreamBestHeightTest {
    @Test fun `the best the channel has`() {
        assertEquals(1080, StreamTracks.bestHeight(listOf(360, 720, 1080), maxHeight = 0))
    }

    @Test fun `the quality setting limits it`() {
        assertEquals(720, StreamTracks.bestHeight(listOf(360, 720, 1080), maxHeight = 720))
        assertEquals(576, StreamTracks.bestHeight(listOf(360, 576, 1080), maxHeight = 720))
    }

    @Test fun `a limit below every track still gives what plays`() {
        assertEquals(360, StreamTracks.bestHeight(listOf(360, 432), maxHeight = 240))
    }

    @Test fun `nothing known`() {
        assertEquals(0, StreamTracks.bestHeight(emptyList(), maxHeight = 720))
        assertNull(StreamTracks().bestQualityLabel)
        assertEquals("1080p", StreamTracks(bestHeight = 1080).bestQualityLabel)
    }
}
