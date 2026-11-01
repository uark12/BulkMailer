package com.umar.bulkmailer

import android.util.Patterns

/**
 * Accepts either:
 *  - one person per line: "email, name, anything else"  (or "Name <email>")
 *  - a CSV with a header row, e.g. "email,name,company"  (comma, semicolon or tab separated)
 */
object RecipientParser {

    data class Result(
        val recipients: List<Recipient>,
        val invalid: List<String>,
        val headers: List<String>,
        val duplicates: Int
    )

    private val angle = Regex("^(.*)<\\s*([^<>\\s]+@[^<>\\s]+)\\s*>$")

    fun parse(text: String): Result {
        val lines = text.replace("﻿", "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Result(emptyList(), emptyList(), emptyList(), 0)

        val delim = detectDelimiter(lines.first())
        val rows = lines.map { splitLine(it, delim) }
        val hasHeader = rows.first().none { it.contains("@") }

        val headers = if (hasHeader) rows.first().map { normaliseHeader(it) } else emptyList()
        val startIndex = if (hasHeader) 1 else 0
        val emailCol = headers.indexOfFirst { it == "email" || it.contains("email") || it.contains("e-mail") }

        val out = mutableListOf<Recipient>()
        val invalid = mutableListOf<String>()
        val seen = HashSet<String>()
        var duplicates = 0

        for (i in startIndex until rows.size) {
            val row = rows[i]
            val idx = if (emailCol >= 0) emailCol else row.indexOfFirst { it.contains("@") }
            val rawEmail = row.getOrNull(idx)?.trim()?.trim('"', '\'')
            val match = rawEmail?.let { angle.find(it) }
            val angleName: String? = match?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            val e: String? = match?.groupValues?.get(2) ?: rawEmail
            if (e == null || !Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
                invalid += lines[i]
                continue
            }
            if (!seen.add(e.lowercase())) {
                duplicates++
                continue
            }

            val fields = mutableMapOf<String, String>()
            if (hasHeader) {
                headers.forEachIndexed { c, h -> if (h.isNotEmpty()) fields[h] = row.getOrNull(c)?.trim().orEmpty() }
            } else {
                val others = row.filterIndexed { c, _ -> c != idx }
                val name = angleName?.takeIf { it.isNotEmpty() } ?: others.getOrNull(0)
                name?.let { fields["name"] = it.trim() }
                others.drop(if (angleName.isNullOrEmpty()) 1 else 0)
                    .forEachIndexed { c, v -> fields["col${c + 3}"] = v.trim() }
            }
            if (!fields.containsKey("name") && !angleName.isNullOrEmpty()) fields["name"] = angleName
            fields["email"] = e
            out += Recipient(e, fields)
        }
        return Result(out, invalid, headers.filter { it.isNotEmpty() }, duplicates)
    }

    private fun normaliseHeader(h: String): String =
        h.trim().trim('"', '\'').lowercase().let { if (it == "e-mail" || it == "email address") "email" else it }

    private fun detectDelimiter(line: String): Char = when {
        '\t' in line -> '\t'
        ';' in line && ',' !in line -> ';'
        else -> ','
    }

    private fun splitLine(line: String, d: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == d && !inQuotes -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }
}
