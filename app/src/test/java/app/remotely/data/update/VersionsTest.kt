package app.remotely.data.update

import app.remotely.data.store.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun comparesNumericallyNotAsText() {
        assertTrue(Versions.isNewer("v1.16", "1.15"))
        assertTrue(Versions.isNewer("1.100", "1.99"))
        assertTrue(Versions.isNewer("2.0", "1.99"))
        assertTrue(Versions.isNewer("1.15.1", "1.15"))
        assertFalse(Versions.isNewer("1.15", "1.15"))
        assertFalse(Versions.isNewer("1.9", "1.15"))
        assertFalse(Versions.isNewer("nightly", "1.15"))
    }

    @Test fun releaseNeedsTheConstantApkAsset() {
        val body = """{"tag_name":"v1.16","assets":[
            {"name":"Remotely-1.16.apk","browser_download_url":"https://x/Remotely-1.16.apk"},
            {"name":"Remotely.apk","browser_download_url":"https://x/Remotely.apk"}]}"""
        assertEquals(Release("1.16", "https://x/Remotely.apk"), Versions.parseRelease(body))
        assertNull(Versions.parseRelease("""{"tag_name":"v1.16","assets":[]}"""))
        assertNull(Versions.parseRelease("not json"))
    }

    @Test fun qualityCyclesThroughZalaSteps() {
        assertEquals(listOf(0, 1080, 720, 576, 360), Quality.entries.map { it.height })
        assertEquals(Quality.AUTO, Quality.P360.next())
    }
}
