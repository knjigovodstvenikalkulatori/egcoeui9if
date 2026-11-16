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
                KeyService.lastEkranLaunchPkg = pkg
                KeyService.lastEkranLaunchAt = android.os.SystemClock.uptimeMillis()
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

    // ───────── dugmad na daljinskom ─────────
    @JavascriptInterface
    fun keyMap(slot: String, pkg: String) {
        val e = KeyService.prefs(act).edit()
        if (pkg.isBlank()) e.remove("to_$slot") else e.putString("to_$slot", pkg)
        e.apply()
    }

    @JavascriptInterface
    fun keyStatus(): String {
        val p = KeyService.prefs(act)
        val o = JSONObject().put("enabled", KeyService.instance != null)
        for (slot in KeyService.SOURCES.keys) {
            o.put(slot, JSONObject().put("to", p.getString("to_$slot", null) ?: JSONObject.NULL).put("key", p.getInt("key_$slot", -1)))
        }
        o.put("events", JSONArray(KeyService.lastEvents()))
        o.put("learning", KeyService.learnSlot ?: JSONObject.NULL)
        return o.toString()
    }

    @JavascriptInterface
    fun keyLearn(slot: String) {
        KeyService.learnSlot = slot
        KeyService.learnUntil = android.os.SystemClock.uptimeMillis() + 15000
    }

    @JavascriptInterface
    fun keyForget(slot: String) { KeyService.prefs(act).edit().remove("key_$slot").apply() }

    private fun toast(msg: String) {
        act.runOnUiThread { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show() }
    }
}
