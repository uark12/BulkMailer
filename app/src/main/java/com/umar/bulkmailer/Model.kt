package com.umar.bulkmailer

import java.io.File

/** One person on the list. [fields] holds every column (lower-case header -> value), always including "email". */
data class Recipient(val email: String, val fields: Map<String, String>) {
    fun value(key: String): String? {
        fields[key]?.let { return it }
        return when (key) {
            "firstname", "first_name", "first name", "first" ->
                fields["name"]?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            else -> null
        }
    }

    fun knows(key: String): Boolean = value(key) != null || key in setOf("firstname", "first_name", "first name", "first")

    fun withCopy(n: Int, total: Int): Recipient =
        if (fields.containsKey("__keepcopy")) this else this.copy(fields = fields + mapOf("copy" to n.toString(), "copies" to total.toString()))
}

data class Account(val email: String, val appPassword: String, val senderName: String)

data class MailJob(
    val account: Account,
    val recipients: List<Recipient>,
    val subject: String,
    val body: String,
    val attachments: List<File>,
    val delayMs: Long,
    val copies: Int,
    val roundGapMs: Long
)

object Template {
    private val regex = Regex("\\{([^{}]+)\\}")

    /** Replaces {column}, {firstname}, {copy}, {copies} and {column|fallback}. Unknown placeholders are left as typed. */
    fun fill(text: String, r: Recipient): String = regex.replace(text) { m ->
        val parts = m.groupValues[1].split("|", limit = 2)
        val key = parts[0].trim().lowercase()
        val fallback = parts.getOrNull(1)
        val v = r.value(key)
        when {
            !v.isNullOrBlank() -> v
            fallback != null -> fallback
            r.knows(key) -> ""
            else -> m.value
        }
    }

    /** Placeholders without a fallback that the given recipient can't fill. */
    fun unknownKeys(text: String, sample: Recipient): Set<String> =
        regex.findAll(text).mapNotNull { m ->
            val parts = m.groupValues[1].split("|", limit = 2)
            val key = parts[0].trim().lowercase()
            if (parts.size == 1 && !sample.knows(key) && key != "copy" && key != "copies") key else null
        }.toSet()
}
