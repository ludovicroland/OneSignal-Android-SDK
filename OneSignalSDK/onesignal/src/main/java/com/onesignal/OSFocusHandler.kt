package com.onesignal

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class OSFocusHandler {

    fun resetBackgroundState() {
        backgrounded = false
    }

    fun hasBackgrounded(): Boolean {
        return backgrounded
    }

    fun hasCompleted(): Boolean {
        return completed
    }

    private fun buildConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }

    fun startOnFocusWorker(context: Context) {
        val constraints = buildConstraints()
        val workRequest = OneTimeWorkRequest.Builder(OnFocusWorker::class.java)
            .setConstraints(constraints)
            .setInitialDelay(SYNC_AFTER_BG_DELAY_MS, TimeUnit.MILLISECONDS)
            .addTag(FOCUS_LOST_WORKER_TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                FOCUS_LOST_WORKER_TAG,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
    }

    fun cancelOnFocusWorker(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(FOCUS_LOST_WORKER_TAG)
    }

    class OnFocusWorker(context: Context, workerParams: WorkerParameters) :
        Worker(context, workerParams) {
        override fun doWork(): Result {
            onFocusDoWork()
            return Result.success()
        }
    }

    companion object {
        // We want to perform a on_focus sync as soon as the session is done to report the time
        private val SYNC_AFTER_BG_DELAY_MS: Long = 2000
        val FOCUS_LOST_WORKER_TAG = "FOCUS_LOST_WORKER_TAG"

        private var backgrounded = false
        private var completed = false

        fun onFocusDoWork() {
            val activityLifecycleHandler = ActivityLifecycleListener.getActivityLifecycleHandler()
            if (activityLifecycleHandler == null || activityLifecycleHandler.curActivity == null) {
                OneSignal.setInForeground(false)
            }
            OneSignal.onesignalLog(
                OneSignal.LOG_LEVEL.DEBUG,
                "ActivityLifecycleHandler running AppFocusRunnable"
            )
            backgrounded = true
            OneSignal.onAppLostFocus()
            completed = true
        }
    }
}
