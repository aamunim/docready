package com.docready.aamunim.ui.components

import com.docready.aamunim.data.FolderLogic
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import com.docready.aamunim.ui.components.AppCard
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
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
  var asZip by rememberSaveable { mutableStateOf(false) }
  // the Folders tab's folder: the one last used for this preset, else Scans
  val tree = app.folderTree
  val treeData by tree.data.collectAsState()
  var folderId by rememberSaveable { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    if (folderId == null) folderId = withContext(Dispatchers.IO) { tree.lastFolder(naming.preset) ?: tree.ensure(FolderLogic.SCANS) }
  }
  val folderPath = remember(treeData, folderId) { tree.path(folderId)?.ifEmpty { null } ?: FolderLogic.SCANS }

  var toPhone by rememberSaveable { mutableStateOf(true) }
  var choosing by remember { mutableStateOf(false) }
  // "Also upload to Drive" (when connected), remembered per preset
  val drive by com.docready.aamunim.drive.DriveAuth.account.collectAsState()
  var toDrive by rememberSaveable { mutableStateOf<Boolean?>(null) }
  LaunchedEffect(drive.connected) {
    if (toDrive == null && drive.connected) toDrive = withContext(Dispatchers.IO) { tree.driveFor(naming.preset) }
  }
  var busy by remember { mutableStateOf<String?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  // the files as they are now ("Fix to fit" replaces the ones it shrinks)
  var items by remember { mutableStateOf(files) }
  // (one ZIP goes to "ZIP exports")
  val folder = if (asZip && items.size > 1) FolderLogic.ZIPS else folderPath
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
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Ready to save", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        Text(
          "${items.size} file${if (items.size == 1) "" else "s"} · ${bytesText(items.sumOf { it.file.length() })}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      // the size check: each file against its item's limit
      VSpace(14)
      AppCard(Modifier.fillMaxWidth()) {
        Column {
          items.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FileSizeRow(names[i], f, facts[f.file])
          }
        }
      }
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
          border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
          colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(46.dp),
        ) {
          Icon(AppIcons.Bars, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Fix to fit (${tooBig.size} file${if (tooBig.size == 1) "" else "s"})", fontWeight = FontWeight.Bold)
        }
      }
      // the names the files get (the file-name pattern)
      FieldLabel("File names")
      Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        Text(
          names.firstOrNull().orEmpty() + if (names.size > 1) "  +${names.size - 1} more" else "",
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
          color = DocReadyColors.extra.text2,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      // where they go
      FieldLabel("Save to folder")
      Surface(
        onClick = { choosing = true },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().height(48.dp),
      ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(AppIcons.FolderNav, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
          }
          Spacer(Modifier.width(10.dp))
          Text(folder.replace("/", " › "), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
          Text("Change", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
      }
      if (items.size > 1) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { asZip = !asZip }, verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = asZip, onCheckedChange = { asZip = it })
          Text("Also make one ZIP", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
      }
      if (drive.connected) {
        Row(Modifier.fillMaxWidth().clickable { toDrive = toDrive != true }, verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = toDrive == true, onCheckedChange = { toDrive = it })
          Column {
            Text("Also upload to Drive", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("My Drive / DocReady / ${folder.replace("/", " / ")} · waits for a network", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
          modifier = Modifier.weight(1f).height(52.dp),
        ) {
          Text(if (asZip && items.size > 1) "Share ZIP" else "Share")
        }
        Button(
          onClick = {
            busy = "Saving…"
            error = null
            scope.launch {
              try {
                val ready = prepared()
                // remembered for this preset next time
                folderId?.let { id -> withContext(Dispatchers.IO) { tree.rememberFolder(naming.preset, id) } }
                when (val r = targets.phone.saveTo(context, ready, folder, preset = naming.preset)) {
                  is SaveOutcome.Saved -> {
                    // the saved files in the Drive queue (and the choice kept for this preset)
                    if (drive.connected) {
                      val up = toDrive == true
                      val links = r.files.map { it.uri }.toSet()
                      withContext(Dispatchers.IO) {
                        tree.rememberDrive(naming.preset, up)
                        if (up) {
                          val ids = tree.data.value.files.filter { it.uri in links }.map { it.id }.toSet()
                          com.docready.aamunim.drive.DriveQueue.enqueue(context, recordIds = ids)
                        }
                      }
                    }
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
          modifier = Modifier.weight(1.4f).height(52.dp),
        ) {
          Text(if (busy == "Saving…") "Saving…" else if (asZip && items.size > 1) "Save ZIP" else "Save to phone", fontWeight = FontWeight.Bold)
        }
      }
    }
  }
  if (choosing) {
    FolderPickerDialog(
      treeData.folders,
      folderId,
      onPick = {
        folderId = it
        choosing = false
      },
      onNew = { parent, name ->
        scope.launch { folderId = withContext(Dispatchers.IO) { tree.create(parent, name) } }
      },
      onDismiss = { choosing = false },
    )
  }
}

@Composable
private fun FieldLabel(text: String) {
  Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

/** A file in the save sheet: its kind, name, "X KB of Y KB · pixels / pages", and a badge */
@Composable
private fun FileSizeRow(name: String, f: ReadyFile, facts: FileFacts?) {
  val bytes = facts?.bytes ?: f.file.length()
  val st = fitState(bytes, f.maxKb)
  val dims = when {
    facts?.width != null && facts.height != null -> " · ${facts.width}×${facts.height}"
    facts?.pages != null -> " · ${facts.pages} page${if (facts.pages == 1) "" else "s"}"
    else -> ""
  }
  Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
    FileKindTile(name, 34)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        bytesText(bytes) + (f.maxKb?.let { " of $it KB" } ?: "") + dims + if (st == FitState.TOO_BIG) " · too big" else "",
        fontSize = 11.5.sp,
        fontWeight = if (st == FitState.TOO_BIG) FontWeight.SemiBold else FontWeight.Normal,
        color = if (st == FitState.TOO_BIG) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    fitLabel(st)?.let { StatusBadge(it, st) }
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
