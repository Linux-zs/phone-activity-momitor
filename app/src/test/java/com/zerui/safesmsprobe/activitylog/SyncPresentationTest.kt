package com.zerui.safesmsprobe.activitylog

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPresentationTest {
    @Test fun submittedWorkNeverReusesPreviousSuccess() {
        assertEquals(SyncPhase.WAITING, SyncPresentation.phase(true, false, false, true, 0, 1234, "", true))
        assertEquals(SyncPhase.WAITING, SyncPresentation.phase(true, false, true, true, 0, 1234, ""))
    }
    @Test fun offlineBacklogWaitsForNetwork() {
        assertEquals(SyncPhase.NETWORK, SyncPresentation.phase(true, false, true, false, 19, 1234, "network"))
    }
    @Test fun retryExecutionTakesPrecedenceOverOldFailure() {
        assertEquals(SyncPhase.RUNNING, SyncPresentation.phase(true, true, false, true, 19, 1234, "auth"))
        assertEquals(SyncPhase.FAILED, SyncPresentation.phase(true, false, true, true, 19, 1234, "auth"))
    }
    @Test fun pauseDoesNotClaimAnActiveSync() {
        assertEquals(SyncPhase.PAUSED, SyncPresentation.phase(false, true, true, true, 19, 1234, ""))
    }
    @Test fun firstInstallIsNotZeroDataSuccess() {
        assertEquals(SyncPhase.IDLE, SyncPresentation.phase(true, false, false, true, 0, 0, ""))
    }
    @Test fun onlyAcknowledgedAndDrainedWorkIsSuccessful() {
        assertEquals(SyncPhase.SUCCESS, SyncPresentation.phase(true, false, false, true, 0, 1234, ""))
        assertEquals(SyncPhase.WAITING, SyncPresentation.phase(true, false, false, true, 1, 1234, ""))
        assertEquals(SyncPhase.FAILED, SyncPresentation.phase(true, false, false, true, 0, 1234, "network"))
    }
}
