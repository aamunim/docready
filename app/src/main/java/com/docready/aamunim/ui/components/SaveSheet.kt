package com.docready.aamunim.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docready.aamunim.docReady
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.save.SaveOutcome
import com.docready.aamunim.save.SaveTarget
import kotlinx.coroutines.launch

/**
 * Save or share ready files: "This phone" (Downloads/DocReady/<folder>) and "Share"; the
 * Google Drive and ITTC rows are shown, disabled, as "Coming soon" (Phase 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveSheet(files: List<ReadyFile>, defaultFolder: String, onDismiss: () -> Unit) {
  // (the screen's own context: an Activity, so the share sheet opens from it)
  val context = LocalContext.current
  val app = context.docReady
  val targets = app.saveTargets
  val folders by app.folders.folders.collectAsState()
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var folder by rememberSaveable { mutableStateOf(defaultFolder) }
  var toPhone by rememberSaveable { mutableStateOf(true) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
    ) {
      Text(
        if (files.size == 1) "Save “${files[0].name}”" else "Save ${files.size} files",
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        "Pick where. Nothing leaves the phone unless you share it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
      VSpace(14)
      OutlinedTextField(
        value = folder,
        onValueChange = { folder = it.take(60) },
        label = { Text("Folder") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      val recent = folders.map { it.name }.filter { it != folder }.take(6)
      if (recent.isNotEmpty()) {
        Row(
          Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          for (name in recent) FilterChip(selected = false, onClick = { folder = name }, label = { Text(name) })
        }
      }
      VSpace(10)
      TargetRow(targets.phone, folder, checked = toPhone, onChecked = { toPhone = it })
      TargetRow(targets.googleDrive, folder, checked = false, onChecked = {})
      TargetRow(targets.ittc, folder, checked = false, onChecked = {})
      ErrorText(error)
      VSpace(14)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
          onClick = {
            scope.launch {
              val r = targets.share.save(context, files, folder)
              if (r is SaveOutcome.Failed) error = r.message
            }
          },
          enabled = !busy,
          modifier = Modifier.height(52.dp),
        ) {
          Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Share")
        }
        Button(
          onClick = {
            busy = true
            error = null
            scope.launch {
              when (val r = targets.phone.save(context, files, folder)) {
                is SaveOutcome.Saved -> {
                  val names = if (r.names.size == 1) r.names[0] else "${r.names.size} files"
                  Toast.makeText(context, "Saved $names in ${r.where}" + (r.note?.let { "\n$it" } ?: ""), Toast.LENGTH_LONG).show()
                  busy = false
                  onDismiss()
                }
                is SaveOutcome.Failed -> {
                  error = r.message
                  busy = false
                }
                SaveOutcome.Shared -> busy = false
              }
            }
          },
          enabled = toPhone && !busy && files.isNotEmpty(),
          modifier = Modifier.weight(1f).height(52.dp),
        ) {
          Text(if (busy) "Saving…" else "Save to phone")
        }
      }
    }
  }
}

@Composable
private fun TargetRow(target: SaveTarget, folder: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
  val enabled = target.available
  Row(
    Modifier
      .fillMaxWidth()
      .then(if (enabled) Modifier.clickable { onChecked(!checked) } else Modifier)
      .padding(vertical = 6.dp)
      .alpha(if (enabled) 1f else 0.55f),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = checked, onCheckedChange = if (enabled) onChecked else null, enabled = enabled)
    Column(Modifier.weight(1f)) {
      Text(target.kind.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
      Text(target.description(folder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!enabled) Badge("Coming soon")
  }
}
