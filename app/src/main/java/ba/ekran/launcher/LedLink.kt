package ba.ekran.launcher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import java.util.UUID
import kotlin.random.Random

/**
 * Bluetooth (BLE) veza s jednom LED trakom.
 *  UAC  = kocka oko TV-a (uLamp protokol: 0A A5 <TID> <CMD> ..., na FFB1)
 *  MELK = C traka na zidu (7E 00 <6 bajtova> EF, na FFF3)
 */
@SuppressLint("MissingPermission")
class LedLink(private val ctx: Context, val kind: Kind, private val handler: Handler) {

    enum class Kind { UAC, MELK }

    @Volatile var status: String = "nije spojena"
        private set
    @Volatile var ready = false
        private set
    var started = false
        private set

    private var gatt: BluetoothGatt? = null
    private var ch: BluetoothGattCharacteristic? = null
    @Volatile private var connecting = false
    @Volatile private var busy = false
    private var busySince = 0L
    private var lastTry = 0L
    private var mtu = 23
    private var tid = Random.nextInt(256)
    private var scanning = false
    private var scanCb: ScanCallback? = null

    private val prefs = ctx.getSharedPreferences("ambi", Context.MODE_PRIVATE)
    private val macKey = if (kind == Kind.UAC) "uac_mac" else "melk_mac"
    private val charUuid: UUID = UUID.fromString(if (kind == Kind.UAC) "0000ffb1-0000-1000-8000-00805f9b34fb" else "0000fff3-0000-1000-8000-00805f9b34fb")
    private val svcUuid: UUID = UUID.fromString(if (kind == Kind.UAC) "0000ffb0-0000-1000-8000-00805f9b34fb" else "0000fff0-0000-1000-8000-00805f9b34fb")

    /** Koliko boja (segmenata) stane u jedan paket. */
    val maxSegments: Int get() = ((mtu - 3 - 8) / 3).coerceIn(1, 60)

    private val adapter: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun start() { started = true; connect() }

    fun connect() {
        if (connecting || ready) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTry < 8000) return
        lastTry = now
        val a = adapter
        if (a == null) { status = "nema Bluetootha"; return }
        if (!a.isEnabled) { status = "Bluetooth je ugašen"; return }
        val mac = prefs.getString(macKey, null)
        try {
            if (mac != null) open(a.getRemoteDevice(mac)) else scan(a)
        } catch (e: Exception) {
            connecting = false
            status = "greška: ${e.javaClass.simpleName}"
        }
    }

    private fun matches(r: ScanResult): Boolean {
        val name = (r.device.name ?: r.scanRecord?.deviceName ?: "").lowercase()
        val uuids = r.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
        return if (kind == Kind.UAC) name.startsWith("uac") || uuids.contains(svcUuid)
        else name.contains("melk") || name.startsWith("elk") || name.contains("bledom")
    }

    private fun scan(a: BluetoothAdapter) {
        val sc = a.bluetoothLeScanner ?: run { status = "skeniranje nije dostupno"; return }
        connecting = true
        scanning = true
        status = "tražim traku…"
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!scanning || !matches(result)) return
                scanning = false
                try { sc.stopScan(this) } catch (e: Exception) { }
                prefs.edit().putString(macKey, result.device.address).apply()
                handler.post { open(result.device) }
            }
            override fun onScanFailed(errorCode: Int) {
                scanning = false; connecting = false
                status = "skeniranje nije uspjelo ($errorCode)"
            }
        }
        scanCb = cb
        sc.startScan(cb)
        handler.postDelayed({
            if (scanning) {
                scanning = false
                try { sc.stopScan(cb) } catch (e: Exception) { }
                connecting = false
                status = "traka nije pronađena (ugasi aplikaciju na telefonu)"
            }
        }, 15000)
    }

    private fun open(dev: BluetoothDevice) {
        connecting = true
        status = "spajam se…"
        gatt = if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
               else dev.connectGatt(ctx, false, cb)
        handler.postDelayed({
            if (connecting && !ready) {          // veza visi — pokušaj ponovo kasnije
                try { gatt?.close() } catch (e: Exception) { }
                gatt = null; connecting = false
                status = "nije dostupna"
            }
        }, 20000)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.post { if (!g.requestMtu(247)) g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false; connecting = false; busy = false
                try { g.close() } catch (e: Exception) { }
                if (gatt === g) gatt = null
                status = "odspojena"
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, st: Int) {
            mtu = if (st == BluetoothGatt.GATT_SUCCESS) m else 23
            handler.post { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            var c = g.getService(svcUuid)?.getCharacteristic(charUuid)
            if (c == null) for (s in g.services) { c = s.getCharacteristic(charUuid); if (c != null) break }
            if (c == null) {
                status = "pogrešan uređaj"
                prefs.edit().remove(macKey).apply()
                handler.post { try { g.disconnect() } catch (e: Exception) { } }
                return
            }
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch = c
            ready = true; connecting = false
            status = "spojena"
            handler.post { power(true) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            busy = false
        }
    }

    @Suppress("DEPRECATION")
    private fun write(bytes: ByteArray, force: Boolean = false): Boolean {
        val g = gatt; val c = ch
        if (!ready || g == null || c == null) { connect(); return false }
        val now = SystemClock.uptimeMillis()
        if (busy && !force && now - busySince < 250) return false
        c.value = bytes
        busy = true; busySince = now
        val ok = try { g.writeCharacteristic(c) } catch (e: Exception) { false }
        if (!ok) busy = false
        return ok
    }

    private fun uac(cmd: Int, vararg args: Int): ByteArray {
        tid = (tid + 1) and 0xFF
        val out = ByteArray(4 + args.size)
        out[0] = 0x0A; out[1] = 0xA5.toByte(); out[2] = tid.toByte(); out[3] = cmd.toByte()
        for (i in args.indices) out[4 + i] = args[i].toByte()
        return out
    }

    private fun melk(vararg payload: Int): ByteArray {
        val out = ByteArray(9)
        out[0] = 0x7E; out[1] = 0x00; out[8] = 0xEF.toByte()
        for (i in 0 until 6) out[2 + i] = (if (i < payload.size) payload[i] else 0).toByte()
        return out
    }

    fun power(on: Boolean) {
        val b = if (kind == Kind.UAC) uac(0x01, if (on) 1 else 0)
                else if (on) melk(0x04, 0xF0, 0x00, 0x01, 0xFF) else melk(0x04, 0x00, 0x00, 0x00, 0xFF)
        write(b, force = true)
    }

    /** colors: lista RGB boja (0xRRGGBB). Kocka dobija sve, C traka samo prvu. */
    fun send(colors: IntArray): Boolean {
        if (colors.isEmpty()) return false
        if (kind == Kind.MELK) {
            val c = colors[0]
            return write(melk(0x05, 0x03, (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF))
        }
        val same = colors.all { it == colors[0] }
        val list = if (same) intArrayOf(colors[0]) else colors
        val args = IntArray(4 + list.size * 3)
        args[0] = 1; args[1] = 1; args[2] = list.size; args[3] = 100
        for (i in list.indices) {
            args[4 + i * 3] = (list[i] shr 16) and 0xFF
            args[5 + i * 3] = (list[i] shr 8) and 0xFF
            args[6 + i * 3] = list[i] and 0xFF
        }
        return write(uac(0x04, *args))
    }

    fun close() {
        started = false
        try { scanCb?.let { adapter?.bluetoothLeScanner?.stopScan(it) } } catch (e: Exception) { }
        try { gatt?.disconnect() } catch (e: Exception) { }
        try { gatt?.close() } catch (e: Exception) { }
        gatt = null; ready = false; connecting = false
        status = "nije spojena"
    }
}
