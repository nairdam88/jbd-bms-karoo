package com.jbdbms.karoo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jbdbms.karoo.data.BmsRepository
import com.jbdbms.karoo.debug.DebugExport
import com.jbdbms.karoo.debug.DebugWebServer

/**
 * Debug tools: copy/share/export the connection log, plus a recovery action
 * for the Karoo's sticky BLE state.
 */
@Composable
fun DebugExportScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val connectionLog by BmsRepository.connectionLog.collectAsStateWithLifecycle()
    val settings by BmsRepository.settings.collectAsStateWithLifecycle()
    val scanResults by BmsRepository.scanResults.collectAsStateWithLifecycle()
    val rawFrames by BmsRepository.rawFrames.collectAsStateWithLifecycle()

    val report = remember(connectionLog, settings, scanResults, rawFrames) {
        DebugExport.build(context)
    }
    val clipboard = LocalClipboardManager.current
    val server = remember { DebugWebServer() }
    var webUrl by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { server.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Debug log",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        Text(
            "Copy/share this text, or open the web URL on your phone to copy it from there.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Button(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy") }
            OutlinedButton(onClick = { shareText(context, report) }) { Text("Share") }
            OutlinedButton(
                onClick = {
                    if (webUrl == null) {
                        webUrl = server.start { DebugExport.build(context) }
                    } else {
                        server.stop()
                        webUrl = null
                    }
                },
            ) { Text(if (webUrl == null) "Web export" else "Stop web") }
        }

        webUrl?.let { url ->
            Text(
                "Open on your phone:\n$url",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { BmsRepository.clearConnectionLog() }) { Text("Clear log") }
            TextButton(
                onClick = {
                    BmsRepository.resetLink()
                    openBluetoothSettings(context)
                },
            ) { Text("Reset link") }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SelectionContainer {
                Text(
                    report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "JBD BMS debug log")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share debug log"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app to share the log", Toast.LENGTH_SHORT).show()
    }
}

private fun openBluetoothSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Bluetooth settings unavailable", Toast.LENGTH_SHORT).show()
    }
}
