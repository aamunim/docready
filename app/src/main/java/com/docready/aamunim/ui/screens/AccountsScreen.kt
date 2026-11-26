package com.docready.aamunim.ui.screens

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
 * Accounts: both optional. Phase 1 shows them as "Coming soon"; DocReady works fully
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
      Text(
        "Optional. Without it DocReady works fully on this phone.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      AccountCard(
        icon = AppIcons.Drive,
        title = "Google Drive",
        text = "Save ready files to your own Google Drive. DocReady will see only the files it puts there, " +
          "in a DocReady folder. No ITTC account needed.",
        button = "Connect Google Drive",
      )
      SectionLabel("Settings")
      androidx.compose.material3.OutlinedButton(onClick = onFileNames, modifier = Modifier.fillMaxWidth()) { Text("File names") }
      SectionLabel("Privacy")
      Text(
        "Everything is made on this phone. This version has no internet access and no analytics: " +
          "files leave the phone only when you share them.",
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
