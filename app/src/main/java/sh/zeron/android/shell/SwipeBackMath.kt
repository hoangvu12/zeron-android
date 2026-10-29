package sh.zeron.android.shell

import androidx.compose.ui.unit.dp
import sh.zeron.android.design.inSystemGestureEdge
import kotlin.math.abs

/**
 * The pure half of the in-app swipe-back (#135): where a pan may start,
 * when it claims the pointer, how far the pop has come and whether a
 * release commits. No density, no snapshot state, so the plain-JVM
 * [SwipeBackMathTest] pins every branch.
 *
 * iOS: Zeron iOS (deployment target 26.0) uses stock `UINavigationController`s
 * and never touches `interactivePopGestureRecognizer` or
 * `interactiveContentPopGestureRecognizer`, so it gets UIKit's defaults —
 * the edge pan **and** iOS 26's full-width content pop, on every stack
 * above its root. The content pop yields to the content's own gestures
 * (row swipe actions, horizontal scroll views, visual scrubs); here that
 * is "a child that consumed the drag first wins" ([NavStackHost]).
 *
 * The `\*` values are Android-side choices (UIKit exposes none of its pop
 * metrics).
 */
object SwipeBackTokens {

    /**
     * The claim slop as a multiple of the platform touch slop. (\*) Twice
     * the slop every child detector uses (scrollers, pagers, the switch,
     * 8 dp row swipes), so a child always decides first and wins a
     * horizontal drag it wants.
     */
    const val SlopFactor = 2f

    /** Claim iff the travel toward the pop is > [LockRatio] · |dy|. (\*) */
    const val LockRatio = 1.5f

    /** A release at least this fast decides on its own: toward the pop commits, away cancels. (\*) per second */
    val CommitVelocity = 300.dp

    /** Otherwise the pop commits once the page has travelled this fraction of the width. (\*) */
    const val CommitFraction = 0.5f
}

/** What the pan decides from the travel so far. */
enum class SwipeBackLock {
    /** Inside the slop on both axes: keep watching, move nothing. */
    Undecided,

    /** Clearly toward the pop and past the slop: claim and begin the pop. */
    Pop,

    /** Vertical, diagonal or away from the pop: leave the pointer alone for good. */
    Reject,
}

/**
 * Whether a pan may start at window point ([x], [y]). Not in the
 * system's back-gesture edges ([leftEdge]/[rightEdge],
 * `WindowInsets.systemGestures`; 0 on 3-button navigation) — the system's
 * predictive back owns those and drives the same pop, so one swipe never
 * pops twice — and not in the bottom gesture area ([bottomEdge]: the
 * home/app-switch swipe, or the 3-button bar).
 */
fun swipeBackMayStart(
    x: Float,
    y: Float,
    windowWidth: Float,
    windowHeight: Float,
    leftEdge: Float,
    rightEdge: Float,
    bottomEdge: Float,
): Boolean = !inSystemGestureEdge(x, windowWidth, leftEdge, rightEdge) && y < windowHeight - bottomEdge

/**
 * The claim decision from the total travel ([dx], [dy] physical px since
 * the down). The pop runs toward the trailing edge: right in LTR, left in
 * [rtl].
 */
fun swipeBackLock(dx: Float, dy: Float, slopPx: Float, rtl: Boolean): SwipeBackLock {
    if (abs(dx) <= slopPx && abs(dy) <= slopPx) return SwipeBackLock.Undecided
    val toward = if (rtl) -dx else dx
    return if (toward > slopPx && toward > SwipeBackTokens.LockRatio * abs(dy)) {
        SwipeBackLock.Pop
    } else {
        SwipeBackLock.Reject
    }
}

/**
 * The pop's progress for a physical travel [dx] in a [widthPx]-wide host:
 * the page follows the finger 1:1 (the travel before the claim counts, as
 * a UIKit pan's translation does), clamped to [0, 1].
 */
fun swipeBackProgress(dx: Float, widthPx: Float, rtl: Boolean): Float {
    if (widthPx <= 0f) return 0f
    val toward = if (rtl) -dx else dx
    return (toward / widthPx).coerceIn(0f, 1f)
}

/**
 * The release: [velocity] is px/s **toward the pop** (already mapped
 * through RTL), [commitVelocityPx] is [SwipeBackTokens.CommitVelocity] in
 * px. A fling decides by its direction; otherwise past
 * [SwipeBackTokens.CommitFraction] commits.
 */
fun swipeBackCommits(progress: Float, velocity: Float, commitVelocityPx: Float): Boolean = when {
    velocity >= commitVelocityPx -> true
    velocity <= -commitVelocityPx -> false
    else -> progress >= SwipeBackTokens.CommitFraction
}
