package dev.framio.lab.labs.time

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.framio.lab.core.Events
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** AlarmManager and JobScheduler round trips: schedule, then record when the system actually ran them. */
object Schedulers {

    data class Fired(val scheduledFor: Long, val firedAt: Long) {
        val lateMs get() = firedAt - scheduledFor
    }

    private val _alarm = MutableStateFlow<Fired?>(null)
    val alarm: StateFlow<Fired?> = _alarm

    private val _job = MutableStateFlow<Fired?>(null)
    val job: StateFlow<Fired?> = _job

    private const val EXTRA_AT = "at"
    private const val JOB_ID = 4242

    /** Exact alarm that also fires in Doze (API 23). */
    fun scheduleAlarm(context: Context, inMs: Long): Long {
        val at = System.currentTimeMillis() + inMs
        _alarm.value = null
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_AT, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        return at
    }

    /** Job that may run between [minMs] and [maxMs] from now. */
    fun scheduleJob(context: Context, minMs: Long, maxMs: Long): Boolean {
        _job.value = null
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, LabJobService::class.java))
            .setMinimumLatency(minMs)
            .setOverrideDeadline(maxMs)
            .setExtras(android.os.PersistableBundle().apply { putLong(EXTRA_AT, System.currentTimeMillis() + minMs) })
            .build()
        return context.getSystemService(JobScheduler::class.java).schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    internal fun onAlarm(context: Context, at: Long) {
        val fired = Fired(at, System.currentTimeMillis())
        _alarm.value = fired
        Events.log("time", "alarm fired ${fired.lateMs} ms after its time")
        Toast.makeText(context, "Hardware Lab: alarm fired (${fired.lateMs} ms late)", Toast.LENGTH_SHORT).show()
    }

    internal fun onJob(at: Long) {
        val fired = Fired(at, System.currentTimeMillis())
        _job.value = fired
        Events.log("time", "job ran ${fired.lateMs} ms after its earliest time")
    }

    internal fun extraAt(intent: Intent) = intent.getLongExtra(EXTRA_AT, 0L)
    internal fun extraAt(params: JobParameters) = params.extras.getLong(EXTRA_AT)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Schedulers.onAlarm(context, Schedulers.extraAt(intent))
}

class LabJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Schedulers.onJob(Schedulers.extraAt(params))
        return false // done, nothing running in the background
    }

    override fun onStopJob(params: JobParameters) = false
}
