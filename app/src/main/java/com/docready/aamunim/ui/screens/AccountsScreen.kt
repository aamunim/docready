package com.docready.aamunim.ui.screens

import android.widget.Toast
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.docready.aamunim.data.DriveLogic
import com.docready.aamunim.docReady
import com.docready.aamunim.drive.DriveAuth
import com.docready.aamunim.drive.DriveQueue
import com.docready.aamunim.ui.components.AppCard
import com.docready.aamunim.ui.theme.DocReadyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.docready.aamunim.ui.components.ScreenTitle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.Badge
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.IconTile
import com.docready.aamunim.ui.components.SectionLabel

/**
 * Settings: Google Drive (optional, the user's own account); DocReady works fully
 * on the phone without them.
 */
@Composable
fun AccountsScreen(onBack: (() -> Unit)?, onFileNames: () -> Unit = {}) {
  val context = LocalContext.current
  val version = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
  } catch (_: Exception) {
    ""
  }
  Scaffold(topBar = { if (onBack != null) DocTopBar("Settings", onBack) }) { padding ->
    Column(
      Modifier
        .padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (onBack == null) ScreenTitle("Settings", "Accounts, file names, privacy")
      Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      DriveCard()
      SectionLabel("Settings")
      androidx.compose.material3.OutlinedButton(onClick = onFileNames, modifier = Modifier.fillMaxWidth()) { Text("File names") }
      SectionLabel("Privacy")
      Text(
        "Everything is made on this phone, and there are no analytics. Files leave the phone only when " +
          "you share them, or when you choose to upload them to your own Google Drive (DocReady can see " +
          "only the files it puts there).",
        style = MaterialTheme.typography.bodyMedium,
      )
      if (version.isNotEmpty()) {
        Text("DocReady $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      }
    }
  }
}

@Composable
private fun AccountCard(icon: ImageVector, title: String, text: String, button: String) {
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        Spacer(Modifier.width(12.dp))
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Badge("Coming soon")
      }
      Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp),
      )
      Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(button) }
    }
  }
}

/**
 * Google Drive with the user's own Google account: Connect / Disconnect, the account, and the
 * upload queue ("N waiting · Retry all"). A plain outlined button (no blue).
 */
@Composable
private fun DriveCard() {
  val context = LocalContext.current
  val app = context.docReady
  val scope = rememberCoroutineScope()
  val account by DriveAuth.account.collectAsState()
  val tree by app.folderTree.data.collectAsState()
  val students by app.students.students.collectAsState()
  var busy by remember { mutableStateOf(false) }
  val states = tree.files.map { it.drive } + students.flatMap { s -> s.files.map { it.drive } }
  val waiting = DriveLogic.waiting(states)
  val failed = DriveLogic.failed(states)
  val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
    busy = false
    if (res.resultCode == android.app.Activity.RESULT_OK) {
      try {
        DriveAuth.fromIntent(context, res.data)
        Toast.makeText(context, "Google Drive connected", Toast.LENGTH_SHORT).show()
        if (waiting > 0 || failed > 0) scope.launch { withContext(Dispatchers.IO) { DriveQueue.retryAll(context) } }
      } catch (e: Exception) {
        Toast.makeText(context, e.message ?: "Google Drive was not connected.", Toast.LENGTH_LONG).show()
      }
    }
  }
  AppCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(AppIcons.Drive, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text("Google Drive", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
          Text(
            if (account.connected) "Connected" + (account.email?.let { " · $it" } ?: "") else "Optional · your own Google account",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Text(
        "Upload files you choose to My Drive / DocReady, in the same folders as on the phone. DocReady can see only " +
          "the files it puts there (drive.file). Uploads wait for a network.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp),
      )
      if (account.connected && (waiting > 0 || failed > 0)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(
            listOfNotNull(if (waiting > 0) "$waiting waiting" else null, if (failed > 0) "$failed failed" else null).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (failed > 0) DocReadyColors.extra.onWarningContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { DriveQueue.retryAll(context) } } }) { Text("Retry all") }
        }
      }
      if (account.connected) {
        androidx.compose.material3.OutlinedButton(
          onClick = {
            busy = true
            scope.launch {
              DriveQueue.disconnect(context)
              busy = false
            }
          },
          enabled = !busy,
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp),
        ) { Text("Disconnect") }
      } else {
        androidx.compose.material3.OutlinedButton(
          onClick = {
            busy = true
            scope.launch {
              try {
                when (val o = DriveAuth.authorize(context)) {
                  is DriveAuth.Outcome.NeedsUser -> consent.launch(IntentSenderRequest.Builder(o.intent.intentSender).build())
                  is DriveAuth.Outcome.Token -> {
                    busy = false
                    Toast.makeText(context, "Google Drive connected", Toast.LENGTH_SHORT).show()
                  }
                }
              } catch (e: Exception) {
                busy = false
                Toast.makeText(context, "Google Drive could not be connected: ${e.message ?: "Google Play services"}", Toast.LENGTH_LONG).show()
              }
            }
          },
          enabled = !busy,
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp),
        ) { Text(if (busy) "Connecting…" else "Connect Google Drive", fontWeight = FontWeight.SemiBold) }
      }
    }
  }
}
