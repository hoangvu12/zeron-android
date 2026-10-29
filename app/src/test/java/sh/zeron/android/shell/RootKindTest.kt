package sh.zeron.android.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The root choice (android-ui-parity #35, plain JVM for the merge check):
 * signed out wins over any width, the 700 dp breakpoint (600 dp with a
 * fold) is inclusive, a compact height keeps the phone shell, and the fade
 * groups are Welcome alone vs Phone + Split together.
 */
class RootKindTest {

    @Test
    fun signedOutIsWelcomeWhateverTheWidth() {
        assertEquals(RootKind.Welcome, chooseRoot(signedIn = false, width = 1200.dp))
        assertEquals(RootKind.Welcome, chooseRoot(signedIn = false, width = 320.dp))
    }

    @Test
    fun belowTheBreakpointIsThePhoneShell() {
        assertEquals(RootKind.Phone, chooseRoot(signedIn = true, width = 699.dp))
        assertEquals(RootKind.Phone, chooseRoot(signedIn = true, width = 393.dp))
    }

    @Test
    fun atAndAboveTheBreakpointIsTheSplit() {
        assertEquals(RootKind.Split, chooseRoot(signedIn = true, width = 700.dp))
        // The iPad mini upright.
        assertEquals(RootKind.Split, chooseRoot(signedIn = true, width = 744.dp))
        assertEquals(RootKind.Split, chooseRoot(signedIn = true, width = 1280.dp))
    }

    @Test
    fun theBreakpointsMatchZeronAndWindowSizeClass() {
        assertEquals(700.dp, SplitBreakpoint)
        assertEquals(600.dp, FoldableSplitBreakpoint)
        assertEquals(480.dp, CompactHeight)
    }

    /** #26: an unfolded book foldable splits like the unfolded iPhone Duo (669 pt). */
    @Test
    fun anUnfoldedFoldableSplitsFromSixHundred() {
        // Galaxy Z Fold inner screen upright (~673 × 838): splits with a fold,
        // a plain window that size does not.
        assertEquals(RootKind.Split, chooseRoot(signedIn = true, width = 673.dp, height = 838.dp, foldable = true))
        assertEquals(RootKind.Phone, chooseRoot(signedIn = true, width = 673.dp, height = 838.dp))
        assertEquals(RootKind.Split, chooseRoot(signedIn = true, width = 600.dp, height = 838.dp, foldable = true))
        assertEquals(RootKind.Phone, chooseRoot(signedIn = true, width = 599.dp, height = 838.dp, foldable = true))
        // Half-opened sideways (compact height) stays the phone shell.
        assertEquals(RootKind.Phone, chooseRoot(signedIn = true, width = 838.dp, height = 400.dp, foldable = true))
    }

    /** Wave-10 42: I1 and the user's portrait-tablet decision. */
    @Test
    fun aLandscapePhoneStaysPhoneAndAnUprightTabletSplits() {
        // Pixel 7 sideways: wide, but compact height.
        assertEquals(RootKind.Phone, chooseRoot(signedIn = true, width = 915.dp, height = 400.dp))
        // Pixel Tablet upright (800 × 1280) and sideways.
        assertEquals(RootKind.Split, chooseRoot(signedIn = true, width = 800.dp, height = 1280.dp))
        assertEquals(RootKind.Split, chooseRoot(signedIn = true, width = 1280.dp, height = 800.dp))
        // A small 600 dp tablet upright is still the phone layout.
        assertEquals(RootKind.Phone, chooseRoot(signedIn = true, width = 600.dp, height = 960.dp))
        // Exactly the compact height is regular.
        assertEquals(RootKind.Split, chooseRoot(signedIn = true, width = 900.dp, height = 480.dp))
        assertEquals(RootKind.Welcome, chooseRoot(signedIn = false, width = 915.dp, height = 400.dp))
    }

    @Test
    fun onlyWelcomeFadesPhoneAndSplitShareAGroup() {
        assertEquals(RootGroup.Welcome, RootKind.Welcome.group)
        assertEquals(RootGroup.Shell, RootKind.Phone.group)
        assertEquals(RootGroup.Shell, RootKind.Split.group)
    }
}
