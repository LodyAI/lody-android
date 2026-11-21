package app.lody.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiffScopeArgsTest {
    @Test fun branchKeepsTheMachineDefaultBySendingNoScope() {
        assertEquals(1, workspaceChangesArgs("s", DiffScope.Branch).length())
        assertEquals(2, fileDiffArgs("s", "a.ts", DiffScope.Branch).length())
        assertNull(DiffScope.Branch.wire)
    }

    @Test fun uncommittedScopeIsSentAfterTheNativeArguments() {
        assertEquals("uncommitted", workspaceChangesArgs("s", DiffScope.Uncommitted).getString(1))
        assertEquals("uncommitted", fileDiffArgs("s", "a.ts", DiffScope.Uncommitted).getString(2))
    }

    @Test fun aCallerWithoutAScopeAlsoSendsNone() {
        assertEquals(1, workspaceChangesArgs("s", null).length())
        assertEquals(2, fileDiffArgs("s", "a.ts", null).length())
    }
}
