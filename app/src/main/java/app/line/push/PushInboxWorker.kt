package app.line.push

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.line.CallService
import app.line.CallState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class PushInboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (runAttemptCount >= MAX_ATTEMPTS) return Result.failure()
        return try {
            if (!bindUntilInboxSynchronized()) return retryOrFail()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retryOrFail()
        }
    }

    private suspend fun bindUntilInboxSynchronized(): Boolean {
        val context = applicationContext
        val serviceRef = AtomicReference<CallService?>()
        val observerRef = AtomicReference<((CallState) -> Unit)?>(null)
        val connectionRef = AtomicReference<ServiceConnection?>()
        var bound = false
        try {
            val connected = withTimeoutOrNull(SERVICE_CONNECT_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: android.content.ComponentName, binder: IBinder) {
                            if (!continuation.isActive) return
                            val service = (binder as? CallService.LocalBinder)?.service
                            if (service == null) {
                                continuation.resume(false)
                                return
                            }
                            serviceRef.set(service)
                            val observer: (CallState) -> Unit = { state ->
                                if (state.online && continuation.isActive) continuation.resume(true)
                            }
                            observerRef.set(observer)
                            service.observe(observer)
                        }

                        override fun onServiceDisconnected(name: android.content.ComponentName) {
                            if (continuation.isActive) continuation.resume(false)
                        }

                        override fun onBindingDied(name: android.content.ComponentName) {
                            if (continuation.isActive) continuation.resume(false)
                        }

                        override fun onNullBinding(name: android.content.ComponentName) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                    connectionRef.set(connection)
                    try {
                        bound = context.bindService(Intent(context, CallService::class.java), connection, Context.BIND_AUTO_CREATE)
                        if (!bound && continuation.isActive) continuation.resume(false)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false
            if (!connected) return false
            var inboxSynced = false
            withTimeoutOrNull(INBOX_SYNC_TIMEOUT_MILLIS) {
                while (!inboxSynced) {
                    inboxSynced = withContext(Dispatchers.Main.immediate) {
                        serviceRef.get()?.inboxSynchronized() == true
                    }
                    if (!inboxSynced) {
                        delay(INBOX_SYNC_POLL_MILLIS)
                    }
                }
            }
            return inboxSynced
        } finally {
            withContext(Dispatchers.Main.immediate) {
                serviceRef.get()?.let { service -> observerRef.get()?.let(service::removeObserver) }
                if (bound) connectionRef.get()?.let { connection -> runCatching { context.unbindService(connection) } }
            }
        }
    }

    private fun retryOrFail(): Result = if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        private const val UNIQUE_WORK = "line-push-inbox-sync"
        private const val MAX_ATTEMPTS = 3
        private const val SERVICE_CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val INBOX_SYNC_TIMEOUT_MILLIS = 10_000L
        private const val INBOX_SYNC_POLL_MILLIS = 200L

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushInboxWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
