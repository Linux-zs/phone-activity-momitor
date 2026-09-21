package com.zerui.safesmsprobe.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Migration tombstone: persisted v0.3 work must never send a webhook after upgrade. */
class WeComSendWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
