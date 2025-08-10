package io.ergolyam.clipbridge.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.ergolyam.clipbridge.R
import io.ergolyam.clipbridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

class ClipClientService : Service() {

    companion object {
        const val ACTION_START = "io.ergolyam.clipbridge.START"
        const val ACTION_STOP = "io.ergolyam.clipbridge.STOP"
        const val ACTION_SEND_TEXT = "io.ergolyam.clipbridge.SEND_TEXT"
        const val ACTION_STATUS = "io.ergolyam.clipbridge.STATUS"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_STATUS_TEXT = "status"
        const val EXTRA_AUTOCONNECT = "auto"

        private const val CHANNEL_ID = "clipbridge_channel"
        private const val NOTIF_ID = 1001

        private const val MSG_TEXT: Byte = 0x01
        private const val MAX_BYTES = 1_048_576
    }

    private var scope: CoroutineScope? = null
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var host: String = ""
    private var port: Int = 0
    private var readerJob: Job? = null
    private var reachJob: Job? = null

    @Volatile private var stopping: Boolean = false
    @Volatile private var autoConnectEnabled: Boolean = false
    @Volatile private var startedOnce: Boolean = false
    @Volatile private var isConnectedNow: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val newHost = intent.getStringExtra(EXTRA_HOST) ?: ""
                val newPort = intent.getIntExtra(EXTRA_PORT, 28900)
                val newAuto = intent.getBooleanExtra(EXTRA_AUTOCONNECT, false)

                if (scope == null) scope = CoroutineScope(Dispatchers.IO)

                if (startedOnce && host == newHost && port == newPort && autoConnectEnabled == newAuto) {
                    if (isConnectedNow) {
                        startForeground(NOTIF_ID, notifConnected("$host:$port"))
                        updateStatus(true, "Connected to $host:$port")
                    } else if (autoConnectEnabled) {
                        startForeground(NOTIF_ID, notifWaiting("$host:$port"))
                        updateStatus(false, "Waiting for $host:$port…")
                        startReachabilityWatch()
                    } else {
                        startForeground(NOTIF_ID, notifDisconnected())
                        updateStatus(false, "Disconnected")
                    }
                    return START_NOT_STICKY
                }

                stopping = false
                host = newHost
                port = newPort
                autoConnectEnabled = newAuto
                startedOnce = true

                readerJob?.cancel()
                reachJob?.cancel()
                closeSocket()
                isConnectedNow = false

                if (autoConnectEnabled) {
                    startForeground(NOTIF_ID, notifWaiting("$host:$port"))
                    updateStatus(false, "Waiting for $host:$port…")
                    startReachabilityWatch()
                } else {
                    startForeground(NOTIF_ID, notifDisconnected())
                    connect()
                }
            }
            ACTION_SEND_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                scope?.launch {
                    val ok = sendFramed(text)
                    showToast(if (ok) getString(R.string.toast_sent) else getString(R.string.toast_send_failed))
                }
            }
            ACTION_STOP -> {
                stopping = true
                updateStatus(false, "Disconnected")
                startForeground(NOTIF_ID, notifDisconnected())
                stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    private fun startReachabilityWatch() {
        if (!autoConnectEnabled) return
        val s = scope ?: return
        reachJob?.cancel()
        reachJob = s.launch {
            while (!stopping && autoConnectEnabled && this.isActive) {
                if (isEndpointReachable(host, port, 1200)) {
                    connect()
                    break
                }
                delay(3_000)
            }
        }
    }

    private fun isEndpointReachable(h: String, p: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { sock -> sock.connect(InetSocketAddress(h, p), timeoutMs) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun connect() {
        val s = scope ?: return
        reachJob?.cancel()
        readerJob?.cancel()
        readerJob = s.launch {
            updateStatus(false, "Connecting to $host:$port")
            startForeground(NOTIF_ID, notifConnecting("$host:$port"))
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 5_000)
                socket = sock
                input = DataInputStream(sock.getInputStream())
                output = DataOutputStream(sock.getOutputStream())
                isConnectedNow = true
                updateStatus(true, "Connected to $host:$port")
                startForeground(NOTIF_ID, notifConnected("$host:$port"))
                readLoop()
            } catch (e: Exception) {
                isConnectedNow = false
                if (stopping) {
                    updateStatus(false, "Disconnected")
                    startForeground(NOTIF_ID, notifDisconnected())
                    return@launch
                }
                if (autoConnectEnabled) {
                    updateStatus(false, "Waiting for $host:$port…")
                    startForeground(NOTIF_ID, notifWaiting("$host:$port"))
                    startReachabilityWatch()
                } else {
                    updateStatus(false, "Disconnected")
                    startForeground(NOTIF_ID, notifDisconnected())
                }
            }
        }
    }

    private suspend fun readLoop() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val `in` = input ?: return
        try {
            while (coroutineContext.isActive) {
                val type = `in`.readByte()
                if (type != MSG_TEXT) throw IOException("Bad frame")
                val len = `in`.readIntBE()
                if (len < 0 || len > MAX_BYTES) throw IOException("Frame size")
                val buf = ByteArray(len)
                `in`.readFully(buf)
                val text = buf.toString(Charsets.UTF_8)
                val clip = ClipData.newPlainText("ClipBridge", text)
                cm.setPrimaryClip(clip)
            }
        } catch (e: EOFException) {
            if (stopping) {
                updateStatus(false, "Disconnected")
            } else if (autoConnectEnabled) {
                updateStatus(false, "Waiting for $host:$port…")
            } else {
                updateStatus(false, "Disconnected")
            }
        } catch (e: Exception) {
            if (stopping) {
                updateStatus(false, "Disconnected")
            } else {
                if (autoConnectEnabled) {
                    updateStatus(false, "Waiting for $host:$port…")
                } else {
                    updateStatus(false, "Disconnected")
                }
            }
        } finally {
            isConnectedNow = false
            closeSocket()
            if (!stopping && autoConnectEnabled) {
                startForeground(NOTIF_ID, notifWaiting("$host:$port"))
                startReachabilityWatch()
            } else {
                startForeground(NOTIF_ID, notifDisconnected())
            }
        }
    }

    private suspend fun sendFramed(text: String): Boolean {
        return try {
            val out = output ?: return false
            val data = text.toByteArray(Charsets.UTF_8)
            val len = data.size
            if (len > MAX_BYTES) throw IOException("Too large")
            out.writeByte(MSG_TEXT.toInt())
            out.writeIntBE(len)
            out.write(data)
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun DataInputStream.readIntBE(): Int {
        val b = ByteArray(4)
        this.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun DataOutputStream.writeIntBE(v: Int) {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v)
        this.write(bb.array())
    }

    private fun updateStatus(connected: Boolean, text: String) {
        val i = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
            putExtra(EXTRA_CONNECTED, connected)
            putExtra(EXTRA_STATUS_TEXT, text)
        }
        applicationContext.sendBroadcast(i)
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_desc) }
            nm.createNotificationChannel(ch)
        }
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun notifConnecting(endpoint: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title_connecting))
            .setContentText(endpoint)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent())
            .build()

    private fun notifConnected(endpoint: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title_connected))
            .setContentText(endpoint)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent())
            .build()

    private fun notifWaiting(endpoint: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title_waiting))
            .setContentText(endpoint)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent())
            .build()

    private fun notifDisconnected(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title_disconnected))
            .setOngoing(true)
            .setContentIntent(contentPendingIntent())
            .build()

    private fun closeSocket() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null
        output = null
        socket = null
    }

    private fun stopSelfSafely() {
        stopping = true
        readerJob?.cancel()
        reachJob?.cancel()
        scope?.cancel()
        scope = null
        closeSocket()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSelfSafely()
    }
}

