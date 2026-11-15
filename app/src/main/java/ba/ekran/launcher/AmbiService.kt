package ba.ekran.launcher

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import kotlin.math.abs

/**
 * Ambilight: snima ekran u maloj rezoluciji (64×36), računa boje po ivicama
 * i šalje ih traci oko TV-a (i C traci na zidu) preko Bluetootha.
 */
class AmbiService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile var running = false
        @Volatile var testUntil = 0L
        @Volatile var uacStatus = "nije spojena"
        @Volatile var melkStatus = "isključena"
        @Volatile var info = ""

        // test boje: 1 crvena, 2 zelena, 3 plava, 4 žuta, 5 ljubičasta, 6 tirkizna, 7 bijela, 8 narandžasta …
        val TEST = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF, 0xFFFFFF, 0xFF8000, 0x8000FF, 0x00FF80)
    }

    private val W = 64
    private val H = 36
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var uac: LedLink? = null
    private var melk: LedLink? = null
    private var lastSend = 0L
    private var lastUac: IntArray? = null
    private var lastUacAt = 0L
    private var lastMelk = -1
    private var lastMelkAt = 0L
    private var smooth: FloatArray? = null
    private var smoothMelk: FloatArray? = null
    @Volatile private var screenOn = true
    private val px = IntArray(W * H)

    private val prefs by lazy { getSharedPreferences("ambi", Context.MODE_PRIVATE) }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; handler.post { uac?.power(false); melk?.power(false) } }
                Intent.ACTION_SCREEN_ON -> { screenOn = true; handler.post { uac?.power(true); melk?.power(true); lastUac = null; lastMelk = -1 } }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            info = "snimanje ekrana nije dozvoljeno"
            stopSelf(); return START_NOT_STICKY
        }
        val p = projection ?: run { stopSelf(); return START_NOT_STICKY }

        thread = HandlerThread("ambi").also { it.start() }
        handler = Handler(thread.looper)
        running = true
        info = ""

        uac = LedLink(this, LedLink.Kind.UAC, handler).also { it.start() }
        melk = LedLink(this, LedLink.Kind.MELK, handler)
        if (prefs.getBoolean("melk", true)) melk?.start()

        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { stopSelf() } }
        }, handler)

        val r = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        reader = r
        vdisplay = p.createVirtualDisplay("ekran-ambilight", W, H, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)

        val f = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        registerReceiver(screenRx, f)

        // održavanje veze (ponovno spajanje ako traka "ispadne")
        handler.postDelayed(object : Runnable {
            override fun run() {
                uac?.let { if (!it.ready) it.connect(); uacStatus = it.status }
                melk?.let {
                    if (prefs.getBoolean("melk", true)) { if (!it.started) it.start() else if (!it.ready) it.connect(); melkStatus = it.status }
                    else melkStatus = "isključena"
                }
                if (running) handler.postDelayed(this, 2000)
            }
        }, 2000)
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("ambi", "Ambilight", NotificationManager.IMPORTANCE_LOW))
            builder = Notification.Builder(this, "ambi")
        } else {
            @Suppress("DEPRECATION")
            builder = Notification.Builder(this)
        }
        val n: Notification = builder.setContentTitle("Ambilight radi").setSmallIcon(R.drawable.ic_launcher).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(42, n)
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            val fps = prefs.getInt("fps", 15).coerceIn(5, 30)
            if (!screenOn || now - lastSend < 1000 / fps) return
            lastSend = now
            val plane = img.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val w = minOf(img.width, W)
            val h = minOf(img.height, H)
            for (y in 0 until h) {
                val row = y * rs
                for (x in 0 until w) {
                    val o = row + x * ps
                    px[y * W + x] = ((buf.get(o).toInt() and 0xFF) shl 16) or
                                    ((buf.get(o + 1).toInt() and 0xFF) shl 8) or
                                    (buf.get(o + 2).toInt() and 0xFF)
                }
            }
            process(now)
        } catch (e: Exception) {
            info = "greška: ${e.javaClass.simpleName}"
        } finally {
            img.close()
        }
    }

    private fun process(now: Long) {
        val u = uac ?: return
        val bright = prefs.getInt("bright", 100).coerceIn(5, 100) / 100f
        val smoothK = prefs.getInt("smooth", 50).coerceIn(0, 90) / 100f
        var n = prefs.getInt("n", 12).coerceIn(1, 60)
        n = minOf(n, u.maxSegments)

        val cols: IntArray
        if (now < testUntil) {
            cols = IntArray(n) { TEST[it % TEST.size] }
        } else {
            val start = prefs.getFloat("start", 0f)
            val ccw = prefs.getBoolean("ccw", true)
            val raw = segmentColors(n, start, ccw)
            var sm = smooth
            if (sm == null || sm.size != n * 3) { sm = FloatArray(n * 3); smooth = sm }
            cols = IntArray(n)
            for (i in 0 until n) {
                val c = enhance(raw[i], bright)
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                sm[i * 3] = sm[i * 3] * smoothK + r * (1 - smoothK)
                sm[i * 3 + 1] = sm[i * 3 + 1] * smoothK + g * (1 - smoothK)
                sm[i * 3 + 2] = sm[i * 3 + 2] * smoothK + b * (1 - smoothK)
                cols[i] = (sm[i * 3].toInt() shl 16) or (sm[i * 3 + 1].toInt() shl 8) or sm[i * 3 + 2].toInt()
            }
        }
        val prev = lastUac
        if (prev == null || changed(prev, cols) || now - lastUacAt > 2500) {
            if (u.send(cols)) { lastUac = cols; lastUacAt = now }
        }

        val m = melk
        if (m != null && m.ready && prefs.getBoolean("melk", true)) {
            val c = if (now < testUntil) 0xFFFFFF else {
                val raw = enhance(leftAverage(), bright)
                var s = smoothMelk
                if (s == null) { s = FloatArray(3); smoothMelk = s }
                s[0] = s[0] * smoothK + ((raw shr 16) and 0xFF) * (1 - smoothK)
                s[1] = s[1] * smoothK + ((raw shr 8) and 0xFF) * (1 - smoothK)
                s[2] = s[2] * smoothK + (raw and 0xFF) * (1 - smoothK)
                (s[0].toInt() shl 16) or (s[1].toInt() shl 8) or s[2].toInt()
            }
            if (c != lastMelk && (lastMelk < 0 || dist(c, lastMelk) > 6) || now - lastMelkAt > 2500) {
                if (m.send(intArrayOf(c))) { lastMelk = c; lastMelkAt = now }
            }
        }
    }

    private fun dist(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))

    private fun changed(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return true
        for (i in a.indices) if (dist(a[i], b[i]) > 6) return true
        return false
    }

    /** Tačka na obodu (u, v od 0 do 1), d od gornjeg lijevog ugla u smjeru kazaljke. */
    private fun perim(d0: Float, P: Float): FloatArray {
        var d = ((d0 % P) + P) % P
        val w = W.toFloat(); val h = H.toFloat()
        if (d < w) return floatArrayOf(d / w, 0f)
        d -= w
        if (d < h) return floatArrayOf(1f, d / h)
        d -= h
        if (d < w) return floatArrayOf(1f - d / w, 1f)
        d -= w
        return floatArrayOf(0f, 1f - d / h)
    }

    private fun sample(u: Float, v: Float, depth: Float): Int {
        val uu = 0.5f + (u - 0.5f) * depth
        val vv = 0.5f + (v - 0.5f) * depth
        val x = (uu * W).toInt().coerceIn(0, W - 1)
        val y = (vv * H).toInt().coerceIn(0, H - 1)
        return px[y * W + x]
    }

    private fun segmentColors(n: Int, start: Float, ccw: Boolean): IntArray {
        val P = 2f * (W + H)
        val L = P / n
        val d0 = start * P
        val sign = if (ccw) -1f else 1f
        val out = IntArray(n)
        val samples = 8
        for (k in 0 until n) {
            var r = 0; var g = 0; var b = 0; var cnt = 0
            for (j in 0 until samples) {
                val pt = perim(d0 + sign * (k * L + L * (j + 0.5f) / samples), P)
                for (depth in floatArrayOf(0.96f, 0.86f)) {
                    val c = sample(pt[0], pt[1], depth)
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; cnt++
                }
            }
            out[k] = ((r / cnt) shl 16) or ((g / cnt) shl 8) or (b / cnt)
        }
        return out
    }

    private fun leftAverage(): Int {
        var r = 0; var g = 0; var b = 0; var cnt = 0
        for (y in 0 until H) for (x in 0 until W / 4) {
            val c = px[y * W + x]
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; cnt++
        }
        return ((r / cnt) shl 16) or ((g / cnt) shl 8) or (b / cnt)
    }

    /** Jače boje (kao kod pravog Ambilighta), tamno ostaje tamno. */
    private fun enhance(c: Int, bright: Float): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, hsv)
        if (hsv[2] < 0.05f) return 0
        hsv[1] = minOf(1f, hsv[1] * 1.35f)
        hsv[2] = minOf(1f, hsv[2] * 1.1f) * bright
        return Color.HSVToColor(hsv) and 0xFFFFFF
    }

    override fun onDestroy() {
        running = false
        try { unregisterReceiver(screenRx) } catch (e: Exception) { }
        try { vdisplay?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        vdisplay = null; reader = null; projection = null
        if (::handler.isInitialized) {
            val u = uac; val m = melk
            handler.post { u?.power(false); m?.power(false) }
            handler.postDelayed({ u?.close(); m?.close(); thread.quitSafely() }, 600)
        }
        uacStatus = "nije spojena"; melkStatus = "isključena"
        super.onDestroy()
    }
}

/** Nevidljivi ekran koji traži dozvolu za snimanje ekrana i pokreće AmbiService. */
class AmbiActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 7)
        } catch (e: Exception) {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, AmbiService::class.java)
                .putExtra(AmbiService.EXTRA_CODE, resultCode)
                .putExtra(AmbiService.EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } else {
            AmbiService.info = "snimanje ekrana nije dozvoljeno"
            Toast.makeText(this, "Ambilight: snimanje ekrana nije dozvoljeno", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
