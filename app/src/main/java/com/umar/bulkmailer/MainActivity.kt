package com.umar.bulkmailer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.umar.bulkmailer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val attachments = mutableListOf<File>()

    private val pickCsv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importCsv(it) }
    }
    private val pickAttachments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addAttachments(uris)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        b.spinnerGapUnit.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, GAP_UNITS
        )
        loadFields()

        b.btnAppPassword.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords")))
        }
        b.btnImportCsv.setOnClickListener {
            pickCsv.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/csv", "application/vnd.ms-excel"))
        }
        b.btnCheckList.setOnClickListener { showListInfo(parse()) }
        b.btnAddAttachment.setOnClickListener { pickAttachments.launch(arrayOf("*/*")) }
        b.btnClearAttachments.setOnClickListener {
            attachments.forEach { it.parentFile?.deleteRecursively() }
            attachments.clear()
            renderAttachments()
        }
        b.btnPreview.setOnClickListener { preview() }
        b.btnTest.setOnClickListener { sendTest() }
        b.btnSendAll.setOnClickListener { confirmAndSend() }
        b.btnStop.setOnClickListener {
            SendStatus.stopRequested = true
            Toast.makeText(this, "Stopping…", Toast.LENGTH_SHORT).show()
        }
        b.btnRetry.setOnClickListener {
            val failed = SendStatus.state.value.failedRecipients
            if (failed.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle("Retry ${failed.size} failed email(s)?")
                .setPositiveButton("Retry") { _, _ -> startSending(failed, alreadyExpanded = true) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SendStatus.state.collect { render(it) }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    // ---------- fields ----------

    private fun loadFields() {
        b.inputEmail.setText(prefs.email)
        b.inputPassword.setText(prefs.appPassword)
        b.inputSenderName.setText(prefs.senderName)
        b.inputRecipients.setText(prefs.recipients)
        b.inputSubject.setText(prefs.subject)
        b.inputBody.setText(prefs.body)
        b.inputDelay.setText(prefs.delaySec)
        b.inputCopies.setText(prefs.copies)
        b.inputGap.setText(prefs.roundGapMin)
        b.spinnerGapUnit.setSelection(prefs.gapUnit.coerceIn(0, GAP_UNITS.lastIndex))
        attachments.clear()
        attachments += prefs.attachments.map { File(it) }.filter { it.exists() }
        renderAttachments()
    }

    private fun saveFields() {
        prefs.email = text(b.inputEmail)
        prefs.appPassword = text(b.inputPassword)
        prefs.senderName = text(b.inputSenderName)
        prefs.recipients = b.inputRecipients.text?.toString().orEmpty()
        prefs.subject = b.inputSubject.text?.toString().orEmpty()
        prefs.body = b.inputBody.text?.toString().orEmpty()
        prefs.delaySec = text(b.inputDelay)
        prefs.copies = text(b.inputCopies)
        prefs.roundGapMin = text(b.inputGap)
        prefs.gapUnit = b.spinnerGapUnit.selectedItemPosition.coerceAtLeast(0)
        prefs.attachments = attachments.map { it.absolutePath }.toSet()
    }

    private fun text(v: android.widget.EditText) = v.text?.toString()?.trim().orEmpty()

    private fun account(): Account? {
        val email = text(b.inputEmail)
        val pw = text(b.inputPassword)
        if (email.isEmpty() || pw.isEmpty()) {
            alert("Gmail needed", "Enter your Gmail address and App Password in section 1 first.")
            return null
        }
        return Account(email, pw, text(b.inputSenderName))
    }

    private fun parse() = RecipientParser.parse(b.inputRecipients.text?.toString().orEmpty())

    private fun delayMs() = (text(b.inputDelay).toLongOrNull() ?: 5L).coerceIn(0, 3600) * 1000L
    private fun copies() = (text(b.inputCopies).toIntOrNull() ?: 1).coerceIn(1, MAX_COPIES)
    /** Wait between rounds of copies, in the chosen unit (minutes / hours / days). Minimum 1 minute when repeating. */
    private fun roundGapMs(): Long {
        val n = (text(b.inputGap).toLongOrNull() ?: 0L).coerceIn(0, 100_000)
        val unitMs = GAP_UNIT_MS.getOrElse(b.spinnerGapUnit.selectedItemPosition) { GAP_UNIT_MS[0] }
        val ms = n * unitMs
        return if (copies() > 1) maxOf(ms, 60_000L) else ms
    }

    private fun describeGap(ms: Long): String = when {
        ms % 86_400_000L == 0L -> "${ms / 86_400_000L} day(s)"
        ms % 3_600_000L == 0L -> "${ms / 3_600_000L} hour(s)"
        else -> "${ms / 60_000L} minute(s)"
    }

    // ---------- recipients ----------

    private fun importCsv(uri: Uri) {
        try {
            val txt = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            b.inputRecipients.setText(txt.trim())
            showListInfo(parse())
        } catch (e: Exception) {
            alert("Couldn't read file", "Save the spreadsheet as CSV and try again.\n\n${e.message}")
        }
    }

    private fun showListInfo(r: RecipientParser.Result) {
        val sb = StringBuilder("${r.recipients.size} valid recipient(s)")
        if (r.headers.isNotEmpty()) sb.append("\nColumns: ").append(r.headers.joinToString(", ") { "{$it}" })
        if (r.duplicates > 0) sb.append("\n${r.duplicates} duplicate(s) skipped")
        if (r.invalid.isNotEmpty()) {
            sb.append("\n${r.invalid.size} line(s) skipped (no valid email):")
            r.invalid.take(5).forEach { sb.append("\n  • ").append(it.take(60)) }
            if (r.invalid.size > 5) sb.append("\n  …")
        }
        b.textListInfo.text = sb.toString()
    }

    // ---------- attachments ----------

    private fun addAttachments(uris: List<Uri>) {
        for (uri in uris) {
            try {
                val name = displayName(uri) ?: "attachment"
                val dir = File(cacheDir, "attachments/${UUID.randomUUID()}").apply { mkdirs() }
                val out = File(dir, name)
                contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                attachments += out
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't add file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        renderAttachments()
        val totalMb = attachments.sumOf { it.length() } / (1024.0 * 1024.0)
        if (totalMb > 24) alert("Attachments too big", "Gmail allows about 25 MB per email. You have %.1f MB.".format(totalMb))
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.replace("/", "_")

    private fun renderAttachments() {
        b.textAttachments.text = if (attachments.isEmpty()) "No attachments"
        else attachments.joinToString("\n") { "📎 ${it.name}  (${it.length() / 1024} KB)" }
    }

    // ---------- preview / test ----------

    private fun preview() {
        val list = parse().recipients
        if (list.isEmpty()) { alert("No recipients", "Add at least one email address in section 2."); return }
        val r = list.first().withCopy(1, copies())
        val subject = Template.fill(b.inputSubject.text?.toString().orEmpty(), r)
        val body = Template.fill(b.inputBody.text?.toString().orEmpty(), r)
        alert("Preview for ${r.email}", "Subject: $subject\n\n$body")
    }

    private fun sendTest() {
        val acc = account() ?: return
        saveFields()
        val sample = (parse().recipients.firstOrNull() ?: Recipient(acc.email, mapOf("email" to acc.email)))
            .withCopy(1, copies())
        val subject = "[TEST] " + Template.fill(b.inputSubject.text?.toString().orEmpty(), sample)
        val body = Template.fill(b.inputBody.text?.toString().orEmpty(), sample)
        val files = attachments.toList()
        b.btnTest.isEnabled = false
        b.btnTest.text = "Sending…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val s = MailSender(acc)
                    try { s.send(acc.email, subject, body, files) } finally { s.close() }
                }
            }
            b.btnTest.text = "Test to me"
            b.btnTest.isEnabled = !SendStatus.state.value.running
            result.fold(
                onSuccess = { alert("Test sent", "Check the inbox of ${acc.email}. It used ${sample.email}'s details for the fill-ins.") },
                onFailure = { alert("Test failed", MailSender.friendly(it)) }
            )
        }
    }

    // ---------- send all ----------

    private fun confirmAndSend() {
        val acc = account() ?: return
        val parsed = parse()
        if (parsed.recipients.isEmpty()) { alert("No recipients", "Add at least one email address in section 2."); return }
        val subject = b.inputSubject.text?.toString().orEmpty()
        if (subject.isBlank()) { alert("No subject", "Add a subject in section 3."); return }

        val n = parsed.recipients.size
        val c = copies()
        val total = n * c
        val sample = parsed.recipients.first()
        val unknown = Template.unknownKeys(subject + "\n" + b.inputBody.text, sample)

        val msg = StringBuilder()
        msg.append("From: ${acc.email}\n")
        msg.append(if (c > 1) "$n recipient(s) × $c copies = $total emails\n" else "$n email(s)\n")
        msg.append("${delayMs() / 1000}s between emails")
        if (c > 1) {
            val gap = roundGapMs()
            msg.append(", then a new round every ${describeGap(gap)}")
            val endMs = System.currentTimeMillis() + gap * (c - 1)
            msg.append(".\nLast round around ")
                .append(java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.UK).format(java.util.Date(endMs)))
            msg.append(".\nKeep the phone on and connected until then; the app shows a notification while it's running")
        }
        msg.append(".\n")
        if (attachments.isNotEmpty()) msg.append("${attachments.size} attachment(s).\n")
        if (unknown.isNotEmpty()) msg.append("\n⚠ These fill-ins don't match any column and will be sent as typed: ${unknown.joinToString { "{$it}" }}\n")
        if (total > 400) msg.append("\n⚠ Personal Gmail accounts can send about 500 emails a day. Gmail may block sending after that.\n")
        if (c > 1) msg.append("\nRepeat copies of the same email can get your Gmail flagged as spam. Keep copies low and only send them to people who expect them.\n")

        MaterialAlertDialogBuilder(this)
            .setTitle("Send $total email(s)?")
            .setMessage(msg.toString())
            .setPositiveButton("Send") { _, _ -> startSending(parsed.recipients, alreadyExpanded = false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** [alreadyExpanded] = list already has copy numbers (retry), so send each once. */
    private fun startSending(recipients: List<Recipient>, alreadyExpanded: Boolean) {
        val acc = account() ?: return
        if (SendStatus.state.value.running) return
        saveFields()
        SendStatus.stopRequested = false
        SendStatus.pendingJob = MailJob(
            account = acc,
            recipients = recipients,
            subject = b.inputSubject.text?.toString().orEmpty(),
            body = b.inputBody.text?.toString().orEmpty(),
            attachments = attachments.toList(),
            delayMs = delayMs(),
            copies = if (alreadyExpanded) 1 else copies(),
            roundGapMs = if (alreadyExpanded) 0 else roundGapMs()
        )
        if (alreadyExpanded) {
            // Keep each failed email's original copy number for {copy}.
            SendStatus.pendingJob = SendStatus.pendingJob?.copy(recipients = recipients.map { it.preserveCopy() })
        }
        ContextCompat.startForegroundService(this, Intent(this, SendService::class.java))
    }

    private fun render(s: SendStatus.State) {
        b.progress.max = maxOf(s.total, 1)
        b.progress.progress = s.sent + s.failed
        b.textStatus.text = when {
            s.running && s.waitingUntil > 0 -> "Waiting for next round… ${s.sent + s.failed} of ${s.total} done"
            s.running -> "Sending… ${s.sent + s.failed} of ${s.total}  (sent ${s.sent}, failed ${s.failed})"
            s.total > 0 -> "Finished: ${s.sent} sent, ${s.failed} failed"
            else -> "Not started"
        }
        b.textLog.text = s.log.takeLast(200).reversed().joinToString("\n")
        b.btnSendAll.isEnabled = !s.running
        b.btnTest.isEnabled = !s.running
        b.btnStop.isEnabled = s.running
        b.btnRetry.isEnabled = !s.running && s.failedRecipients.isNotEmpty()
    }

    private fun alert(title: String, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    companion object {
        const val MAX_COPIES = 100
        val GAP_UNITS = listOf("minutes", "hours", "days")
        val GAP_UNIT_MS = listOf(60_000L, 3_600_000L, 86_400_000L)
    }
}

/** Marks a recipient so the service's withCopy() doesn't overwrite its original {copy}/{copies}. */
private fun Recipient.preserveCopy(): Recipient = copy(fields = fields + ("__keepcopy" to "1"))
