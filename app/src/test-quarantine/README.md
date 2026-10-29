# Quarantined tests

Roboco's JVM (`test/`) and instrumented (`androidTest/`) tests, moved here in
M0 (#9). They target Roboco's core and fakes, so they are **not compiled**:
`test-quarantine` is not a Gradle source set.

Paths mirror the original source sets: `test-quarantine/test/java/…` came
from `app/src/test/java/…`, and `test-quarantine/androidTest/java/…` came from
`app/src/androidTest/java/…`.

Revive rule (docs/SPEC.md §4.4): the ticket that ports an area moves that
area's tests back with `git mv` and fixes or rewrites them in the same PR.
