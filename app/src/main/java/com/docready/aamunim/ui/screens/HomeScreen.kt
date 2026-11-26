package com.docready.aamunim.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docready.aamunim.data.Draft
import com.docready.aamunim.data.model.SavedFile
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.data.summary
import com.docready.aamunim.docReady
import com.docready.aamunim.save.ShareSaveTarget
import com.docready.aamunim.ui.components.AppCard
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.CircleIconButton
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.FileKindTile
import com.docready.aamunim.ui.components.SlimProgress
import com.docready.aamunim.ui.components.StatusBadge
import com.docready.aamunim.ui.components.fitLabel
import com.docready.aamunim.ui.components.fitState
import com.docready.aamunim.ui.theme.DocReadyColors
import com.docready.aamunim.ui.theme.Manrope
import com.docready.aamunim.ui.theme.SectionLabelStyle
import kotlinx.coroutines.launch

/** A saved file with the folder it is in */
private class FileInFolder(val file: SavedFile, val folder: String)

@Composable
fun HomeScreen(
  onScan: () -> Unit,
  onPhoto: () -> Unit,
  onSignature: () -> Unit,
  onPdfTools: () -> Unit,
  onSplit: () -> Unit,
  onPresets: () -> Unit,
  onNewPreset: () -> Unit,
  onStudents: () -> Unit = {},
  onCheckSize: () -> Unit = {},
  onDraft: (Draft) -> Unit = {},
  onPreset: (String) -> Unit,
  onFolder: (String) -> Unit,
  onAccounts: () -> Unit,
  onSearch: () -> Unit = {},
  onFolders: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val app = context.docReady
  val folders by app.folders.folders.collectAsState()
  val drafts by app.drafts.drafts.collectAsState()
  // (read once per visit; drafts older than 30 days are removed on the first read)
  LaunchedEffect(Unit) { app.drafts.refresh() }
  val scope = rememberCoroutineScope()
  var deleting by remember { mutableStateOf<Draft?>(null) }
  var allDrafts by rememberSaveable { mutableStateOf(false) }
  val recent = remember(folders) {
    folders.flatMap { f -> f.files.map { FileInFolder(it, f.name) } }.sortedByDescending { it.file.savedAt }.take(4)
  }

  Scaffold { padding ->
    Column(
      Modifier
        .padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // header: the logo, the name, search
      Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
          Icon(AppIcons.DocCheck, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text("DocReady", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, letterSpacing = (-0.3).sp)
          Text("Papers ready for any form", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CircleIconButton(AppIcons.Search, "Search students, presets and files", onSearch)
      }

      ScanCard(onScan)

      // the tools: 3 × 2
      val tiles = listOf(
        Tile("Photo", AppIcons.Person, ToneKind.GREEN, onPhoto),
        Tile("Signature", AppIcons.SignWave, ToneKind.AMBER, onSignature),
        Tile("PDF tools", AppIcons.DocLines, ToneKind.NEUTRAL, onPdfTools),
        Tile("Split by preset", AppIcons.SplitTiles, ToneKind.GREEN, onSplit),
        Tile("Check size", AppIcons.Bars, ToneKind.AMBER, onCheckSize),
        Tile("Presets", AppIcons.PresetList, ToneKind.NEUTRAL, onPresets),
      )
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in tiles.chunked(3)) {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (t in row) ToolTile(t, Modifier.weight(1f))
          }
        }
      }

      if (drafts.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          HeaderRow("Continue where you left off", if (drafts.size > 2) (if (allDrafts) "Fewer" else "All drafts (${drafts.size})") else null) { allDrafts = !allDrafts }
          for (d in if (allDrafts) drafts else drafts.take(2)) DraftCard(d, onOpen = { onDraft(d) }, onDelete = { deleting = d })
        }
      }

      if (recent.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          HeaderRow("Recent files", if (onFolders != null) "Open Folders" else null) { onFolders?.invoke() }
          AppCard(Modifier.fillMaxWidth()) {
            Column {
              recent.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FileRow(r.file, r.folder) {
                  try {
                    ShareSaveTarget.open(context, Uri.parse(r.file.uri), r.file.mime)
                  } catch (_: Exception) {
                    Toast.makeText(context, "This file is no longer on the phone, or no app can open it.", Toast.LENGTH_LONG).show()
                  }
                }
              }
            }
          }
        }
      }
    }
  }
  deleting?.let { d ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      title = { Text("Delete this draft?") },
      text = { Text("${d.title}\n${d.summary()}\n\nIts unfinished pages are thrown away. Files you already saved are not touched.") },
      confirmButton = {
        TextButton(onClick = {
          deleting = null
          scope.launch { app.drafts.discard(d.key) }
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
    )
  }
}

private enum class ToneKind { GREEN, AMBER, NEUTRAL }

private class Tile(val title: String, val icon: ImageVector, val tone: ToneKind, val onClick: () -> Unit)

/** "Scan papers": the big green card */
@Composable
private fun ScanCard(onClick: () -> Unit) {
  Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(56.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        Icon(AppIcons.ScanFrame, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
      }
      Spacer(Modifier.width(16.dp))
      Column(Modifier.weight(1f)) {
        Text("Scan papers", fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimary)
        Text("Shadows, folds and fingers cleaned", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f))
      }
      Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
    }
  }
}

@Composable
private fun ToolTile(t: Tile, modifier: Modifier) {
  val extra = DocReadyColors.extra
  val (bg, fg) = when (t.tone) {
    ToneKind.GREEN -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
    ToneKind.AMBER -> extra.warningContainer to MaterialTheme.colorScheme.tertiary
    ToneKind.NEUTRAL -> extra.chip to extra.text2
  }
  AppCard(modifier.heightIn(min = 96.dp), onClick = t.onClick) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.size(42.dp).background(bg, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
        Icon(t.icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
      }
      Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
  }
}

@Composable
private fun HeaderRow(title: String, link: String?, onLink: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(title, style = SectionLabelStyle, modifier = Modifier.weight(1f))
    if (link != null) TextButton(onClick = onLink) { Text(link, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
  }
}

/** A draft: a paper-like icon, what it is, how far it got (a bar for Split), delete */
@Composable
private fun DraftCard(d: Draft, onOpen: () -> Unit, onDelete: () -> Unit) {
  val extra = DocReadyColors.extra
  AppCard(Modifier.fillMaxWidth(), onClick = onOpen) {
    Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(
        Modifier.size(width = 44.dp, height = 54.dp).background(extra.chip, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        for (w in listOf(1f, 0.7f, 1f)) Box(Modifier.fillMaxWidth(w).height(3.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
      }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(d.title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val given = d.pages.count { it.item != null }
        val line = if (d.tool == "split" && d.pages.isNotEmpty()) "$given of ${d.pages.size} pages given · " + d.summary().substringAfter(" · ") else d.summary()
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (d.tool == "split" && d.pages.isNotEmpty()) SlimProgress(given.toFloat() / d.pages.size, Modifier.padding(top = 4.dp))
      }
      IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) { Icon(AppIcons.Close, contentDescription = "Delete draft") }
    }
  }
}

/** A saved file: its kind, name, size and folder, and how it stands against its limit */
@Composable
private fun FileRow(f: SavedFile, folder: String, onClick: () -> Unit) {
  Surface(onClick = onClick, color = Color.Transparent) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      FileKindTile(f.name)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${bytesText(f.bytes)} · $folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
      }
      val st = fitState(f.bytes, f.maxKb)
      fitLabel(st)?.let { StatusBadge(it, st) }
    }
  }
}

/** Search across presets, students and saved files (Home's search button) */
@Composable
fun SearchScreen(onBack: () -> Unit, onPreset: (String) -> Unit, onStudent: (String) -> Unit) {
  val context = LocalContext.current
  val app = context.docReady
  val presets by app.presets.presets.collectAsState()
  val students by app.students.students.collectAsState()
  val folders by app.folders.folders.collectAsState()
  var q by rememberSaveable { mutableStateOf("") }
  val t = q.trim()
  val foundPresets = if (t.isEmpty()) emptyList() else presets.filter { it.name.contains(t, ignoreCase = true) }.take(20)
  val foundStudents = if (t.isEmpty()) {
    emptyList()
  } else {
    students.filter { s -> listOfNotNull(s.name, s.roll, s.registration).any { it.contains(t, ignoreCase = true) } }.take(20)
  }
  val foundFiles = if (t.isEmpty()) {
    emptyList()
  } else {
    folders.flatMap { f -> f.files.map { FileInFolder(it, f.name) } }.filter { it.file.name.contains(t, ignoreCase = true) || it.folder.contains(t, ignoreCase = true) }.take(40)
  }
  Scaffold(topBar = { DocTopBar("Search", onBack) }) { padding ->
    LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      item {
        OutlinedTextField(
          value = q,
          onValueChange = { q = it },
          leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
          placeholder = { Text("Students, presets, files") },
          singleLine = true,
          shape = RoundedCornerShape(24.dp),
          modifier = Modifier.fillMaxWidth(),
        )
      }
      if (t.isNotEmpty() && foundPresets.isEmpty() && foundStudents.isEmpty() && foundFiles.isEmpty()) {
        item { Text("Nothing found for “$t”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
      }
      if (foundStudents.isNotEmpty()) item { Text("Students", style = SectionLabelStyle, modifier = Modifier.padding(top = 12.dp)) }
      items(foundStudents, key = { "s" + it.id }) { s ->
        AppCard(Modifier.fillMaxWidth(), onClick = { onStudent(s.id) }) {
          Column(Modifier.padding(14.dp)) {
            Text(s.name, fontWeight = FontWeight.Bold)
            listOfNotNull(s.roll?.let { "Roll $it" }, s.registration?.let { "Reg $it" }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
              Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
      if (foundPresets.isNotEmpty()) item { Text("Presets", style = SectionLabelStyle, modifier = Modifier.padding(top = 12.dp)) }
      items(foundPresets, key = { "p" + it.id }) { p ->
        AppCard(Modifier.fillMaxWidth(), onClick = { onPreset(p.id) }) {
          Column(Modifier.padding(14.dp)) {
            Text(p.name, fontWeight = FontWeight.Bold)
            Text("${p.items.size} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      if (foundFiles.isNotEmpty()) item { Text("Files", style = SectionLabelStyle, modifier = Modifier.padding(top = 12.dp)) }
      items(foundFiles, key = { "f" + it.file.uri }) { r ->
        AppCard(Modifier.fillMaxWidth()) {
          FileRow(r.file, r.folder) {
            try {
              ShareSaveTarget.open(context, Uri.parse(r.file.uri), r.file.mime)
            } catch (_: Exception) {
              Toast.makeText(context, "This file is no longer on the phone, or no app can open it.", Toast.LENGTH_LONG).show()
            }
          }
        }
      }
    }
  }
}
