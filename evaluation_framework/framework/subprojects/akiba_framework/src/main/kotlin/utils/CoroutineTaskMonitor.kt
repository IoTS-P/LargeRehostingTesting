package org.iotsplab.akiba.utils

import ghidra.util.exception.CancelledException
import ghidra.util.task.CancelledListener
import ghidra.util.task.TaskMonitor
import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException

/**
 * A wrapper for TaskMonitor that cancels the coroutine job when the task is cancelled.
 *
 * TaskMonitor is widely used in Ghidra tasks, however some of them uses Java Threads to control,
 * which is not controllable by coroutines. To let our modules exit in time, we need to wrap the
 * task to cancel the coroutine job when the task needs to be cancelled.
 */
class CoroutineTaskMonitor (
    private val delegate: TaskMonitor,
    private val job: Job
) : TaskMonitor {

    init {
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                delegate.cancel()
            }
        }
    }

    override fun isCancelled(): Boolean = !job.isActive || delegate.isCancelled

    @Deprecated("Deprecated in Ghidra")
    override fun checkCanceled() {
        if (!job.isActive)
            throw CancelledException("Cancelled by coroutine")
        delegate.checkCancelled()
    }

    override fun cancel() {
        delegate.cancel()
        job.cancel()
    }

    // Delegate all in below

    override fun setMessage(var1: String?) {
        delegate.message = var1
    }
    override fun getMessage(): String? = delegate.message
    override fun setProgress(var1: Long) {
        delegate.progress = var1
    }
    override fun initialize(var1: Long) = delegate.initialize(var1)
    override fun setMaximum(var1: Long) {
        delegate.maximum = var1
    }
    override fun getMaximum(): Long = delegate.maximum
    override fun setIndeterminate(var1: Boolean) {
        delegate.isIndeterminate = var1
    }
    override fun isIndeterminate(): Boolean = delegate.isIndeterminate
    override fun incrementProgress(var1: Long) = delegate.incrementProgress(var1)

    override fun getProgress(): Long = delegate.progress
    override fun addCancelledListener(var1: CancelledListener?) =
        delegate.addCancelledListener(var1)

    override fun removeCancelledListener(var1: CancelledListener?) =
        delegate.removeCancelledListener(var1)

    override fun setCancelEnabled(var1: Boolean) {
        delegate.isCancelEnabled = var1
    }

    override fun isCancelEnabled(): Boolean =
        delegate.isCancelEnabled

    @Deprecated("Deprecated in Ghidra")
    override fun clearCanceled() =
        delegate.clearCancelled()

    override fun setShowProgressValue(var1: Boolean) =
        delegate.setShowProgressValue(var1)

    companion object {
        fun TaskMonitor.asCoroutineAware(job: Job): CoroutineTaskMonitor {
            return CoroutineTaskMonitor(this, job)
        }
    }
}
