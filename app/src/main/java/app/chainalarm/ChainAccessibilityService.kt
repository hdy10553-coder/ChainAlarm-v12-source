package app.chainalarm

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * Recognises alarms added by hand in the Clock app and starts a timer to the next alarm.
 * Logic: a change of "next alarm" counts as manual when the Clock app was active in the last
 * 20 s, this app is not creating alarms itself, and the next alarm changed (sooner, later or new).
 */
class ChainAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ChainAccessibilityService? = null

        // When an alarm rings, start a timer to the next alarm in the chain.
        const val CHAIN_TIMERS = true
        // Only if the next alarm is at most this many minutes after the one that rang.
        const val CHAIN_MAX_GAP_MIN = 60L
        // Only alarms owned by the DeskClock app may trigger or change an auto timer.
        const val ONLY_DESKCLOCK = true

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val cn = ComponentName(ctx, ChainAccessibilityService::class.java)
            val a = cn.flattenToString()
            val b = cn.flattenToShortString()
            for (s in flat.split(':')) {
                if (s.equals(a, ignoreCase = true) || s.equals(b, ignoreCase = true)) return true
            }
            return false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastDeskEvent = 0L
    private var lastKnown = 0L
    private var lastTimerFor = 0L
    private var selfBusyUntil = 0L   // v11: our own timer launch makes Clock events; ignore them
    private var candidate = 0L       // v11: new next-alarm value waiting to prove it is stable
    private var registered = false

    private val checkRunnable = Runnable { check() }

    private val nextAlarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Small delay so the status-bar text (next_alarm_formatted) is updated first.
            handler.removeCallbacks(checkRunnable)
            handler.postDelayed(checkRunnable, 1200L)
        }
    }

    /**
     * Android's "next alarm" is system-wide: it can belong to ANY app (or a system feature).
     * Only DeskClock's own alarms may drive the auto timer, so an alarm owned by another app is
     * treated as "no alarm" (0). If the owner cannot be read (no showIntent), it is accepted.
     */
    private fun isDeskClockAlarm(info: AlarmManager.AlarmClockInfo): Boolean {
        if (!ONLY_DESKCLOCK) return true
        val owner = try {
            info.showIntent?.creatorPackage
        } catch (e: Exception) {
            null
        } ?: return true
        return owner == "com.android.deskclock" ||
            owner == "com.google.android.deskclock" ||
            owner.contains("deskclock", ignoreCase = true)
    }

    private fun nextAlarmTime(): Long {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val info = am.nextAlarmClock ?: return 0L
        if (!isDeskClockAlarm(info)) return 0L
        return fixXiaomi(info.triggerTime)
    }

    /**
     * Xiaomi Clock reports the "next alarm" 15 min EARLY while the alarm is more than 15 min away.
     * So whenever the reported time is more than 15 min ahead, add 15 min to get the real time.
     */
    private fun fixXiaomi(trig: Long): Long {
        val left = trig - System.currentTimeMillis()
        return if (left > 15 * 60_000L) trig + 15 * 60_000L else trig
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lastKnown = nextAlarmTime()
        if (!registered) {
            val f = IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(nextAlarmReceiver, f, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(nextAlarmReceiver, f)
                }
                registered = true
            } catch (e: Exception) {
                registered = false
            }
        }
        // Restores the daily profile schedule whenever the system (re)starts this service.
        try {
            Auto.rescheduleAll(this)
        } catch (e: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.deskclock" && pkg != "com.google.android.deskclock") return
        val t = System.currentTimeMillis()
        if (t < selfBusyUntil || t < Guard.busyUntil) return   // v11: caused by us, not the user
        lastDeskEvent = t
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, 1500L)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            try {
                unregisterReceiver(nextAlarmReceiver)
            } catch (e: Exception) {
            }
            registered = false
        }
        instance = null
        super.onDestroy()
    }

    private fun startTimer(secs: Int, forAlarm: Long, label: String): Boolean {
        val lbl = label
        var ok = Planner.fireTimer(this, secs, lbl, "com.android.deskclock")
        if (!ok) ok = Planner.fireTimer(this, secs, lbl, null)
        if (ok) {
            lastTimerFor = forAlarm
            selfBusyUntil = System.currentTimeMillis() + 30_000L
        }
        return ok
    }

    private fun check() {
        val now = System.currentTimeMillis()
        val next = nextAlarmTime()
        // v11: while this app (or our own timer) is busy, just follow the value silently.
        if (now < Guard.busyUntil || now < selfBusyUntil) {
            lastKnown = next
            candidate = 0L
            return
        }
        if (next == lastKnown) { candidate = 0L; return }
        // v11: Clock/Xiaomi flips the value for a moment (15 min early, stale text). Act only if it stays the same.
        if (next != candidate) {
            candidate = next
            handler.removeCallbacks(checkRunnable)
            handler.postDelayed(checkRunnable, 3000L)
            return
        }
        candidate = 0L
        val prev = lastKnown
        lastKnown = next
        if (next == 0L || next <= now) return

        // A) An alarm just rang (the old "next alarm" is in the past): chain a timer to the next one.
        if (CHAIN_TIMERS && prev != 0L && prev <= now + 3_000L && now - prev < 120_000L && next > prev) {
            val gap = next - prev
            if (gap <= CHAIN_MAX_GAP_MIN * 60_000L && abs(next - lastTimerFor) >= 1_000L) {
                val secs = ((next - now) / 1000L).toInt()
                if (secs in 1..86400) startTimer(secs, next, "Next alarm")
            }
            return
        }

        // B) An alarm was added by hand in the Clock app.
        if (now - lastDeskEvent > 20_000L) return              // not touched in the Clock app
        // v10: editing an alarm to a LATER time must also refresh the timer (was skipped before).
        if (abs(next - lastTimerFor) < 60_000L) return         // timer already set for it
        if (abs(next - Guard.timerFor) < 60_000L) return       // v11: the app's own timer already covers it
        val secs = ((next - now) / 1000L).toInt()
        if (secs < 1 || secs > 86400) return
        startTimer(secs, next, "Next alarm")
    }
}
