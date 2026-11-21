package app.lody.android.chat

import app.lody.android.runtime.DiffScope
import app.lody.android.runtime.WorkspaceChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDiffTotalsTest {
    @Test fun sumsEveryFilesLineStats() {
        assertEquals(
            WorkspaceDiffTotals(added = 7, removed = 3, files = 2),
            workspaceDiffTotals(
                listOf(
                    WorkspaceChange("a.ts", added = 5, removed = 2),
                    WorkspaceChange("b.ts", added = 2, removed = 1),
                ),
            ),
        )
    }

    @Test fun aFileWithoutBothCountsHidesTheTotal() {
        assertNull(workspaceDiffTotals(listOf(WorkspaceChange("a.ts", added = 5, removed = 2), WorkspaceChange("a.png"))))
        assertNull(workspaceDiffTotals(listOf(WorkspaceChange("a.ts", added = 1, removed = null))))
    }

    @Test fun anEmptyListIsZeroFiles() {
        assertEquals(WorkspaceDiffTotals(added = 0, removed = 0, files = 0), workspaceDiffTotals(emptyList()))
    }

    @Test fun theCatalogShowsBothScopes() {
        assertEquals(
            listOf(DiffScope.Branch, DiffScope.Uncommitted),
            workspaceDiffScopes(catalogHasDiff = true, liveFiles = 0),
        )
    }

    @Test fun aLiveUncommittedListAddsItsRowBeforeTheCatalogCatchesUp() {
        assertEquals(
            listOf(DiffScope.Uncommitted),
            workspaceDiffScopes(catalogHasDiff = false, liveFiles = 2),
        )
    }

    @Test fun aCleanCatalogAndEmptyListShowNoRows() {
        assertTrue(workspaceDiffScopes(catalogHasDiff = false, liveFiles = 0).isEmpty())
    }
}
