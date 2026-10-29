package sh.zeron.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The list status (android-ui-parity #41, rewritten for #25): rows, or a
 * blank list — iOS draws no empty, no-results or loading state on any
 * session list (C13, C14, J3, I7; D11 drops the Android-only copy and
 * spinner). Plain JUnit: the records are plain Kotlin data classes.
 */
class SessionListStatusTest {

    @Test
    fun rowsAreContent() {
        val one = buildSessionListItems(
            listOf(ListSection(id = "s", header = null, rows = listOf(testRow("a")))),
            collapsible = true,
            toVm = SessionListFixtures::toVm,
        )
        assertEquals(SessionListStatus.Content, sessionListStatus(one))
    }

    @Test
    fun nothingToShowIsBlank() {
        assertEquals(SessionListStatus.Blank, sessionListStatus(emptyList()))
        // A section with no rows and no header shows nothing either.
        val none = buildSessionListItems(
            listOf(ListSection(id = "s", header = null, rows = emptyList())),
            collapsible = true,
            toVm = SessionListFixtures::toVm,
        )
        assertEquals(SessionListStatus.Blank, sessionListStatus(none))
    }

    @Test
    fun thereIsNoOtherState() {
        assertEquals(listOf(SessionListStatus.Content, SessionListStatus.Blank), SessionListStatus.entries)
    }
}
