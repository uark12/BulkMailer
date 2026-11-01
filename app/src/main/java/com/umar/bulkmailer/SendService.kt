package com.umar.bulkmailer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Foreground service so sending keeps going with the screen off or the app in the background. */
class SendService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private val time = SimpleDateFormat("HH:mm:ss", Locale.UK)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val job = SendStatus.pendingJob
        val alreadyRunning = SendStatus.state.value.running
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, progressNotif("Starting…", 0, 1), type)

        if (job == null || alreadyRunning) {
            if (!alreadyRunning) stopSelf()
            return START_NOT_STICKY
        }
        SendStatus.pendingJob = null
        val total = job.recipients.size * job.copies
        SendStatus.update { SendStatus.State(running = true, total = total) }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BulkMailer:send").apply {
            // Long enough for the whole schedule (rounds can be days apart) plus slack for sending.
            val expected = job.roundGapMs * (job.copies - 1) + job.delayMs * total + total * 30_000L
            acquire(expected + 60 * 60 * 1000L)
        }

        scope.launch { run(job, total) }
        return START_NOT_STICKY
    }

    private suspend fun run(job: MailJob, total: Int) {
        val sender = MailSender(job.account)
        var done = 0
        var stoppedEarly = false
        val failed = mutableListOf<Recipient>()

        outer@ for (round in 1..job.copies) {
            if (job.copies > 1) SendStatus.log("— Round $round of ${job.copies} —")
            for ((i, base) in job.recipients.withIndex()) {
                if (SendStatus.stopRequested) { stoppedEarly = true; break@outer }
                val r = base.withCopy(round, job.copies)
                try {
                    sender.send(r.email, Template.fill(job.subject, r), Template.fill(job.body, r), job.attachments)
                    SendStatus.update { it.copy(sent = it.sent + 1) }
                    SendStatus.log("${time.format(Date())}  ✓ ${r.email}")
                } catch (e: Exception) {
                    sender.close()
                    failed += r
                    SendStatus.update { it.copy(failed = it.failed + 1, failedRecipients = failed.toList()) }
                    SendStatus.log("${time.format(Date())}  ✗ ${r.email} — ${MailSender.friendly(e)}")
                    if (MailSender.isFatal(e)) {
                        SendStatus.log("Stopped: ${MailSender.friendly(e)}")
                        // Everything not yet sent goes on the retry list.
                        val remaining = mutableListOf<Recipient>()
                        job.recipients.drop(i + 1).forEach { remaining += it.withCopy(round, job.copies) }
                        for (later in round + 1..job.copies) job.recipients.forEach { remaining += it.withCopy(later, job.copies) }
                        failed += remaining
                        SendStatus.update {
                            it.copy(failed = it.failed + remaining.size, failedRecipients = failed.toList())
                        }
                        stoppedEarly = true
                        break@outer
                    }
                }
                done++
                notify(progressNotif("Sent ${SendStatus.state.value.sent}, failed ${SendStatus.state.value.failed}", done, total))

                val lastInRound = i == job.recipients.lastIndex
                val lastOverall = lastInRound && round == job.copies
                if (!lastOverall) {
                    val wait = if (lastInRound && job.roundGapMs > 0) job.roundGapMs else job.delayMs
                    if (lastInRound && job.roundGapMs > 0) {
                        val until = System.currentTimeMillis() + wait
                        SendStatus.update { it.copy(waitingUntil = until) }
                        SendStatus.log("Waiting until ${time.format(Date(until))} before round ${round + 1}")
                        notify(progressNotif("Next round at ${time.format(Date(until))}", done, total))
                    }
                    if (!waitStoppable(wait)) { stoppedEarly = true; break@outer }
                    SendStatus.update { it.copy(waitingUntil = 0L) }
                }
            }
        }
        sender.close()

        if (stoppedEarly && SendStatus.stopRequested) SendStatus.log("Stopped by you.")
        val s = SendStatus.state.value
        SendStatus.update { it.copy(running = false, waitingUntil = 0L) }
        SendStatus.log("Finished: ${s.sent} sent, ${s.failed} failed.")

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        notify(doneNotif("Done: ${s.sent} sent, ${s.failed} failed"))
        wakeLock?.let { if (it.isHeld) it.release() }
        stopSelf()
    }

    /** Waits in small steps so Stop works immediately. Returns false if stopped. */
    private suspend fun waitStoppable(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (SendStatus.stopRequested) return false
            val step = minOf(250L, left)
            delay(step)
            left -= step
        }
        return !SendStatus.stopRequested
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Sending emails", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progressNotif(text: String, done: Int, total: Int) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Bulk Mailer: sending $done of $total")
            .setContentText(text)
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun doneNotif(text: String) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Bulk Mailer")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    private fun notify(n: android.app.Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (_: SecurityException) { }
    }

    companion object {
        const val CHANNEL = "sending"
        const val NOTIF_ID = 42
    }
}
