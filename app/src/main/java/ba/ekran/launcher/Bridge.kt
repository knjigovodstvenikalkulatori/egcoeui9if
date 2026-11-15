package ba.ekran.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Funkcije koje HTML (window.LauncherBridge) može pozvati. */
class Bridge(private val act: MainActivity) {

    @JavascriptInterface
    fun getApps(): String {
        val pm = act.packageManager
        val map = LinkedHashMap<String, JSONObject>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            val list = try { pm.queryIntentActivities(q, 0) } catch (e: Exception) { emptyList() }
            for (ri in list) {
                val pkg = ri.activityInfo.packageName
                if (pkg == act.packageName || map.containsKey(pkg)) continue
                val o = JSONObject()
                o.put("pkg", pkg)
                o.put("label", ri.loadLabel(pm).toString())
                o.put("banner", Icons.hasBanner(act, pkg))
                map[pkg] = o
            }
        }
        val sorted = map.values.sortedBy { it.optString("label").lowercase() }
        return JSONArray(sorted).toString()
    }

    @JavascriptInterface
    fun isInstalled(pkg: String): Boolean = try {
        act.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    @JavascriptInterface
    fun launch(pkg: String) {
        act.runOnUiThread {
            val pm = act.packageManager
            val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (i == null) {
                toast("Aplikacija se ne može otvoriti")
            } else {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { act.startActivity(i) } catch (e: Exception) { toast("Aplikacija se ne može otvoriti") }
            }
        }
    }

    /** Otvara link (npr. stremio:///detail/movie/tt…). Vraća false ako ga nijedna aplikacija ne zna otvoriti. */
    @JavascriptInterface
    fun openUri(uri: String): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(act.packageManager) == null) return false
        act.runOnUiThread {
            try { act.startActivity(i) } catch (e: ActivityNotFoundException) { toast("Ne može se otvoriti") }
        }
        return true
    }

    @JavascriptInterface
    fun openYouTubeApp(id: String) {
        act.runOnUiThread {
            val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                act.startActivity(app)
            } catch (e: ActivityNotFoundException) {
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e2: Exception) { toast("YouTube aplikacija nije instalirana") }
            }
        }
    }

    @JavascriptInterface
    fun openSystemSettings() {
        act.runOnUiThread {
            try { act.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast("Postavke nisu dostupne") }
        }
    }

    /** Konfiguracija poslana s Maca (install.sh). */
    @JavascriptInterface
    fun getConfig(): String =
        act.getSharedPreferences("ekran", android.content.Context.MODE_PRIVATE).getString("config", "{}") ?: "{}"

    /** TV uživo: lista kanala (JSON [{n,u,l,g}]) i indeks kanala koji se pušta. */
    @JavascriptInterface
    fun playLive(json: String, index: Int) {
        val arr = try { JSONArray(json) } catch (e: Exception) { return }
        val list = ArrayList<PlayerActivity.Channel>(arr.length())
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val url = o.optString("u")
            if (url.isBlank()) continue
            list.add(PlayerActivity.Channel(o.optString("n", "Kanal"), url, o.optString("g", "")))
        }
        if (list.isEmpty()) return
        PlayerActivity.channels = list
        act.runOnUiThread {
            val i = Intent(act, PlayerActivity::class.java).putExtra("index", index.coerceIn(0, list.size - 1))
            act.startActivity(i)
        }
    }

    // ───────── Ambilight ─────────
    private var lastAmbiStart = 0L

    @JavascriptInterface
    fun ambiStart() {
        if (AmbiService.running) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAmbiStart < 10000) return
        lastAmbiStart = now
        act.runOnUiThread {
            try { act.startActivity(Intent(act, AmbiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast("Ambilight se ne može pokrenuti") }
        }
    }

    @JavascriptInterface
    fun ambiStop() {
        act.runOnUiThread {
            act.stopService(Intent(act, AmbiService::class.java))
            // ako je izabrana ručna boja, traka nastavlja s njom
            if (act.getSharedPreferences("ambi", android.content.Context.MODE_PRIVATE).getBoolean("manual_on", false))
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startLedService() }, 1200)
        }
    }

    private fun startLedService() {
        val i = Intent(act, AmbiService::class.java)
        try { if (android.os.Build.VERSION.SDK_INT >= 26) act.startForegroundService(i) else act.startService(i) } catch (e: Exception) { }
    }

    /** Ručna boja kad Ambilight ne radi: {on, color (0xRRGGBB), effect: none|breathe|rainbow} */
    @JavascriptInterface
    fun ledColor(json: String) {
        try {
            val o = JSONObject(json)
            val on = o.optBoolean("on", false)
            act.getSharedPreferences("ambi", android.content.Context.MODE_PRIVATE).edit()
                .putBoolean("manual_on", on)
                .putInt("color", o.optInt("color", 0xFFB46B))
                .putString("effect", o.optString("effect", "none"))
                .apply()
            if (AmbiService.hasProjection) return          // Ambilight ima prednost
            act.runOnUiThread { if (on) startLedService() else act.stopService(Intent(act, AmbiService::class.java)) }
        } catch (e: Exception) { }
    }

    /** {n, start, ccw, bright, fps, smooth, melk} */
    @JavascriptInterface
    fun ambiConfig(json: String) {
        try {
            val o = JSONObject(json)
            act.getSharedPreferences("ambi", android.content.Context.MODE_PRIVATE).edit()
                .putInt("n", o.optInt("n", 12))
                .putFloat("start", o.optDouble("start", 0.0).toFloat())
                .putBoolean("ccw", o.optBoolean("ccw", true))
                .putInt("bright", o.optInt("bright", 100))
                .putInt("fps", o.optInt("fps", 15))
                .putInt("smooth", o.optInt("smooth", 50))
                .putBoolean("melk", o.optBoolean("melk", true))
                .apply()
        } catch (e: Exception) { }
    }

    @JavascriptInterface
    fun ambiTest() { AmbiService.testUntil = android.os.SystemClock.uptimeMillis() + 10000 }

    @JavascriptInterface
    fun ambiForget() {
        act.getSharedPreferences("ambi", android.content.Context.MODE_PRIVATE).edit().remove("uac_mac").remove("melk_mac").apply()
        ambiStop()
    }

    @JavascriptInterface
    fun ambiStatus(): String = JSONObject()
        .put("running", AmbiService.running)
        .put("uac", AmbiService.uacStatus)
        .put("melk", AmbiService.melkStatus)
        .put("info", AmbiService.info)
        .put("capture", AmbiService.hasProjection)
        .put("frames", AmbiService.frames)
        .put("light", AmbiService.lastBrightness)
        .toString()

    private fun toast(msg: String) {
        act.runOnUiThread { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show() }
    }
}
