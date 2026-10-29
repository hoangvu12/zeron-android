package sh.zeron.android.shell

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.zeron_core.Credentials
import uniffi.zeron_core.DemoFixture
import uniffi.zeron_core.DemoOptions
import uniffi.zeron_core.StreamSpeed
import uniffi.zeron_core.TranscriptScale

/**
 * The launch extras (android-ui-parity #36, #18): `LaunchOptions.parse`'s
 * debug gate and type rules, the ungated `route` read, the account extras
 * (which account a launch starts, the demo's options), and
 * `applyWallpaper`'s call order against a recording [WallpaperTarget].
 * Plain JUnit: the
 * seam over `android.util.Log` ("not mocked" on the plain JVM) is
 * replaced in [setUp] and restored in [tearDown].
 */
class LaunchOptionsTest {

    private val logs = mutableListOf<String>()
    private val originalLog = launchOptionsLog

    @Before
    fun setUp() {
        launchOptionsLog = { message -> logs.add(message) }
    }

    @After
    fun tearDown() {
        launchOptionsLog = originalLog
    }

    // --------------------------------------------------------------- parse

    @Test
    fun debuggableParsesEveryExtra() {
        val options = LaunchOptions.parse(
            mapOf(
                "wallpaper" to "/x/a.png",
                "wallpaper-effect" to "halftone",
                "harness" to "mock",
                "measureopen" to true,
                "signedout" to true,
                "dev" to "alice org1",
                "demo" to true,
                "route" to "chat:chat-deploy",
                "noprojects" to true,
                "iosonly" to "true",
                "big" to true,
                "huge" to true,
                "fast" to true,
                "longreply" to true,
                "cloud" to true,
                "edge" to "http://10.0.2.2:8787",
                "workos" to "client_staging",
            ),
            debuggable = true,
        )
        assertEquals("/x/a.png", options.wallpaper)
        assertEquals("halftone", options.wallpaperEffect)
        assertEquals("mock", options.harness)
        assertTrue(options.measureOpen)
        assertTrue(options.signedOut)
        assertEquals(Credentials.Dev("alice", "org1"), options.dev)
        assertTrue(options.demo)
        assertTrue(options.route)
        assertTrue(options.noProjects)
        assertTrue(options.iosOnly)
        assertTrue(options.big)
        assertTrue(options.huge)
        assertTrue(options.fast)
        assertTrue(options.longReply)
        assertTrue(options.cloud)
        assertEquals("http://10.0.2.2:8787", options.edge)
        assertEquals("client_staging", options.workos)
        assertTrue(options.touchesAccount)
    }

    // #18: `dev "<userId> <orgId>"` — exactly two words.
    @Test
    fun devNeedsAUserAndAnOrg() {
        assertEquals(
            Credentials.Dev("alice", "org1"),
            LaunchOptions.parse(mapOf("dev" to "  alice   org1 "), debuggable = true).dev,
        )
        assertNull(LaunchOptions.parse(mapOf("dev" to "alice"), debuggable = true).dev)
        assertNull(LaunchOptions.parse(mapOf("dev" to "a b c"), debuggable = true).dev)
        assertEquals(2, logs.size)
    }

    // ------------------------------------------------------------ account

    @Test
    fun devWinsOverTheDemo() {
        val options = LaunchOptions(dev = Credentials.Dev("alice", "org1"), demo = true)
        assertEquals(Credentials.Dev("alice", "org1"), options.launchCredentials(hasStored = true))
    }

    @Test
    fun theDemoRunsWhetherOrNotAnAccountIsStored() {
        val options = LaunchOptions(demo = true)
        assertTrue(options.launchCredentials(hasStored = true) is Credentials.Demo)
        assertTrue(options.launchCredentials(hasStored = false) is Credentials.Demo)
    }

    @Test
    fun aRouteImpliesTheDemoOnlyWhenNothingIsStored() {
        val options = LaunchOptions(route = true)
        assertTrue(options.launchCredentials(hasStored = false) is Credentials.Demo)
        assertNull(options.launchCredentials(hasStored = true))
        assertNull(LaunchOptions().launchCredentials(hasStored = false))
    }

    @Test
    fun onlyARouteThatParsesCounts() {
        assertTrue(LaunchOptions.parse(mapOf("route" to "new"), debuggable = true).route)
        assertFalse(LaunchOptions.parse(mapOf("route" to "gallery"), debuggable = true).route)
    }

    @Test
    fun demoOptionsFollowIosPrecedence() {
        assertEquals(
            DemoOptions(DemoFixture.STANDARD, TranscriptScale.Normal, StreamSpeed.REALISTIC, false),
            LaunchOptions(demo = true).demoOptions(),
        )
        assertEquals(
            DemoOptions(DemoFixture.NO_PROJECTS, TranscriptScale.Huge, StreamSpeed.FAST, true),
            LaunchOptions(noProjects = true, iosOnly = true, big = true, huge = true, fast = true, longReply = true)
                .demoOptions(),
        )
        assertEquals(
            DemoOptions(DemoFixture.IOS_ONLY, TranscriptScale.Big, StreamSpeed.REALISTIC, false),
            LaunchOptions(iosOnly = true, big = true).demoOptions(),
        )
    }

    @Test
    fun wallpaperAloneDoesNotTouchTheAccount() {
        assertFalse(LaunchOptions(wallpaper = "none", cloud = true, fast = true).touchesAccount)
        assertTrue(LaunchOptions(edge = "prod").touchesAccount)
    }

    @Test
    fun measureOpenAcceptsTrueAndCaseInsensitiveStrings() {
        assertTrue(LaunchOptions.parse(mapOf("measureopen" to true), debuggable = true).measureOpen)
        assertTrue(LaunchOptions.parse(mapOf("measureopen" to "true"), debuggable = true).measureOpen)
        assertTrue(LaunchOptions.parse(mapOf("measureopen" to "TRUE"), debuggable = true).measureOpen)
        // "1" (String) and 1 (Int) are not true.
        assertFalse(LaunchOptions.parse(mapOf("measureopen" to "1"), debuggable = true).measureOpen)
        assertFalse(LaunchOptions.parse(mapOf("measureopen" to 1), debuggable = true).measureOpen)
        // Absent is false.
        assertFalse(LaunchOptions.parse(emptyMap(), debuggable = true).measureOpen)
    }

    @Test
    fun remoteVoiceTakesEitherBooleanForm() {
        assertTrue(LaunchOptions.parse(mapOf("remote-voice" to true), debuggable = true).remoteVoice)
        assertTrue(LaunchOptions.parse(mapOf("remote-voice" to "true"), debuggable = true).remoteVoice)
        assertFalse(LaunchOptions.parse(emptyMap(), debuggable = true).remoteVoice)
        // Not an account extra: it never starts the demo or touches the account.
        assertFalse(LaunchOptions.parse(mapOf("remote-voice" to true), debuggable = true).touchesAccount)
    }

    @Test
    fun nonDebuggableIgnoresEveryDebugExtra() {
        val extras = mapOf(
            "wallpaper" to "/x/a.png",
            "wallpaper-effect" to "halftone",
            "harness" to "mock",
            "measureopen" to true,
            // SPEC D12: the account extras are debug-only too.
            "signedout" to true,
            "dev" to "alice org1",
            "demo" to true,
            "route" to "new",
            "edge" to "http://evil",
            "workos" to "client_evil",
            "remote-voice" to true,
        )
        assertEquals(LaunchOptions(), LaunchOptions.parse(extras, debuggable = false))
        assertFalse(LaunchOptions.parse(extras, debuggable = false).touchesAccount)
    }

    @Test
    fun blankStringsAndWrongTypesReadAsAbsent() {
        val options = LaunchOptions.parse(
            mapOf(
                "wallpaper" to "   ",
                "wallpaper-effect" to 5,
                "harness" to "",
                "measureopen" to "yes",
                "demo" to 1,
                "edge" to " ",
                "dev" to 27,
            ),
            debuggable = true,
        )
        assertNull(options.wallpaper)
        assertNull(options.wallpaperEffect)
        assertNull(options.harness)
        assertFalse(options.measureOpen)
        assertFalse(options.demo)
        assertNull(options.edge)
        assertNull(options.dev)
    }

    // --------------------------------------------------------------- route

    @Test
    fun routeIsUngatedAndNeverALaunchOptionsField() {
        val extras = mapOf<String, Any?>("route" to "chat:abc")
        // The route parses whatever the debug value is (it is not one of
        // the gated fields — parse with debuggable=false returns the empty
        // record, and route still reads it).
        assertEquals(DeepLink.OpenChat("abc"), LaunchOptions.route(extras))
        assertEquals(LaunchOptions(), LaunchOptions.parse(extras, debuggable = false))
        // Debuggable, a route only records that one was given (#18's demo).
        assertEquals(LaunchOptions(route = true), LaunchOptions.parse(extras, debuggable = true))
        // Non-string, absent and unknown routes read as nothing.
        assertNull(LaunchOptions.route(mapOf("route" to 7)))
        assertNull(LaunchOptions.route(emptyMap()))
        assertNull(LaunchOptions.route(mapOf("route" to "gallery")))
    }

    @Test
    fun debugRoutesNeedADebuggableBuild() {
        val extras = mapOf<String, Any?>("route" to "providers:cloud")
        assertNull(LaunchOptions.route(extras))
        assertEquals(
            DeepLink.Debug(DebugRoute.Providers("cloud")),
            LaunchOptions.route(extras, debuggable = true),
        )
        // Debuggable, a debug route counts as a route (#18's demo).
        assertEquals(LaunchOptions(route = true), LaunchOptions.parse(extras, debuggable = true))
    }

    // ------------------------------------------------------ applyWallpaper

    @Test
    fun wallpaperNoneOnlyRemoves() {
        val target = RecordingWallpapers()
        LaunchOptions(wallpaper = "none").applyWallpaper(target)
        assertEquals(listOf<Any>("remove"), target.calls)
    }

    @Test
    fun wallpaperFirstThenEffect() {
        val target = RecordingWallpapers()
        LaunchOptions(wallpaper = "/x/a.png", wallpaperEffect = "halftone")
            .applyWallpaper(target)
        assertEquals(listOf<Any>(File("/x/a.png"), "effect(halftone)"), target.calls)
    }

    @Test
    fun effectOnly() {
        val target = RecordingWallpapers()
        LaunchOptions(wallpaperEffect = "dither").applyWallpaper(target)
        assertEquals(listOf<Any>("effect(dither)"), target.calls)
    }

    @Test
    fun wallpaperIsCaseSensitive() {
        val target = RecordingWallpapers()
        // "None" is a file path, not the clear keyword (iOS: exact match).
        LaunchOptions(wallpaper = "None").applyWallpaper(target)
        assertEquals(listOf<Any>(File("None")), target.calls)
    }

    @Test
    fun neitherMeansNoCalls() {
        val target = RecordingWallpapers()
        LaunchOptions().applyWallpaper(target)
        assertTrue(target.calls.isEmpty())
    }

    @Test
    fun anUnknownEffectKeyWarnsAndChangesNothingElse() {
        val target = RecordingWallpapers()
        LaunchOptions(wallpaperEffect = "bogus").applyWallpaper(target)
        assertEquals(listOf<Any>("effect(bogus)"), target.calls)
        assertEquals(listOf("wallpaper-effect: unknown key bogus"), logs)
    }

    // ---------------------------------------------------------------- rig

    /** Records the calls, in order, and knows the five real effect keys. */
    private class RecordingWallpapers : WallpaperTarget {
        val calls = mutableListOf<Any>()

        override fun remove() {
            calls.add("remove")
        }

        override fun set(path: File) {
            calls.add(path)
        }

        override fun setEffect(key: String): Boolean {
            calls.add("effect($key)")
            return key in EFFECT_KEYS
        }
    }

    private companion object {
        val EFFECT_KEYS = setOf("none", "dither", "ascii", "halftone", "scanlines")
    }
}
