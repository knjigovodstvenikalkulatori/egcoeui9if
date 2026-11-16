package ba.ekran.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Preusmjeravanje dugmadi na daljinskom (Netflix, Prime Video).
 *
 * Dva načina, da radi na svakom daljinskom:
 *  1) ako sistem pusti tipku do nas — prepoznamo je i odmah otvorimo izabranu aplikaciju;
 *  2) ako sistem sam otvori Netflix/Prime (tipka nikad ne stigne do nas) — čim se ta aplikacija
 *     pojavi, a nije otvorena iz Ekrana, odmah otvorimo izabranu aplikaciju umjesto nje.
 */
class KeyService : AccessibilityService() {

    companion object {
        @Volatile var instance: KeyService? = null
        @Volatile var lastEkranLaunchPkg: String? = null
        @Volatile var lastEkranLaunchAt = 0L

        /** slot -> paketi koje to dugme inače otvara */
        val SOURCES = mapOf(
            "netflix" to setOf("com.netflix.ninja", "com.netflix.mediaclient"),
            "prime" to setOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient", "com.amazon.avod")
        )

        fun prefs(c: Context) = c.getSharedPreferences("keys", Context.MODE_PRIVATE)

        /** Učenje dugmeta: sljedeća "nepoznata" tipka se pamti za ovaj slot. */
        @Volatile var learnSlot: String? = null
        @Volatile var learnUntil = 0L
        /** Zadnjih nekoliko događaja, za prikaz u postavkama. */
        val events = java.util.ArrayDeque<String>()
        fun note(m: String) {
            Log.i("Ekran", "Dugmad: $m")
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            synchronized(events) { events.addFirst("$t $m"); while (events.size > 6) events.removeLast() }
        }
        fun lastEvents(): List<String> = synchronized(events) { events.toList() }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastRedirect = 0L
    private var redirectUntil = 0L
    private var redirectTarget: String? = null

    override fun onServiceConnected() {
        instance = this
        Log.i("Ekran", "Dugmad: servis aktivan")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT ||
            code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_BACK) return false
        val p = prefs(this)
        val ls = learnSlot
        if (ls != null) {
            if (SystemClock.uptimeMillis() > learnUntil) { learnSlot = null }
            else {
                if (event.action == KeyEvent.ACTION_UP) {
                    p.edit().putInt("key_$ls", code).apply()
                    learnSlot = null
                    note("naučeno: $ls = tipka $code")
                    main.post { android.widget.Toast.makeText(this, "Dugme naučeno ✓", android.widget.Toast.LENGTH_SHORT).show() }
                }
                return true
            }
        }
        for (slot in SOURCES.keys) {
            val target = p.getString("to_$slot", null) ?: continue
            val learned = p.getInt("key_$slot", -1)
            if (learned != -1 && code == learned) {
                if (event.action == KeyEvent.ACTION_UP) {
                    note("tipka $code ($slot) → $target")
                    open(target)
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && code != KeyEvent.KEYCODE_HOME && code != KeyEvent.KEYCODE_MENU &&
            code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) note("tipka $code (${KeyEvent.keyCodeToString(code)})")
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        val p = prefs(this)
        for ((slot, srcs) in SOURCES) {
            if (!srcs.contains(pkg)) continue
            val target = p.getString("to_$slot", null) ?: continue
            if (target == pkg) continue
            if (lastEkranLaunchPkg == pkg && now - lastEkranLaunchAt < 10000) return   // korisnik je to sam otvorio iz Ekrana
            // Netflix/Prime se često otvaraju u dva koraka (uvodni pa glavni ekran) —
            // zato nekoliko sekundi vraćamo izabranu aplikaciju svaki put kad se oni pojave.
            if (now - lastRedirect < 400) return
            if (now > redirectUntil) note("otvoren $pkg → $target")
            lastRedirect = now
            redirectUntil = now + 8000
            redirectTarget = target
            open(target)
            main.postDelayed({ kill(pkg) }, 1500)
            main.postDelayed({ kill(pkg) }, 4500)
            return
        }
    }

    private fun kill(pkg: String) {
        try { (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg) } catch (e: Exception) { }
    }

    private fun open(pkg: String) {
        val pm = packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (i == null) { note("aplikacija $pkg nema ekran za pokretanje"); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(i) } catch (e: Exception) { Log.w("Ekran", "Dugmad: ne mogu otvoriti $pkg") }
    }
}
