package com.umar.bulkmailer

import android.content.Context

/** Saves what you typed so it's still there next time. Stored privately on the phone only. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("bulkmailer", Context.MODE_PRIVATE)

    var email: String get() = p.getString("email", "")!!; set(v) = p.edit().putString("email", v).apply()
    var appPassword: String get() = p.getString("pw", "")!!; set(v) = p.edit().putString("pw", v).apply()
    var senderName: String get() = p.getString("sender", "")!!; set(v) = p.edit().putString("sender", v).apply()
    var recipients: String get() = p.getString("recipients", "")!!; set(v) = p.edit().putString("recipients", v).apply()
    var subject: String get() = p.getString("subject", "")!!; set(v) = p.edit().putString("subject", v).apply()
    var body: String get() = p.getString("body", "")!!; set(v) = p.edit().putString("body", v).apply()
    var delaySec: String get() = p.getString("delay", "5")!!; set(v) = p.edit().putString("delay", v).apply()
    var copies: String get() = p.getString("copies", "1")!!; set(v) = p.edit().putString("copies", v).apply()
    var roundGapMin: String get() = p.getString("gap", "0")!!; set(v) = p.edit().putString("gap", v).apply()
    var gapUnit: Int get() = p.getInt("gapUnit", 0); set(v) = p.edit().putInt("gapUnit", v).apply()
    var attachments: Set<String> get() = p.getStringSet("att", emptySet())!!; set(v) = p.edit().putStringSet("att", v).apply()
}
