package io.ergolyam.clipbridge.ui.screen

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.ergolyam.clipbridge.data.Prefs
import io.ergolyam.clipbridge.net.ClipClientService
import io.ergolyam.clipbridge.notify.Notifications
import kotlin.system.exitProcess

@Composable
fun ClipBridgeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext

    var host by remember { mutableStateOf(Prefs.getHost(appCtx)) }
    var port by remember { mutableStateOf(Prefs.getPort(appCtx).toString()) }
    var autoConnect by remember { mutableStateOf(Prefs.getAutoConnect(appCtx)) }
    var connected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Disconnected") }
    var outgoing by remember { mutableStateOf("") }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        if (Prefs.getAutoConnect(appCtx)) {
            val p = port.toIntOrNull() ?: Prefs.getPort(appCtx)
            val h = if (host.isBlank()) Prefs.getHost(appCtx) else host.trim()
            val i = Intent(ctx, ClipClientService::class.java).apply {
                action = ClipClientService.ACTION_START
                putExtra(ClipClientService.EXTRA_HOST, h)
                putExtra(ClipClientService.EXTRA_PORT, p)
                putExtra(ClipClientService.EXTRA_AUTOCONNECT, true)
            }
            ContextCompat.startForegroundService(ctx, i)
            status = "Waiting for $h:$p…"
        }
    }

    LaunchedEffect(autoConnect) {
        if (autoConnect) {
            val p = port.toIntOrNull() ?: Prefs.getPort(appCtx)
            val h = if (host.isBlank()) Prefs.getHost(appCtx) else host.trim()
            val i = Intent(ctx, ClipClientService::class.java).apply {
                action = ClipClientService.ACTION_START
                putExtra(ClipClientService.EXTRA_HOST, h)
                putExtra(ClipClientService.EXTRA_PORT, p)
                putExtra(ClipClientService.EXTRA_AUTOCONNECT, true)
            }
            ContextCompat.startForegroundService(ctx, i)
            status = "Waiting for $h:$p…"
        }
    }

    DisposableEffect(Unit) {
        val f = IntentFilter().apply { addAction(ClipClientService.ACTION_STATUS) }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ClipClientService.ACTION_STATUS) {
                    connected = intent.getBooleanExtra(ClipClientService.EXTRA_CONNECTED, false)
                    status = intent.getStringExtra(ClipClientService.EXTRA_STATUS_TEXT) ?: ""
                }
            }
        }
        ContextCompat.registerReceiver(appCtx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { appCtx.unregisterReceiver(r) }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Status: $status")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Server IP") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("Port") },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Auto-connect", modifier = Modifier.weight(1f))
            Switch(
                checked = autoConnect,
                onCheckedChange = {
                    autoConnect = it
                    Prefs.setAutoConnect(appCtx, it)
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val p = port.toIntOrNull() ?: 28900
                    Prefs.saveEndpoint(appCtx, host.trim(), p)
                    val i = Intent(ctx, ClipClientService::class.java).apply {
                        action = ClipClientService.ACTION_START
                        putExtra(ClipClientService.EXTRA_HOST, host.trim())
                        putExtra(ClipClientService.EXTRA_PORT, p)
                        putExtra(ClipClientService.EXTRA_AUTOCONNECT, Prefs.getAutoConnect(appCtx))
                    }
                    ContextCompat.startForegroundService(ctx, i)
                },
                enabled = !connected
            ) { Text("Connect") }

            Button(
                onClick = {
                    val i = Intent(ctx, ClipClientService::class.java).apply {
                        action = ClipClientService.ACTION_STOP
                    }
                    ctx.startService(i)
                },
                enabled = connected
            ) { Text("Disconnect") }

            Button(
                onClick = {
                    val stopIntent = Intent(ctx, ClipClientService::class.java).apply {
                        action = ClipClientService.ACTION_STOP
                    }
                    ctx.startService(stopIntent)

                    val nm = ContextCompat.getSystemService(ctx, NotificationManager::class.java)
                    nm?.cancel(Notifications.NOTIF_ID)

                    val act = ctx as? Activity
                    if (Build.VERSION.SDK_INT >= 21) {
                        act?.finishAndRemoveTask()
                    } else {
                        act?.finish()
                    }

                    exitProcess(0)
                }
            ) { Text("Exit") }
        }

        if (connected) {
            TextField(
                value = outgoing,
                onValueChange = { outgoing = it },
                minLines = 3,
                maxLines = 8,
                label = { Text("Text to send to PC") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 200.dp)
            )
            Button(
                onClick = {
                    val i = Intent(ctx, ClipClientService::class.java).apply {
                        action = ClipClientService.ACTION_SEND_TEXT
                        putExtra(ClipClientService.EXTRA_TEXT, outgoing)
                    }
                    ctx.startService(i)
                },
                enabled = outgoing.isNotBlank()
            ) { Text("Send to PC") }
        }
    }
}

