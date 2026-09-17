package com.reveil.aube.kiosk

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "AubeLockTaskExit"
private const val RETRY_DELAY_MS = 200L
private const val MAX_ATTEMPTS = 25

/**
 * Leaves lock task mode and finishes [this], for the pinned screens (the alarm screen, the
 * night screen).
 *
 * `stopLockTask()` is a one-way binder call: it returns before the system has actually taken
 * the task out of lock task mode. A `finish()` issued right after it is a regular (two-way)
 * call and can overtake it — and while a task is still the root of the lock task, the system
 * refuses to finish its root activity, silently: `finish()` returns with `isFinishing` still
 * false, and the screen just stays. Confirmed on a real device via logcat ("Not finishing
 * task in lock task mode", logged 1 ms *before* the task's own removal from the lock task
 * list, from the same thread, in that order). So this retries the finish, briefly, until it
 * takes — instead of assuming the first one did.
 */
fun Activity.exitLockTaskAndFinish() {
    if (isFinishing) return
    try {
        stopLockTask()
    } catch (_: IllegalArgumentException) {
        // Wasn't pinned (startLockTask failed earlier, or already unpinned) — fine.
    }
    finishUntilItTakes(attempt = 1)
}

private fun Activity.finishUntilItTakes(attempt: Int) {
    finish()
    if (isFinishing || isDestroyed) return
    if (attempt >= MAX_ATTEMPTS) {
        Log.w(TAG, "finish() still refused after $attempt attempts, giving up")
        return
    }
    Handler(Looper.getMainLooper()).postDelayed({ finishUntilItTakes(attempt + 1) }, RETRY_DELAY_MS)
}
