package com.reveil.aube.ui

import android.app.TimePickerDialog
import android.content.Context
import java.util.Locale

fun showTimePicker(context: Context, initialMinutes: Int, onPicked: (Int) -> Unit) {
    val hour = initialMinutes / 60
    val minute = initialMinutes % 60
    TimePickerDialog(context, { _, h, m -> onPicked(h * 60 + m) }, hour, minute, true).show()
}

// Locale.US, not the device default: keeps clock digits in plain Western numerals for every
// language (some locales, e.g. Arabic or Hindi, would otherwise format %d with native digits).
fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return String.format(Locale.US, "%02d:%02d", h, m)
}
