package com.zerui.safesmsprobe.activitylog

enum class SyncPhase(val label: String) {
    PAUSED("同步已暂停"), WAITING("等待执行"), NETWORK("等待网络"),
    RUNNING("同步中"), SUCCESS("同步成功"), FAILED("同步失败"), IDLE("尚未同步")
}
object SyncPresentation {
    fun phase(enabled: Boolean, running: Boolean, queued: Boolean, online: Boolean,
              pending: Long, lastUpload: Long, error: String, submitted: Boolean = false): SyncPhase = when {
        !enabled -> SyncPhase.PAUSED
        running -> SyncPhase.RUNNING
        submitted -> SyncPhase.WAITING
        !online && (queued || pending > 0) -> SyncPhase.NETWORK
        error.isNotBlank() -> SyncPhase.FAILED
        queued || pending > 0 -> SyncPhase.WAITING
        lastUpload > 0 -> SyncPhase.SUCCESS
        else -> SyncPhase.IDLE
    }
}
