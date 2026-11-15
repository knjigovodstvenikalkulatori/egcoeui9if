package ba.ekran.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Ikone i TV baneri instaliranih aplikacija, servirani kao /icon/<paket>. */
object Icons {
    private val cache = ConcurrentHashMap<String, ByteArray>()

    private fun banner(ctx: Context, pkg: String): Drawable? {
        val pm = ctx.packageManager
        return try {
            pm.getApplicationBanner(pkg) ?: run {
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg)
                val ri = pm.queryIntentActivities(i, 0).firstOrNull()
                ri?.activityInfo?.loadBanner(pm)
            }
        } catch (e: Exception) { null }
    }

    fun hasBanner(ctx: Context, pkg: String): Boolean = banner(ctx, pkg) != null

    fun response(ctx: Context, pkg: String): WebResourceResponse {
        val bytes = cache[pkg] ?: render(ctx, pkg)?.also { cache[pkg] = it }
        return if (bytes != null)
            WebResourceResponse("image/png", null, 200, "OK", mapOf("Cache-Control" to "max-age=86400"), ByteArrayInputStream(bytes))
        else
            WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    private fun render(ctx: Context, pkg: String): ByteArray? {
        val b = banner(ctx, pkg)
        val d: Drawable
        val w: Int
        val h: Int
        if (b != null) {
            d = b; w = 480; h = 270
        } else {
            d = try { ctx.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { return null }
            w = 192; h = 192
        }
        val bmp = if (d is BitmapDrawable && d.bitmap != null && b == null) {
            Bitmap.createScaledBitmap(d.bitmap, w, h, true)
        } else {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            out
        }
        val os = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
        return os.toByteArray()
    }
}
