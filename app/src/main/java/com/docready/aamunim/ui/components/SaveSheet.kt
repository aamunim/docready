package com.docready.aamunim.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.style.TextOverflow
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.NameContext
import com.docready.aamunim.data.Naming
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.imaging.FileFacts
import com.docready.aamunim.imaging.SizeFixer
import com.docready.aamunim.save.Zipper
import com.docready.aamunim.ui.theme.DocReadyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
fun SaveSheet(
  files: List<ReadyFile>,
  defaultFolder: String,
  onDismiss: () -> Unit,
  /** What the file-name tokens stand for (preset, student) */
  naming: NameContext = NameContext(),
  /** Saved to the phone (the files as saved, with their final names) */
  onSaved: (List<ReadyFile>) -> Unit = {},
) {
  // (the screen's own context: an Activity, so the share sheet opens from it)
  val context = LocalContext.current
  val app = context.docReady
  val targets = app.saveTargets
  val folders by app.folders.folders.collectAsState()
  val settings by app.settings.naming.collectAsState()
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var folder by rememberSaveable { mutableStateOf(defaultFolder) }
  var toPhone by rememberSaveable { mutableStateOf(true) }
  var asZip by rememberSaveable { mutableStateOf(false) }
  var busy by remember { mutableStateOf<String?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  // the files as they are now ("Fix to fit" replaces the ones it shrinks)
  var items by remember { mutableStateOf(files) }
  val facts = remember { mutableStateMapOf<java.io.File, FileFacts>() }
  LaunchedEffect(items) {
    for (f in items) {
      if (f.file !in facts) facts[f.file] = withContext(Dispatchers.IO) { SizeFixer.facts(f.file, f.mime) }
    }
  }
  // the names the pattern gives (unique within this batch)
  val names = remember(items, settings, naming) {
    val used = mutableSetOf<String>()
    items.mapIndexed { i, f ->
      val dot = f.name.lastIndexOf('.')
      val base = if (dot > 0) f.name.substring(0, dot) else f.name
      val ext = if (dot > 0) f.name.substring(dot + 1) else "bin"
      FileNames.unique(Naming.apply(settings, naming, base, if (items.size > 1) i + 1 else 1), ext, used).also { used.add(it) }
    }
  }
  val tooBig = items.filter { f -> f.maxKb != null && f.file.length() > f.maxKb * 1024L }

  /** The files renamed by the pattern (and zipped if asked); off the main thread */
  suspend fun prepared(): List<ReadyFile> = withContext(Dispatchers.IO) {
    val renamed = items.mapIndexed { i, f ->
      val name = names[i]
      if (f.file.name == name) {
        f.copy(name = name)
      } else {
        // a copy under the new name in its own folder: the tool's file is never moved (it is
        // still shown, and may be shared, saved or added to a student again)
        val target = FileNames.outputFile(context, name)
        f.file.copyTo(target, overwrite = true)
        f.copy(file = target, name = name)
      }
    }
    if (asZip && renamed.size > 1) {
      val zipBase = Naming.apply(settings, naming, naming.preset ?: "documents", 1)
      listOf(Zipper.zip(context, renamed, zipBase))
    } else {
      renamed
    }
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
    ) {
      Text(
        if (items.size == 1) "Save “${names.firstOrNull() ?: ""}”" else "Save ${items.size} files",
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        "Pick where. Nothing leaves the phone unless you share it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
      // the size check: each file against its item's limit
      VSpace(10)
      items.forEachIndexed { i, f -> SizeRow(names[i], f, facts[f.file]) }
      if (tooBig.isNotEmpty()) {
        OutlinedButton(
          onClick = {
            busy = "Fitting…"
            error = null
            scope.launch {
              try {
                // each file on its own: one that can't be fitted (a PDF over 150 pages) keeps its
                // message, the others are still fixed
                val problems = mutableListOf<String>()
                val fixed = withContext(Dispatchers.Default) {
                  items.map { f ->
                    if (f !in tooBig) {
                      f
                    } else {
                      try {
                        SizeFixer.fit(context, f)
                      } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                      } catch (e: Throwable) {
                        problems.add("${f.name}: ${e.message ?: "could not be fitted."}")
                        f
                      }
                    }
                  }
                }
                items = fixed
                val still = fixed.count { it.maxKb != null && it.file.length() > it.maxKb * 1024L } - problems.size
                if (still > 0) problems.add("$still file(s) can't get under their limit, even at the lowest readable quality.")
                if (problems.isNotEmpty()) error = problems.joinToString("\n")
              } catch (e: Throwable) {
                error = e.message ?: "The files could not be fitted."
              } finally {
                busy = null
              }
            }
          },
          enabled = busy == null,
          modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text("Fix to fit (${tooBig.size} too big)") }
      }
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
      if (items.size > 1) {
        Row(Modifier.fillMaxWidth().clickable { asZip = !asZip }, verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = asZip, onCheckedChange = { asZip = it })
          Column {
            Text("As one ZIP", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text("Save or share all ${items.size} files in one .zip", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      busy?.let { BusyRow(it) }
      ErrorText(error)
      VSpace(14)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
          onClick = {
            busy = if (asZip) "Making the ZIP…" else "Preparing…"
            scope.launch {
              try {
                val r = targets.share.save(context, prepared(), folder)
                if (r is SaveOutcome.Failed) error = r.message
              } catch (e: Throwable) {
                error = e.message ?: "Sharing did not open."
              } finally {
                busy = null
              }
            }
          },
          enabled = busy == null,
          modifier = Modifier.height(52.dp),
        ) {
          Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text(if (asZip && items.size > 1) "Share ZIP" else "Share")
        }
        Button(
          onClick = {
            busy = "Saving…"
            error = null
            scope.launch {
              try {
                val ready = prepared()
                when (val r = targets.phone.save(context, ready, folder)) {
                  is SaveOutcome.Saved -> {
                    val what = if (r.names.size == 1) r.names[0] else "${r.names.size} files"
                    Toast.makeText(context, "Saved $what in ${r.where}" + (r.note?.let { "\n$it" } ?: ""), Toast.LENGTH_LONG).show()
                    onSaved(ready)
                    onDismiss()
                  }
                  is SaveOutcome.Failed -> error = r.message
                  SaveOutcome.Shared -> Unit
                }
              } catch (e: Throwable) {
                error = e.message ?: "The files could not be saved."
              } finally {
                busy = null
              }
            }
          },
          enabled = toPhone && busy == null && items.isNotEmpty(),
          modifier = Modifier.weight(1f).height(52.dp),
        ) {
          Text(if (busy == "Saving…") "Saving…" else if (asZip && items.size > 1) "Save ZIP" else "Save to phone")
        }
      }
    }
  }
}

/** One file of the size check: its name, size and pixels / pages, against its limit */
@Composable
fun SizeRow(name: String, f: ReadyFile, facts: FileFacts?) {
  val extra = DocReadyColors.extra
  val bytes = facts?.bytes ?: f.file.length()
  val limit = f.maxKb?.let { it * 1024L }
  // green: fits; amber: within 10 % of the limit; red: too big
  val (color, word) = when {
    limit == null -> MaterialTheme.colorScheme.onSurfaceVariant to null
    bytes > limit -> MaterialTheme.colorScheme.error to "too big"
    bytes > limit * 0.9 -> extra.onWarningContainer to "close"
    else -> extra.success to "fits"
  }
  val dims = when {
    facts?.width != null && facts.height != null -> " · ${facts.width} × ${facts.height} px"
    facts?.pages != null -> " · ${facts.pages} page${if (facts.pages == 1) "" else "s"}"
    else -> ""
  }
  Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) {
      Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        bytesText(bytes) + (f.maxKb?.let { " of max $it KB" } ?: "") + dims + (word?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = color,
      )
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
