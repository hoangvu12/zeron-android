package sh.zeron.android.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import sh.zeron.android.MainActivity
import sh.zeron.android.R
import sh.zeron.android.shell.DeepLink
import sh.zeron.android.shell.DeepLinks

/**
 * The notifier (android-app#28): versioned channels with the desktop
 * chimes, migration, one-notification-per-chat replacement (tag
 * `chat:<chatId>`, #56), the cancel paths, the copy table and the "New
 * session" fallback. Robolectric —
 * the `NotificationManager` shadows (API 31, so the 33+ runtime
 * permission never gates the shadow's `notify`), on a plain [Application]
 * so the core never loads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class NotifierTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var notifier: Notifier
    private lateinit var manager: NotificationManager

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        manager = context.getSystemService(NotificationManager::class.java)
        notifier = Notifier(context)
        notifier.migrateChannels()
        notifier.ensureChannels()
    }

    // -------------------------------------------------- channels

    @Test
    fun theFourChannelsExistWithTheChimesAndPatterns() {
        val done = manager.getNotificationChannel("session-done-v1")
        val input = manager.getNotificationChannel("session-input-v1")
        val failed = manager.getNotificationChannel("session-failed-v1")
        val runs = manager.getNotificationChannel("runs-v1")
        assertNotNull(done)
        assertNotNull(input)
        assertNotNull(failed)
        assertNotNull(runs)
        assertEquals("Task completed", done.name.toString())
        assertEquals("When an agent finishes a run", done.description)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, done.importance)
        assertEquals("Input required", input.name.toString())
        assertEquals("When an agent needs your response", input.description)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, input.importance)
        assertEquals("Errors and disconnections", failed.name.toString())
        assertEquals(
            "When a run fails or the connection remains unavailable",
            failed.description,
        )
        assertEquals(NotificationManager.IMPORTANCE_HIGH, failed.importance)
        assertEquals("Runs in progress", runs.name.toString())
        assertEquals("Shown while an agent run is active", runs.description)
        assertEquals(NotificationManager.IMPORTANCE_LOW, runs.importance)
        // The chime sounds, as android.resource:// URIs.
        assertEquals(
            "android.resource://${context.packageName}/raw/chime_done",
            done.sound.toString(),
        )
        assertEquals(
            "android.resource://${context.packageName}/raw/chime_request",
            input.sound.toString(),
        )
        assertEquals(
            "android.resource://${context.packageName}/raw/chime_attention",
            failed.sound.toString(),
        )
        // #699's vibration patterns.
        assertTrue(done.vibrationPattern.contentEquals(longArrayOf(0, 24, 40, 30)))
        assertTrue(input.vibrationPattern.contentEquals(longArrayOf(0, 18, 90, 18)))
        assertTrue(failed.vibrationPattern.contentEquals(longArrayOf(0, 35, 55, 45)))
        // The runs channel stays quiet and never MIN (Live Update needs it).
        assertNull(runs.sound)
    }

    @Test
    fun migrateDeletesOtherVersionsAndKeepsTheCurrentOnes() {
        manager.createNotificationChannel(
            NotificationChannel("session-done-v0", "old", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel("runs-v9", "old runs", NotificationManager.IMPORTANCE_LOW),
        )
        notifier.migrateChannels()
        assertNull(manager.getNotificationChannel("session-done-v0"))
        assertNull(manager.getNotificationChannel("runs-v9"))
        assertNotNull(manager.getNotificationChannel("session-done-v1"))
        assertNotNull(manager.getNotificationChannel("runs-v1"))
        // Unrelated channels are untouched.
        manager.createNotificationChannel(
            NotificationChannel("other", "other", NotificationManager.IMPORTANCE_DEFAULT),
        )
        notifier.migrateChannels()
        assertNotNull(manager.getNotificationChannel("other"))
    }

    // -------------------------------------------------- the copy table

    @Test
    fun theSessionCopyTableAndTheFallbackTitle() {
        notifier.postSessionEvent("c1", "Fix the login bug", NotificationKind.Done, now)
        val done = posted("chat:c1")
        assertEquals("Fix the login bug", title(done))
        assertEquals("Run finished", text(done))
        assertEquals(NotificationCompat.CATEGORY_STATUS, done.category)
        assertEquals("session-done-v1", done.channelId)
        assertEquals(now, done.`when`)

        // A blank title falls back to "New session".
        notifier.postSessionEvent("c1", "   ", NotificationKind.Input, now)
        val input = posted("chat:c1")
        assertEquals("New session", title(input))
        assertEquals("Waiting on your input", text(input))
        assertEquals("session-input-v1", input.channelId)
        assertEquals(NotificationCompat.CATEGORY_STATUS, input.category)

        notifier.postSessionEvent("c2", "A failing one", NotificationKind.Failed, now - 5_000)
        val failed = posted("chat:c2")
        assertEquals("Run failed", text(failed))
        assertEquals(NotificationCompat.CATEGORY_ERROR, failed.category)
        assertEquals("session-failed-v1", failed.channelId)
        // Dated by the row's last activity; no engine subText any more.
        assertEquals(now - 5_000, failed.`when`)
        assertNull(subText(failed))
    }

    @Test
    fun aNewerEventReplacesTheOlderOneForTheSameChat() {
        for (kind in listOf(NotificationKind.Done, NotificationKind.Input, NotificationKind.Failed)) {
            notifier.postSessionEvent("c1", "T", kind, now)
        }
        // One notification per chat — the newest (failed) wins, on its own
        // channel.
        val active = manager.activeNotifications.filter { it.tag == "chat:c1" }
        assertEquals(1, active.size)
        assertEquals("session-failed-v1", active[0].notification.channelId)
    }

    @Test
    fun linkLostCarriesItsCopyAndCancelsOnRestore() {
        notifier.postLinkLost("e1", "Studio Mac", now)
        val lost = posted("link:e1")
        assertEquals("Connection unavailable", title(lost))
        // The body picks the offline copy when no validated network
        // exists; which one Robolectric's shadow reports is not the
        // behaviour under test — both are the ticket's copy.
        assertTrue(
            "the link-lost copy must be one of the two sentences",
            text(lost) == "Zeron is trying to reconnect" || text(lost) == "Your device is offline",
        )
        assertEquals(NotificationCompat.CATEGORY_ERROR, lost.category)
        assertEquals("session-failed-v1", lost.channelId)
        assertEquals("Studio Mac", subText(lost))
        // Restored: only the lost notification goes.
        notifier.postLinkLost("e2", "Other", now)
        assertEquals(2, manager.activeNotifications.count { it.tag?.startsWith("link:") == true })
        notifier.cancelLinkLost("e1")
        assertNull(active("link:e1"))
        assertNotNull(active("link:e2"))
    }

    @Test
    fun theBudgetNotificationPostsAndCancels() {
        notifier.postBudgetExhausted()
        val budget = posted("budget")
        assertEquals("Background updates paused", title(budget))
        assertEquals("Open Zeron to resume.", text(budget))
        assertEquals("runs-v1", budget.channelId)
        notifier.cancelBudgetExhausted()
        assertNull(active("budget"))
    }

    // -------------------------------------------------- cancels

    @Test
    fun theCancelPaths() {
        notifier.postSessionEvent("c1", "A", NotificationKind.Done, now)
        notifier.postSessionEvent("c2", "B", NotificationKind.Done, now)
        notifier.postSessionEvent("c3", "C", NotificationKind.Done, now)
        // Opened in the app: that chat only.
        notifier.cancelChatFor("c1")
        assertNull(active("chat:c1"))
        assertNotNull(active("chat:c2"))
        // Signed out: everything.
        notifier.cancelAll()
        assertEquals(0, manager.activeNotifications.size)
    }

    // -------------------------------------------------- the ongoing builder

    @Test
    fun theOngoingNotificationIsSilentOngoingAndOpensTheChat() {
        val content = ongoingContent(
            OngoingInput(
                working = listOf(
                    WorkingRun("engine:v1:a", "Alpha", "Mac", now - 90_000, fromThisDevice = true),
                ),
                awaiting = 0,
                engineCount = 1,
                liveUpdateDismissed = false,
            ),
            now,
            apiPromotionPossible = false,
        )
        val notification = notifier.buildOngoing(content, onDeleteDismissed = null)
        assertEquals("runs-v1", notification.channelId)
        assertEquals("Alpha", title(notification))
        assertEquals("Working", text(notification))
        assertEquals("Working", notification.extras.getString(EXTRA_SHORT_CRITICAL_TEXT))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(NotificationCompat.CATEGORY_PROGRESS, notification.category)
        assertEquals(now - 90_000, notification.`when`)
        // `Notification.usesChronometer()` is @hide (not in android.jar).
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        // The tap opens the chat (#19).
        assertChatTap("engine:v1:a", notification.contentIntent)
    }

    // -------------------------------------------------- taps (#19)

    @Test
    fun aSessionTapIsAnExplicitIntentWithTheChatIdExtra() {
        notifier.postSessionEvent("c1", "A", NotificationKind.Done, now)
        assertChatTap("c1", posted("chat:c1").contentIntent)
    }

    @Test
    fun aLinkLostTapOnlyOpensTheApp() {
        notifier.postLinkLost("e1", "Mac", now)
        val intent = targetIntent(posted("link:e1").contentIntent)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertNull(intent.getStringExtra(DeepLinks.EXTRA_CHAT_ID))
    }

    // -------------------------------------------------- branding

    /**
     * android-next #03: every builder (session event, link lost, budget,
     * the ongoing run) carries the ribbon-bow small icon, the ribbon blue
     * accent and the launcher artwork as the large icon.
     */
    @Test
    fun everyNotificationCarriesTheBowTheRibbonAccentAndTheArtwork() {
        notifier.postSessionEvent("c1", "A", NotificationKind.Done, now)
        notifier.postLinkLost("e1", "Mac", now)
        notifier.postBudgetExhausted()
        val ongoing = notifier.buildOngoing(
            ongoingContent(
                OngoingInput(
                    working = listOf(WorkingRun("engine:v1:a", "Alpha", "Mac", now, fromThisDevice = false)),
                    awaiting = 0,
                    engineCount = 1,
                    liveUpdateDismissed = false,
                ),
                now,
                apiPromotionPossible = false,
            ),
            onDeleteDismissed = null,
        )
        val all = mapOf(
            "session" to posted("chat:c1"),
            "link" to posted("link:e1"),
            "budget" to posted("budget"),
            "ongoing" to ongoing,
        )
        assertEquals(0xFF4B4F9E.toInt(), ContextCompat.getColor(context, R.color.brand_ribbon))
        for ((name, n) in all) {
            assertEquals("$name small icon", R.drawable.zi_zeron_bow, n.smallIcon.resId)
            assertEquals("$name accent", 0xFF4B4F9E.toInt(), n.color)
            assertNotNull("$name large icon", n.getLargeIcon())
        }
    }

    // -------------------------------------------------- helpers

    private fun active(tag: String): Notification? =
        manager.activeNotifications.firstOrNull { it.tag == tag }?.notification

    private fun posted(tag: String): Notification =
        active(tag) ?: error("no notification posted for $tag")

    private fun title(n: Notification): String? =
        n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun text(n: Notification): String? =
        n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun subText(n: Notification): String? =
        n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

    /** An explicit `MainActivity` intent, no URL, the chat in the `chatId` extra. */
    private fun assertChatTap(chatId: String, pending: PendingIntent) {
        val intent = targetIntent(pending)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNull(intent.data)
        assertEquals(chatId, intent.getStringExtra(DeepLinks.EXTRA_CHAT_ID))
        assertEquals(DeepLink.OpenChat(chatId), DeepLinks.notificationTap(intent.getStringExtra(DeepLinks.EXTRA_CHAT_ID)))
    }

    /** Resolves a pending intent's target intent (the Robolectric shadow). */
    private fun targetIntent(pending: PendingIntent): Intent =
        Shadows.shadowOf(pending).savedIntent

    private companion object {
        const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
    }
}
