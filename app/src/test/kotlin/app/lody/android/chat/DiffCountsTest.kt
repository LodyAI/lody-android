package app.lody.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiffCountsTest {
    @Test fun theLoadedDiffCountsWin() {
        assertEquals(3 to 1, diffCounts(listedAdded = 9, listedRemoved = 9, loadedAdded = 3, loadedRemoved = 1))
    }

    @Test fun theListCountsFillInWhenTheDiffHasNone() {
        assertEquals(4 to 2, diffCounts(listedAdded = 4, listedRemoved = 2, loadedAdded = null, loadedRemoved = null))
    }

    @Test fun aPartialDiffCountDoesNotOverwriteTheList() {
        assertEquals(4 to 2, diffCounts(listedAdded = 4, listedRemoved = 2, loadedAdded = 3, loadedRemoved = null))
    }

    @Test fun noCountsStayHidden() {
        assertNull(diffCounts(listedAdded = null, listedRemoved = null, loadedAdded = null, loadedRemoved = null))
    }
}
