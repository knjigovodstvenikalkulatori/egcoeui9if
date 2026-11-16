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
    }

    private var lastRedirect = 0L

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
        for (slot in SOURCES.keys) {
            val target = p.getString("to_$slot", null) ?: continue
            val learned = p.getInt("key_$slot", -1)
            if (learned != -1 && code == learned) {
                if (event.action == KeyEvent.ACTION_UP) {
                    Log.i("Ekran", "Dugmad: tipka $code ($slot) -> $target")
                    open(target)
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && code > 100) Log.i("Ekran", "Dugmad: nepoznata tipka $code")
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val p = prefs(this)
        for ((slot, srcs) in SOURCES) {
            if (!srcs.contains(pkg)) continue
            val target = p.getString("to_$slot", null) ?: continue
            if (target == pkg) continue
            val now = SystemClock.uptimeMillis()
            if (lastEkranLaunchPkg == pkg && now - lastEkranLaunchAt < 10000) return   // korisnik je to sam otvorio iz Ekrana
            if (now - lastRedirect < 3000) return
            lastRedirect = now
            Log.i("Ekran", "Dugmad: otvoren $pkg -> preusmjeravam na $target")
            open(target)
            return
        }
    }

    private fun open(pkg: String) {
        val pm = packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(i) } catch (e: Exception) { Log.w("Ekran", "Dugmad: ne mogu otvoriti $pkg") }
    }
}
