package com.docready.aamunim.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.docready.aamunim.data.DriveState
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.FileRecord
import com.docready.aamunim.data.FolderData
import com.docready.aamunim.data.FolderLogic
import com.docready.aamunim.data.FolderNode
import com.docready.aamunim.data.model.Preset
import com.docready.aamunim.data.model.Student
import com.docready.aamunim.data.model.StudentFile
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.docReady
import com.docready.aamunim.save.DownloadsFiles
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.save.SaveOutcome
import com.docready.aamunim.save.ShareSaveTarget
import com.docready.aamunim.save.Zipper
import com.docready.aamunim.ui.components.AppCard
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.CircleIconButton
import com.docready.aamunim.ui.components.FileKindTile
import com.docready.aamunim.ui.components.FitState
import com.docready.aamunim.ui.components.FolderPickerDialog
import com.docready.aamunim.ui.components.StatusBadge
import com.docready.aamunim.ui.components.fitLabel
import com.docready.aamunim.ui.components.fitState
import com.docready.aamunim.ui.theme.DocReadyColors
import com.docready.aamunim.ui.theme.SectionLabelStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A file as a folder shows it: a saved file's record, or one of a student's files */
class FolderFile(
  val key: String,
  val name: String,
  val mime: String,
  val bytes: Long,
  val savedAt: Long,
  val maxKb: Int?,
  val record: FileRecord? = null,
  val student: Pair<String, StudentFile>? = null,
) {
  val drive: DriveState get() = record?.drive ?: DriveState.NONE

  /** Its bytes (blocking): the Downloads link, or the student's own copy */
  fun open(context: Context): java.io.InputStream? = try {
    when {
      record != null -> context.contentResolver.openInputStream(Uri.parse(record.uri))
      student != null -> File(student.second.path).takeIf { it.exists() }?.inputStream()
      else -> null
    }
  } catch (_: Exception) {
    null
  }
}

private fun recordFile(r: FileRecord) = FolderFile("r:" + r.id, r.name, r.mime, r.bytes, r.savedAt, r.maxKb, record = r)

private fun studentFile(s: Student, f: StudentFile, preset: Preset?): FolderFile {
  val limit = f.itemId?.let { id -> preset?.items?.firstOrNull { it.id == id }?.maxKb }
  return FolderFile("s:" + s.id + ":" + f.path, f.name, f.mime, f.bytes, f.savedAt, limit, student = s.id to f)
}

/** All the files on this phone the Folders tab knows: saved files and students' files */
private fun allFiles(d: FolderData, students: List<Student>, presets: List<Preset>): List<FolderFile> =
  d.files.map(::recordFile) + students.flatMap { s -> s.files.map { studentFile(s, it, presets.firstOrNull { p -> p.id == s.presetId }) } }

private enum class FolderSort(val title: String) { NAME("name"), DATE("date"), SIZE("size") }

private enum class FolderChip(val title: String) { ALL("All"), STUDENTS("Students"), PRESETS("Presets"), RECENT("Recent") }

/** Files counted: how many, how big, how many too big */
private fun sizeOf(files: List<FolderFile>) = files.sumOf { it.bytes }

private fun mb(bytes: Long): String = if (bytes < 1024 * 1024) bytesText(bytes) else String.format(java.util.Locale.US, "%.0f MB", bytes / 1048576.0)

/**
 * The Folders tab: search, All / Students / Presets / Recent, what is on the phone (PDF /
 * photos / ZIP), pinned folders as cards, all folders as a list, and New folder.
 */
@Composable
fun FoldersScreen(onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val app = context.docReady
  val tree = app.folderTree
  val data by tree.data.collectAsState()
  val loaded by tree.loaded.collectAsState()
  val students by app.students.students.collectAsState()
  val presets by app.presets.presets.collectAsState()
  var sort by rememberSaveable { mutableStateOf(FolderSort.NAME) }
  var grid by rememberSaveable { mutableStateOf(false) }
  var chip by rememberSaveable { mutableStateOf(FolderChip.ALL) }
  var query by rememberSaveable { mutableStateOf("") }
  var naming by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val everything = remember(data, students, presets) { allFiles(data, students, presets) }

  fun filesIn(id: String): List<FolderFile> {
    val inside = FolderLogic.subtree(data.folders, id)
    return data.files.filter { it.folderId in inside }.map(::recordFile)
  }
  fun sorted(list: List<FolderNode>): List<FolderNode> = when (sort) {
    FolderSort.NAME -> list.sortedBy { it.name.lowercase() }
    FolderSort.DATE -> list.sortedByDescending { it.updatedAt }
    FolderSort.SIZE -> list.sortedByDescending { sizeOf(filesIn(it.id)) }
  }

  Scaffold(
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = { naming = true },
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        icon = { Icon(AppIcons.Add, contentDescription = null) },
        text = { Text("New folder", fontWeight = FontWeight.Bold) },
      )
    },
  ) { padding ->
    LazyVerticalGrid(
      columns = GridCells.Fixed(2),
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.padding(padding).fillMaxSize(),
    ) {
      val full = GridItemSpan(2)
      item(span = { full }) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Folders", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
          CircleIconButton(AppIcons.Sort, "Sorted by ${sort.title}. Tap to change", {
            sort = FolderSort.entries[(sort.ordinal + 1) % FolderSort.entries.size]
            Toast.makeText(context, "Sorted by ${sort.title}", Toast.LENGTH_SHORT).show()
          })
          CircleIconButton(if (grid) AppIcons.ListLines else AppIcons.Grid, if (grid) "Show as a list" else "Show as a grid", { grid = !grid })
        }
      }
      item(span = { full }) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
          placeholder = { Text("Search folders and files") },
          singleLine = true,
          shape = RoundedCornerShape(24.dp),
          modifier = Modifier.fillMaxWidth(),
        )
      }
      item(span = { full }) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (c in FolderChip.entries) FilterChip(selected = chip == c, onClick = { chip = c }, label = { Text(c.title) })
        }
      }
      if (!loaded) {
        item(span = { full }) { com.docready.aamunim.ui.components.BusyRow("Reading your folders…") }
        return@LazyVerticalGrid
      }
      item(span = { full }) { StorageCard(everything) }

      val q = query.trim()
      if (q.isNotEmpty()) {
        val folders = data.folders.filter { it.name.contains(q, ignoreCase = true) }
        val files = everything.filter { it.name.contains(q, ignoreCase = true) }
        item(span = { full }) { Text("Found", style = SectionLabelStyle) }
        if (folders.isEmpty() && files.isEmpty()) item(span = { full }) { Text("Nothing found for “$q”.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        for (f in folders) item(span = { full }, key = "sf" + f.id) { FolderRow(f.name, tree.path(f.id) ?: "", AppIcons.FolderNav, false) { onOpen(f.id) } }
        for (f in files.take(60)) item(span = { full }, key = "sx" + f.key) { FileListRow(f) { openFile(context, scope, f) } }
        return@LazyVerticalGrid
      }

      when (chip) {
        FolderChip.ALL -> {
          val pinned = sorted(data.folders.filter { it.pinned })
          if (pinned.isNotEmpty()) {
            item(span = { full }) { Text("Pinned", style = SectionLabelStyle) }
            for ((i, f) in pinned.withIndex()) {
              val inside = filesIn(f.id)
              item(key = "p" + f.id) { PinnedCard(f.name, "${inside.size} files · ${mb(sizeOf(inside))}", i % 2 == 0) { onOpen(f.id) } }
            }
          }
          item(span = { full }) { Text("All folders", style = SectionLabelStyle, modifier = Modifier.padding(top = 4.dp)) }
          val top = sorted(FolderLogic.children(data.folders, null))
          if (grid) {
            if (students.isNotEmpty()) item(key = "students") { PinnedCard("Students", "${students.size} folders", true, starred = false) { onOpen("students") } }
            for ((i, f) in top.withIndex()) {
              val inside = filesIn(f.id)
              item(key = "g" + f.id) { PinnedCard(f.name, "${inside.size} files · ${mb(sizeOf(inside))}", i % 2 == 1, starred = f.pinned) { onOpen(f.id) } }
            }
          } else {
            item(span = { full }) {
              AppCard(Modifier.fillMaxWidth()) {
                Column {
                  var first = true
                  if (students.isNotEmpty()) {
                    FolderRow("Students", "Made from each student · ${students.size} folders", AppIcons.People, true) { onOpen("students") }
                    first = false
                  }
                  for (f in top) {
                    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    first = false
                    val inside = filesIn(f.id)
                    val sub = when (f.name) {
                      FolderLogic.SCANS -> "Not in a folder yet · ${inside.size} files"
                      else -> "${inside.size} files · ${mb(sizeOf(inside))}"
                    }
                    FolderRow(f.name, sub, AppIcons.FolderNav, false, starred = f.pinned) { onOpen(f.id) }
                  }
                  if (first) Text("No folders yet: saved files go to Scans, or make a folder.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp))
                }
              }
            }
          }
        }
        FolderChip.STUDENTS -> {
          if (students.isEmpty()) item(span = { full }) { Text("No students yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
          val list = when (sort) {
            FolderSort.NAME -> students.sortedBy { it.name.lowercase() }
            FolderSort.DATE -> students.sortedByDescending { it.updatedAt }
            FolderSort.SIZE -> students.sortedByDescending { s -> s.files.sumOf { it.bytes } }
          }
          for (s in list) item(span = { full }, key = "st" + s.id) {
            AppCard(Modifier.fillMaxWidth()) { FolderRow(s.name, "${s.files.size} files · ${mb(s.files.sumOf { it.bytes })}", AppIcons.People, true) { onOpen("student:" + s.id) } }
          }
        }
        FolderChip.PRESETS -> {
          // grouped by the preset each file was made for
          val groups = everything.groupBy { f ->
            f.record?.preset ?: f.student?.let { (sid, _) -> students.firstOrNull { it.id == sid }?.presetId?.let { pid -> presets.firstOrNull { it.id == pid }?.name } }
          }.filterKeys { it != null }
          if (groups.isEmpty()) item(span = { full }) { Text("No files made for a preset yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
          for ((name, files) in groups.entries.sortedBy { it.key!!.lowercase() }) item(span = { full }, key = "pr$name") {
            AppCard(Modifier.fillMaxWidth()) { FolderRow(name!!, "${files.size} files · ${mb(sizeOf(files))}", AppIcons.PresetList, false) { onOpen("preset:$name") } }
          }
        }
        FolderChip.RECENT -> {
          val recent = everything.sortedByDescending { it.savedAt }.take(40)
          if (recent.isEmpty()) item(span = { full }) { Text("No files yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
          for (f in recent) item(span = { full }, key = "r" + f.key) { AppCard(Modifier.fillMaxWidth()) { FileListRow(f) { openFile(context, scope, f) } } }
        }
      }
    }
  }
  if (naming) {
    NameDialog("New folder", "") { name ->
      naming = false
      if (name != null) scope.launch { withContext(Dispatchers.IO) { tree.create(null, name) } }
    }
  }
}

/** "On this phone · N files · MB" and a bar per kind (PDF green, photos amber, ZIP grey) */
@Composable
private fun StorageCard(files: List<FolderFile>) {
  val pdf = files.filter { it.mime == "application/pdf" }
  val photos = files.filter { it.mime.startsWith("image/") }
  val zips = files.filter { it.mime == "application/zip" }
  val total = sizeOf(files).coerceAtLeast(1)
  val grey = Color(0xFF8C857A)
  AppCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row {
        Text("On this phone", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${files.size} files · ${mb(sizeOf(files))}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Row(Modifier.fillMaxWidth().height(8.dp).background(DocReadyColors.extra.chip, RoundedCornerShape(4.dp))) {
        for ((list, color) in listOf(pdf to MaterialTheme.colorScheme.primary, photos to MaterialTheme.colorScheme.tertiary, zips to grey)) {
          val part = sizeOf(list).toFloat() / total
          if (part > 0f) Box(Modifier.weight(part).height(8.dp).background(color))
        }
        val rest = 1f - (sizeOf(pdf) + sizeOf(photos) + sizeOf(zips)).toFloat() / total
        if (rest > 0.001f) Spacer(Modifier.weight(rest))
      }
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((label, list, color) in listOf(Triple("PDF", pdf, MaterialTheme.colorScheme.primary), Triple("Photos", photos, MaterialTheme.colorScheme.tertiary), Triple("ZIP", zips, grey))) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text("$label ${list.size} · ${mb(sizeOf(list))}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }
  }
}

@Composable
private fun PinnedCard(name: String, sub: String, green: Boolean, starred: Boolean = true, onClick: () -> Unit) {
  val extra = DocReadyColors.extra
  AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          Modifier.size(40.dp).background(if (green) MaterialTheme.colorScheme.primaryContainer else extra.warningContainer, RoundedCornerShape(12.dp)),
          contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.FolderNav, contentDescription = null, tint = if (green) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.weight(1f))
        if (starred) Icon(AppIcons.Star, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
      }
      Column {
        Text(name, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
      }
    }
  }
}

@Composable
private fun FolderRow(name: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, green: Boolean, starred: Boolean = false, onClick: () -> Unit) {
  val extra = DocReadyColors.extra
  Surface(onClick = onClick, color = Color.Transparent) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(36.dp).background(if (green) MaterialTheme.colorScheme.primaryContainer else extra.chip, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (green) MaterialTheme.colorScheme.primary else extra.text2, modifier = Modifier.size(20.dp))
      }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (starred) Icon(AppIcons.Star, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
      Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
  }
}

@Composable
private fun FileListRow(f: FolderFile, onClick: () -> Unit) {
  Surface(onClick = onClick, color = Color.Transparent) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      FileKindTile(f.name)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(bytesText(f.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      DriveMark(f.drive)
      val st = fitState(f.bytes, f.maxKb)
      fitLabel(st)?.let { StatusBadge(it, st) }
    }
  }
}

/** A file's Drive state: uploaded (cloud), waiting (dots), failed (amber) */
@Composable
fun DriveMark(state: DriveState) {
  when (state) {
    DriveState.DONE -> Icon(AppIcons.Cloud, contentDescription = "On Google Drive", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp).size(18.dp))
    DriveState.QUEUED, DriveState.UPLOADING -> androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.padding(end = 6.dp).size(16.dp), strokeWidth = 2.dp)
    DriveState.FAILED -> Icon(AppIcons.Warning, contentDescription = "Drive upload failed", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(end = 6.dp).size(18.dp))
    DriveState.NONE -> Unit
  }
}

/** A name asked for (new folder, rename); null = cancelled */
@Composable
private fun NameDialog(title: String, start: String, onDone: (String?) -> Unit) {
  var name by remember { mutableStateOf(start) }
  AlertDialog(
    onDismissRequest = { onDone(null) },
    title = { Text(title) },
    text = { OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
    confirmButton = { Button(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text("OK") } },
    dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
  )
}

/** A file opened in another app (a student's own copy goes out as a copy) */
private fun openFile(context: Context, scope: kotlinx.coroutines.CoroutineScope, f: FolderFile) {
  scope.launch {
    try {
      val uri = withContext(Dispatchers.IO) {
        if (f.record != null) {
          Uri.parse(f.record.uri)
        } else {
          val out = FileNames.outputFile(context, f.name)
          File(f.student!!.second.path).copyTo(out, overwrite = true)
          FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
        }
      }
      ShareSaveTarget.open(context, uri, f.mime)
    } catch (_: Exception) {
      Toast.makeText(context, "This file is no longer on the phone, or no app can open it.", Toast.LENGTH_LONG).show()
    }
  }
}

/**
 * Inside a folder ([key]: a folder's id, "students", "student:<id>" or "preset:<name>"):
 * breadcrumb, counters, sub-folders, the files as a grid; long-press selects (Move, Delete,
 * Share as ZIP); otherwise Add files and Share folder as ZIP; the folder's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderOpenScreen(key: String, onBack: () -> Unit, onOpen: (String) -> Unit, onStudent: (String) -> Unit = {}) {
  val context = LocalContext.current
  val app = context.docReady
  val tree = app.folderTree
  val data by tree.data.collectAsState()
  val students by app.students.students.collectAsState()
  val presets by app.presets.presets.collectAsState()
  val scope = rememberCoroutineScope()
  val selected = remember { mutableStateListOf<String>() }
  var busy by remember { mutableStateOf<String?>(null) }
  var menu by remember { mutableStateOf(false) }
  var renaming by remember { mutableStateOf(false) }
  var deletingFolder by remember { mutableStateOf(false) }
  var deletingFiles by remember { mutableStateOf(false) }
  var moving by remember { mutableStateOf(false) }
  var newSub by remember { mutableStateOf(false) }

  val folder = data.folders.firstOrNull { it.id == key }
  val student = if (key.startsWith("student:")) students.firstOrNull { it.id == key.removePrefix("student:") } else null
  val presetName = if (key.startsWith("preset:")) key.removePrefix("preset:") else null
  val title = folder?.name ?: student?.name ?: presetName ?: if (key == "students") "Students" else "Folder"
  val crumb = when {
    folder != null -> "Folders › " + (tree.path(folder.id) ?: folder.name).replace("/", " › ")
    student != null -> "Folders › Students › ${student.name}"
    presetName != null -> "Folders › Presets › $presetName"
    else -> "Folders › Students"
  }
  val subs: List<Triple<String, String, String>> = when {
    folder != null -> FolderLogic.children(data.folders, folder.id).sortedBy { it.name.lowercase() }.map { f ->
      Triple(f.id, f.name, "${data.files.count { it.folderId in FolderLogic.subtree(data.folders, f.id) }} files")
    }
    key == "students" -> students.sortedBy { it.name.lowercase() }.map { Triple("student:" + it.id, it.name, "${it.files.size} files") }
    else -> emptyList()
  }
  val files: List<FolderFile> = when {
    folder != null -> data.files.filter { it.folderId == folder.id }.map(::recordFile)
    student != null -> student.files.map { studentFile(student, it, presets.firstOrNull { p -> p.id == student.presetId }) }
    presetName != null -> allFiles(data, students, presets).filter { f ->
      f.record?.preset == presetName || f.student?.let { (sid, _) -> students.firstOrNull { it.id == sid }?.presetId?.let { pid -> presets.firstOrNull { it.id == pid }?.name } } == presetName
    }
    else -> emptyList()
  }.sortedByDescending { it.savedAt }
  val chosen = files.filter { it.key in selected }
  val driveOn = com.docready.aamunim.drive.DriveAuth.account.collectAsState().value.connected

  /** Files put in the Drive queue (records by id, students' files by their app path) */
  fun uploadAll(list: List<FolderFile>) {
    val ids = list.mapNotNull { it.record?.id }.toSet()
    val paths = list.mapNotNull { it.student?.second?.path }.toSet()
    scope.launch { withContext(Dispatchers.IO) { com.docready.aamunim.drive.DriveQueue.enqueue(context, ids, paths) } }
    Toast.makeText(context, "${list.size} file${if (list.size == 1) "" else "s"} queued for Google Drive", Toast.LENGTH_SHORT).show()
  }

  // files Android asks the user about before deleting (no longer the app's own)
  val askDelete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
    if (res.resultCode != android.app.Activity.RESULT_OK) {
      Toast.makeText(context, "Some files were left in Downloads.", Toast.LENGTH_LONG).show()
    }
  }

  /** Deleted from Downloads (blocking); the links Android asks the user about, and how many failed */
  fun deleteLinks(links: List<String>): Pair<List<String>, Int> {
    val ask = ArrayList<String>()
    var failed = 0
    for (l in links) {
      when (DownloadsFiles.deleteFile(context, l)) {
        DownloadsFiles.Deleted.NEEDS_USER -> ask.add(l)
        DownloadsFiles.Deleted.FAILED -> failed++
        else -> Unit
      }
    }
    return ask to failed
  }

  /** What deleting left: the system's question for some, a word for the rest */
  fun afterDelete(ask: List<String>, failed: Int) {
    val request = DownloadsFiles.deleteRequest(context, ask)
    val left = failed + if (request == null) ask.size else 0
    if (request != null) askDelete.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
    if (left > 0) Toast.makeText(context, "$left file${if (left == 1) "" else "s"} in Downloads couldn't be removed; delete ${if (left == 1) "it" else "them"} in the Files app.", Toast.LENGTH_LONG).show()
  }

  fun shareZip(list: List<FolderFile>, base: String) {
    if (list.isEmpty()) return
    busy = "Making the ZIP…"
    scope.launch {
      try {
        val made = withContext(Dispatchers.IO) { Zipper.zipStreams(context, list.map { f -> f.name to { f.open(context) } }, base) }
        if (made.skipped > 0) {
          Toast.makeText(context, "${made.skipped} file${if (made.skipped == 1) "" else "s"} couldn't be added (no longer on the phone).", Toast.LENGTH_LONG).show()
        }
        if (made.skipped < list.size) app.saveTargets.share.save(context, listOf(made.zip), title)
      } catch (e: Throwable) {
        Toast.makeText(context, e.message ?: "The ZIP could not be made.", Toast.LENGTH_LONG).show()
      } finally {
        busy = null
      }
    }
  }

  // Add files: picked (PDF / pictures), saved into this folder (Downloads / DocReady / its path)
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    val f = folder ?: return@rememberLauncherForActivityResult
    if (uris.isEmpty()) return@rememberLauncherForActivityResult
    busy = "Adding the files…"
    scope.launch {
      try {
        val outcome = withContext(Dispatchers.IO) {
          val ready = uris.map { u ->
            val name = FileNames.displayName(context, u) ?: "file"
            val mime = context.contentResolver.getType(u) ?: "application/octet-stream"
            val tmp = FileNames.copyIn(context, u, name.substringAfterLast('.', "bin"))
            val out = FileNames.outputFile(context, name.replace('/', '_'))
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
            ReadyFile(out, out.name, mime)
          }
          app.saveTargets.phone.saveTo(context, ready, tree.pathWhenRead(f.id))
        }
        if (outcome is SaveOutcome.Failed) Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
      } catch (e: Throwable) {
        Toast.makeText(context, e.message ?: "The files could not be added.", Toast.LENGTH_LONG).show()
      } finally {
        busy = null
      }
    }
  }

  // the tree still being read (app start): nothing shown from an empty tree
  val loaded by tree.loaded.collectAsState()
  if (!loaded) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
      IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) { Icon(AppIcons.Back, contentDescription = "Back") }
      com.docready.aamunim.ui.components.BusyRow("Reading your folders…")
    }
    return
  }

  Scaffold(
    bottomBar = {
      if (chosen.isNotEmpty()) {
        // select mode: Move · Delete · Share as ZIP
        Row(
          Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp, top = 10.dp)
            .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(22.dp)).padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text("${chosen.size} selected", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
          if (chosen.any { it.record != null }) {
            IconButton(onClick = { moving = true }, modifier = Modifier.size(44.dp).background(Color.White.copy(alpha = 0.1f), CircleShape)) {
              Icon(AppIcons.FolderNav, contentDescription = "Move", tint = MaterialTheme.colorScheme.surface)
            }
          }
          if (driveOn) {
            IconButton(onClick = {
              uploadAll(chosen)
              selected.clear()
            }, modifier = Modifier.size(44.dp).background(Color.White.copy(alpha = 0.1f), CircleShape)) {
              Icon(AppIcons.Cloud, contentDescription = "Upload to Drive", tint = MaterialTheme.colorScheme.surface)
            }
          }
          IconButton(onClick = { deletingFiles = true }, modifier = Modifier.size(44.dp).background(Color.White.copy(alpha = 0.1f), CircleShape)) {
            Icon(AppIcons.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.surface)
          }
          Button(
            onClick = { shareZip(chosen, title) },
            enabled = busy == null,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.inversePrimary, contentColor = Color(0xFF10331D)),
            modifier = Modifier.height(44.dp),
          ) { Text("Share as ZIP", fontWeight = FontWeight.Bold) }
        }
      } else if (files.isNotEmpty() || folder != null) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (folder != null) {
            OutlinedButton(onClick = {
              try {
                picker.launch(arrayOf("application/pdf", "image/*", "application/zip"))
              } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No file picker found on this phone.", Toast.LENGTH_LONG).show()
              }
            }, enabled = busy == null, modifier = Modifier.weight(1f).height(50.dp)) { Text("Add files") }
          }
          Button(onClick = { shareZip(files, title) }, enabled = busy == null && files.isNotEmpty(), modifier = Modifier.weight(1f).height(50.dp)) { Text("Share folder as ZIP", fontWeight = FontWeight.Bold) }
        }
      }
    },
  ) { padding ->
    LazyVerticalGrid(
      columns = GridCells.Fixed(3),
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.padding(padding).fillMaxSize(),
    ) {
      val full = GridItemSpan(3)
      item(span = { full }) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) { Icon(AppIcons.Back, contentDescription = "Back") }
          Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(crumb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          if (folder != null || student != null) {
            Box {
              IconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp)) { Icon(AppIcons.More, contentDescription = "More options") }
              DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (driveOn && files.isNotEmpty()) {
                  DropdownMenuItem(text = { Text("Upload to Drive") }, onClick = {
                    menu = false
                    uploadAll(files)
                  })
                }
                if (driveOn && (folder != null || student != null)) {
                  val auto = folder?.autoUpload ?: (key in data.autoUploadKeys)
                  DropdownMenuItem(text = { Text(if (auto) "Auto upload: on (turn off)" else "Auto upload new files") }, onClick = {
                    menu = false
                    scope.launch {
                      withContext(Dispatchers.IO) {
                        if (folder != null) tree.setAutoUpload(folder.id, !auto) else tree.setAutoUploadKey(key, !auto)
                      }
                    }
                  })
                }
                if (folder != null) {
                  DropdownMenuItem(text = { Text("New folder inside") }, onClick = { menu = false; newSub = true })
                  DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                  DropdownMenuItem(text = { Text(if (folder.pinned) "Unpin" else "Pin") }, onClick = {
                    menu = false
                    scope.launch { withContext(Dispatchers.IO) { tree.setPinned(folder.id, !folder.pinned) } }
                  })
                  DropdownMenuItem(text = { Text("Delete folder", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deletingFolder = true })
                }
                if (student != null) DropdownMenuItem(text = { Text("Open the student") }, onClick = { menu = false; onStudent(student.id) })
              }
            }
          }
        }
      }
      item(span = { full }) {
        val tooBig = files.count { fitState(it.bytes, it.maxKb) == FitState.TOO_BIG }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Counter("${files.size} files", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
          Counter(mb(sizeOf(files)), DocReadyColors.extra.chip, DocReadyColors.extra.text2)
          if (tooBig > 0) Counter("$tooBig too big", DocReadyColors.extra.warningContainer, DocReadyColors.extra.onWarningContainer)
        }
      }
      busy?.let { b -> item(span = { full }) { com.docready.aamunim.ui.components.BusyRow(b) } }
      if (subs.isNotEmpty()) {
        item(span = { full }) { Text("Sub-folders", style = SectionLabelStyle, modifier = Modifier.padding(top = 6.dp)) }
        for ((id, name, sub) in subs) item(span = { GridItemSpan(if (subs.size == 1) 3 else 1) }, key = "sub$id") {
          AppCard(Modifier.fillMaxWidth(), onClick = { onOpen(id) }) {
            Column(Modifier.padding(10.dp)) {
              Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Icon(if (key == "students") AppIcons.Person else AppIcons.FolderNav, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
              }
              Text(name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
              Text(sub, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
      if (files.isNotEmpty()) {
        item(span = { full }) {
          Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Files", style = SectionLabelStyle, modifier = Modifier.weight(1f))
            Text(if (chosen.isEmpty()) "Long-press to select" else "Tap to add or remove", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        for (f in files) item(key = f.key) {
          val on = f.key in selected
          Column(
            Modifier
              .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
              .then(if (on) Modifier.background(Color.Transparent) else Modifier)
              .combinedClickable(
                onClick = {
                  if (selected.isNotEmpty()) {
                    if (!selected.remove(f.key)) selected.add(f.key)
                  } else {
                    openFile(context, scope, f)
                  }
                },
                onLongClick = { if (!selected.remove(f.key)) selected.add(f.key) },
              )
              .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.8f).background(MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
              FileKindTile(f.name, 44)
              if (on) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                  Icon(AppIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                }
              }
              Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { DriveMark(f.drive) }
            }
            Text(f.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(bytesText(f.bytes), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
              val st = fitState(f.bytes, f.maxKb)
              fitLabel(st)?.let { StatusBadge(it, st) }
            }
          }
        }
      }
      if (files.isEmpty() && subs.isEmpty()) {
        item(span = { full }) { Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
      }
    }
  }

  if (renaming && folder != null) {
    NameDialog("Rename folder", folder.name) { name ->
      renaming = false
      if (name != null) scope.launch { withContext(Dispatchers.IO) { tree.rename(folder.id, name) } }
    }
  }
  if (newSub && folder != null) {
    NameDialog("New folder in ${folder.name}", "") { name ->
      newSub = false
      if (name != null) scope.launch { withContext(Dispatchers.IO) { tree.create(folder.id, name) } }
    }
  }
  if (moving) {
    FolderPickerDialog(
      data.folders,
      folder?.id,
      title = "Move to",
      onPick = { target ->
        moving = false
        val ids = chosen.mapNotNull { it.record?.id }.toSet()
        selected.clear()
        scope.launch { withContext(Dispatchers.IO) { tree.moveFiles(ids, target) } }
      },
      onNew = { parent, name -> scope.launch { withContext(Dispatchers.IO) { tree.create(parent, name) } } },
      onDismiss = { moving = false },
    )
  }
  if (deletingFiles) {
    var alsoDownloads by remember { mutableStateOf(false) }
    AlertDialog(
      onDismissRequest = { deletingFiles = false },
      title = { Text("Delete ${chosen.size} file${if (chosen.size == 1) "" else "s"}?") },
      text = {
        Column {
          Text("They leave this folder.")
          Row(Modifier.fillMaxWidth().clickable { alsoDownloads = !alsoDownloads }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = alsoDownloads, onCheckedChange = { alsoDownloads = it })
            Text("Also delete them from Downloads / DocReady")
          }
        }
      },
      confirmButton = {
        TextButton(onClick = {
          deletingFiles = false
          val list = chosen.toList()
          selected.clear()
          scope.launch {
            val (ask, failed) = withContext(Dispatchers.IO) {
              val links = if (alsoDownloads) list.mapNotNull { it.record?.uri ?: it.student?.second?.downloadsUri } else emptyList()
              val r = deleteLinks(links)
              for (f in list) f.student?.let { (sid, sf) -> app.students.removeFile(sid, sf) }
              tree.removeFiles(list.mapNotNull { it.record?.id }.toSet())
              r
            }
            afterDelete(ask, failed)
          }
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { deletingFiles = false }) { Text("Cancel") } },
    )
  }
  if (deletingFolder && folder != null) {
    var deleteFiles by remember { mutableStateOf(false) }
    AlertDialog(
      onDismissRequest = { deletingFolder = false },
      title = { Text("Delete “${folder.name}”?") },
      text = {
        Column {
          Text("The folder and its sub-folders go. Their files:")
          Row(Modifier.fillMaxWidth().clickable { deleteFiles = false }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected = !deleteFiles, onClick = { deleteFiles = false })
            Text("Keep them (in Scans)")
          }
          Row(Modifier.fillMaxWidth().clickable { deleteFiles = true }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected = deleteFiles, onClick = { deleteFiles = true })
            Text("Delete them from Downloads / DocReady too")
          }
        }
      },
      confirmButton = {
        TextButton(onClick = {
          deletingFolder = false
          val del = deleteFiles
          scope.launch {
            val (ask, failed) = withContext(Dispatchers.IO) {
              val removed = tree.deleteFolder(folder.id, keepFiles = !del)
              deleteLinks(removed.map { it.uri })
            }
            afterDelete(ask, failed)
            onBack()
          }
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { deletingFolder = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun Counter(text: String, bg: Color, fg: Color) {
  Box(Modifier.height(32.dp).background(bg, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
    Text(text, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
  }
}
