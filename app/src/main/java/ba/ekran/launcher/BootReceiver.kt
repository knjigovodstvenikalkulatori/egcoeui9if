package ba.ekran.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Kad se TV upali (ili se launcher ažurira), otvori Ekran.
 * Ekran je i zadani "Home", ovo je dodatna sigurnost ako sistem prvo pokaže nešto drugo.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { context.startActivity(i) } catch (e: Exception) { }
    }
}
