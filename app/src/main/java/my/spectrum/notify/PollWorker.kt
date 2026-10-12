package my.spectrum.notify

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class PollWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Checker.run(applicationContext)  // handles its own errors and failure alerts
        return Result.success()
    }
}
