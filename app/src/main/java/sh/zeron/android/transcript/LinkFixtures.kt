package sh.zeron.android.transcript

import uniffi.zeron_core.DebugEntry

/**
 * The link-routing fixture (ticket 126), shared by the tap tests and the
 * debug gallery's "Transcript links" section: one plain user turn, and
 * one assistant turn whose first paragraph carries a web link, a mailto
 * link and the five no-op links (a scheme-less path, a scheme-less path
 * with a location, a `zeron-file:` mention, a `zeron-invoke:` skill
 * and an `#anchor`), and whose table is wide enough to scroll — its
 * `table link` is a link **inside a scroller** (the ticket's gap in
 * upstream PR #609). `u1`/`a1` are the ids the tests pin.
 */
val LinkFixture = listOf(
    DebugEntry("u1", true, "Show me the links.", false),
    DebugEntry(
        "a1",
        false,
        """
        Web: [Wikipedia](https://ja.wikipedia.org/wiki/x) and [mail](mailto:dev@example.com).
        Files: [logo](assets/logo.png), [main](src/main.rs:42), [mention](zeron-file:src/lib.rs), [skill](zeron-invoke:7b7d), [anchor](#install).

        | Column header one | Column header two | Column header three | Column header four | Column header five | Link |
        |---|---|---|---|---|---|
        | aaaaaaaaaaaaaaaa | bbbbbbbbbbbbbbbb | cccccccccccccccc | dddddddddddddddd | eeeeeeeeeeeeeeee | [table link](https://example.com/table) |
        """.trimIndent(),
        false,
    ),
)
