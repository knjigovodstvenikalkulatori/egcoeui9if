package ba.ekran.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * TV uživo — ugrađeni player (Media3 ExoPlayer), bez vanjske aplikacije.
 * Gore/dolje (ili CH+/CH-) = promjena kanala, OK = info, Nazad = izlaz.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    data class Channel(val name: String, val url: String, val group: String)

    companion object {
        var channels: List<Channel> = emptyList()
    }

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var infoBox: LinearLayout
    private lateinit var infoNum: TextView
    private lateinit var infoName: TextView
    private lateinit var infoGroup: TextView
    private lateinit var errText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoBox.animate().alpha(0f).setDuration(400).start() }
    private val switchRunnable = Runnable { play() }
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (channels.isEmpty()) { finish(); return }
        index = intent.getIntExtra("index", 0).coerceIn(0, channels.size - 1)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        view = PlayerView(this)
        view.useController = false
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        view.keepScreenOn = true
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Info o kanalu (dolje lijevo)
        infoBox = LinearLayout(this)
        infoBox.orientation = LinearLayout.HORIZONTAL
        infoBox.gravity = Gravity.CENTER_VERTICAL
        infoBox.setPadding(dp(28), dp(18), dp(36), dp(18))
        infoBox.background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(Color.argb(200, 18, 20, 28))
        }
        infoNum = text(34f, true).apply { setTextColor(Color.argb(170, 255, 255, 255)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), 0, 0, 0) }
        infoName = text(30f, true)
        infoGroup = text(17f, false).apply { setTextColor(Color.argb(150, 255, 255, 255)) }
        col.addView(infoName); col.addView(infoGroup)
        infoBox.addView(infoNum); infoBox.addView(col)
        root.addView(infoBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(56), 0, 0, dp(48))
        })

        errText = text(24f, true).apply {
            visibility = View.GONE
            setPadding(dp(30), dp(18), dp(30), dp(18))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.argb(210, 18, 20, 28)) }
        }
        root.addView(errText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        setContentView(root)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 11) ExoPlayer Ekran")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        view.player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showError("Kanal trenutno nije dostupan")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) errText.visibility = View.GONE
            }
        })
        play()
    }

    private fun play() {
        val c = channels[index]
        errText.visibility = View.GONE
        val b = MediaItem.Builder().setUri(c.url)
        val low = c.url.lowercase()
        if (low.contains(".m3u8") || low.contains("m3u8") ) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(b.build())
        player.prepare()
        player.playWhenReady = true
        showInfo()
    }

    private fun change(delta: Int) {
        index = (index + delta + channels.size) % channels.size
        showInfo()
        // mala pauza da brzo listanje ne pokreće svaki kanal
        handler.removeCallbacks(switchRunnable)
        handler.postDelayed(switchRunnable, 450)
    }

    private fun showInfo() {
        val c = channels[index]
        infoNum.text = (index + 1).toString()
        infoName.text = c.name
        infoGroup.text = if (c.group.isNotBlank()) c.group else "TV uživo"
        infoBox.animate().cancel()
        infoBox.alpha = 1f
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 4000)
    }

    private fun showError(msg: String) {
        errText.text = msg
        errText.visibility = View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> { change(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> { change(1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { showInfo(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player.playWhenReady = !player.playWhenReady; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(sp: Float, bold: Boolean) = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.DEFAULT
    }
}
