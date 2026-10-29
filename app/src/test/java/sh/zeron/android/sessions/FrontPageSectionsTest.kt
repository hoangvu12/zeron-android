package sh.zeron.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FrontPageSections] (#21): the section order of iOS `buildSections`
 * (`SessionListController.swift:509-525`), the client-side project groups
 * of `AppModel.projectGroups` (`App/AppModel.swift:442-468`), the metadata
 * kinds, the headed-only `collapsedIds` and Collapse All's ids. Zeron has
 * no engines, so no header ever carries an engine suffix.
 */
class FrontPageSectionsTest {

    private val p1 = testRow("p1", pinned = true)
    private val s1 = testRow("s1", sectionId = "backend")
    private val r1 = testRow("r1")
    private val backend = testSection("backend", "Backend", listOf(s1))

    private fun ids(built: FrontPageSections.Built) = built.sections.map { it.id }
    private fun headers(built: FrontPageSections.Built) = built.sections.map { it.header }

    // ------------------------------------------------------------ order

    @Test
    fun pinnedThenSectionsThenRecent() {
        val built = FrontPageSections.build(
            testFront(pinned = listOf(p1), sections = listOf(backend), recent = listOf(r1)),
            projects = emptyList(),
            groupByProject = false,
        )
        assertEquals(listOf("pinned", "backend", "recent"), ids(built))
        assertEquals(listOf("Pinned", "Backend", "Recent"), headers(built))
        assertEquals(listOf(listOf(p1), listOf(s1), listOf(r1)), built.sections.map { it.rows })
    }

    @Test
    fun noPinsAndNoSectionsLeaveAHeaderlessRecent() {
        val built = FrontPageSections.build(testFront(recent = listOf(r1)), emptyList(), groupByProject = false)
        assertEquals(listOf("recent"), ids(built))
        assertNull(built.sections.single().header)
    }

    @Test
    fun anEmptySectionStillCountsForTheRecentHeader() {
        val built = FrontPageSections.build(
            testFront(sections = listOf(testSection("empty", "Empty")), recent = listOf(r1)),
            emptyList(),
            groupByProject = false,
        )
        assertEquals(listOf("Empty", "Recent"), headers(built))
    }

    // ------------------------------------------------------------ project groups

    @Test
    fun groupingReplacesRecentWithTheProjectGroupsInRecencyOrder() {
        val a1 = testRow("a1", project = testRef("api-mac", "api"))
        val h1 = testRow("h1", deviceId = "mac", deviceName = "MacBook")
        val a2 = testRow("a2", project = testRef("api-linux", "api"))
        val w1 = testRow("w1", project = testRef("web", "web"))
        val built = FrontPageSections.build(
            testFront(pinned = listOf(p1), sections = listOf(backend), recent = listOf(a1, h1, a2, w1)),
            projects = listOf(
                testProject("api-mac", groupKey = "github.com/acme/api", groupName = "api"),
                testProject("api-linux", groupKey = "github.com/acme/api", groupName = "api"),
                testProject("web", groupKey = "github.com/acme/web", groupName = "Web app"),
            ),
            groupByProject = true,
        )
        assertEquals(
            listOf("pinned", "backend", "project:github.com/acme/api", "home:mac", "project:github.com/acme/web"),
            ids(built),
        )
        assertEquals(listOf("Pinned", "Backend", "api", "Home · MacBook", "Web app"), headers(built))
        // Checkouts of one repository share a group; rows keep recency order.
        assertEquals(listOf(a1, a2), built.sections[2].rows)
        assertEquals(FrontSectionKind.ProjectGroup, built.meta["project:github.com/acme/api"]?.kind)
        assertNull(built.meta["home:mac"]?.rawName)
    }

    @Test
    fun anUnlistedProjectFallsBackToItsOwnIdAndNameAndAnUnnamedDeviceToHome() {
        val groups = FrontPageSections.projectGroups(
            listOf(testRow("x", project = testRef("p9", "Lost")), testRow("y", deviceId = "d2")),
            projects = emptyList(),
        )
        assertEquals(listOf("project:p9", "home:d2"), groups.map { it.id })
        assertEquals(listOf("Lost", "Home"), groups.map { it.name })
    }

    @Test
    fun projectlessRowsGroupPerDevice() {
        val groups = FrontPageSections.projectGroups(
            listOf(
                testRow("a", deviceId = "mac", deviceName = "Mac"),
                testRow("b", deviceId = "pc", deviceName = "PC"),
                testRow("c", deviceId = "mac", deviceName = "Mac"),
            ),
            projects = emptyList(),
        )
        assertEquals(listOf("home:mac", "home:pc"), groups.map { it.id })
        assertEquals(listOf("a", "c"), groups[0].rows.map { it.id })
    }

    @Test
    fun groupingWithNoRecentRowsEmitsNothingAfterTheSections() {
        val built = FrontPageSections.build(testFront(sections = listOf(backend)), emptyList(), groupByProject = true)
        assertEquals(listOf("backend"), ids(built))
    }

    // ------------------------------------------------------------ meta and folds

    @Test
    fun metaMapsEveryKindAndTheRawUserSectionName() {
        val built = FrontPageSections.build(
            testFront(pinned = listOf(p1), sections = listOf(backend), recent = listOf(r1)),
            emptyList(),
            groupByProject = false,
        )
        assertEquals(FrontSectionMeta(FrontSectionKind.Pinned, null), built.meta["pinned"])
        assertEquals(FrontSectionMeta(FrontSectionKind.User, "Backend"), built.meta["backend"])
        assertEquals(FrontSectionMeta(FrontSectionKind.Recent, null), built.meta["recent"])
    }

    @Test
    fun collapsedIdsCoversHeadedIdsOnly() {
        val sections = listOf(
            ListSection(id = "backend", header = "Backend", rows = listOf(s1)),
            ListSection(id = "recent", header = null, rows = listOf(r1)),
        )
        assertEquals(setOf("backend"), FrontPageSections.collapsedIds(sections) { true })
    }

    @Test
    fun collapsibleIdsAreFoldersThenGroupsWhileGroupingAndNeverRecent() {
        val front = testFront(
            pinned = listOf(p1),
            sections = listOf(backend),
            recent = listOf(testRow("h", deviceId = "mac")),
        )
        assertEquals(listOf("pinned", "backend"), FrontPageSections.collapsibleIds(front, emptyList(), false))
        assertEquals(
            listOf("pinned", "backend", "home:mac"),
            FrontPageSections.collapsibleIds(front, emptyList(), true),
        )
        assertEquals(emptyList<String>(), FrontPageSections.collapsibleIds(testFront(recent = listOf(r1)), emptyList(), false))
    }
}
