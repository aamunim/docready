package com.docready.aamunim.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.NameContext
import com.docready.aamunim.data.Naming
import com.docready.aamunim.data.StudentRepository
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.ItemState
import com.docready.aamunim.data.model.Preset
import com.docready.aamunim.data.model.Student
import com.docready.aamunim.data.model.StudentFile
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.data.model.fileFor
import com.docready.aamunim.data.model.stateOf
import com.docready.aamunim.data.model.summary
import com.docready.aamunim.data.model.title
import com.docready.aamunim.docReady
import com.docready.aamunim.save.ReadyFile
import com.docready.aamunim.save.SaveOutcome
import com.docready.aamunim.save.ShareSaveTarget
import com.docready.aamunim.save.Zipper
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.BottomActions
import com.docready.aamunim.ui.components.BusyRow
import com.docready.aamunim.ui.components.ChoiceChips
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.components.VSpace
import com.docready.aamunim.ui.theme.DocReadyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A student's progress on their preset: "3 of 5 ready" (required items) */
private fun progress(s: Student, preset: Preset?): String? {
  val p = preset ?: return null
  val required = p.items.filter { it.required }
  val done = required.count { s.stateOf(it) == ItemState.DONE }
  return "$done of ${required.size} ready"
}

private enum class StudentSort(val title: String) { RECENT("Recent"), NAME("Name") }

@Composable
fun StudentsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
  val app = LocalContext.current.docReady
  val students by app.students.students.collectAsState()
  val presets by app.presets.presets.collectAsState()
  var query by rememberSaveable { mutableStateOf("") }
  var sort by rememberSaveable { mutableStateOf(StudentSort.RECENT) }
  val shown = students
    .filter { s ->
      query.isBlank() || s.name.contains(query.trim(), ignoreCase = true) || (s.roll ?: "").contains(query.trim(), ignoreCase = true)
    }
    .let { list -> if (sort == StudentSort.NAME) list.sortedBy { it.name.lowercase() } else list }
  Scaffold(topBar = { DocTopBar("Students", onBack, actions = { TextButton(onClick = onNew) { Text("+ New") } }) }) { padding ->
    LazyColumn(
      Modifier.padding(padding).fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (students.isEmpty()) {
        item {
          Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No students yet", style = MaterialTheme.typography.titleLarge)
            Text(
              "A student keeps one applicant's papers together, with a checklist from their form's preset.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = 8.dp),
            )
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Add a student") }
          }
        }
      } else {
        item {
          OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            placeholder = { Text("Search by name or roll") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
        item { ChoiceChips(StudentSort.entries.toList(), sort, { it.title }, { sort = it }) }
        items(shown, key = { it.id }) { s ->
          val preset = presets.firstOrNull { it.id == s.presetId }
          Surface(
            onClick = { onOpen(s.id) },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(Modifier.padding(14.dp)) {
              Text(s.name, fontWeight = FontWeight.Bold)
              val line = listOfNotNull(s.roll?.let { "Roll $it" }, preset?.name, progress(s, preset), "${s.files.size} file${if (s.files.size == 1) "" else "s"}")
              Text(line.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
        if (shown.isEmpty()) item { Text("No student matches.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
    }
  }
}

/** New student (id "new") or editing one; [onSaved] gets the student's id */
@Composable
fun StudentEditScreen(id: String, onBack: () -> Unit, onSaved: (String) -> Unit) {
  val app = LocalContext.current.docReady
  val existing = remember(id) { app.students.get(id) }
  val presets by app.presets.presets.collectAsState()
  var name by rememberSaveable(id) { mutableStateOf(existing?.name ?: "") }
  var roll by rememberSaveable(id) { mutableStateOf(existing?.roll ?: "") }
  var dob by rememberSaveable(id) { mutableStateOf(existing?.dob ?: "") }
  var registration by rememberSaveable(id) { mutableStateOf(existing?.registration ?: "") }
  var notes by rememberSaveable(id) { mutableStateOf(existing?.notes ?: "") }
  var presetId by rememberSaveable(id) { mutableStateOf(existing?.presetId) }
  var error by remember { mutableStateOf<String?>(null) }

  fun save() {
    if (name.isBlank()) {
      error = "Write the student's name."
      return
    }
    val base = existing ?: Student(StudentRepository.newId(), name.trim())
    val s = app.students.save(
      base.copy(
        name = name.trim().take(80),
        roll = roll.trim().take(40).ifEmpty { null },
        dob = dob.trim().take(20).ifEmpty { null },
        registration = registration.trim().take(40).ifEmpty { null },
        notes = notes.trim().take(500).ifEmpty { null },
        presetId = presetId,
      ),
    )
    onSaved(s.id)
  }

  Scaffold(
    topBar = {
      DocTopBar(if (existing == null) "New student" else "Edit student", onBack, actions = { TextButton(onClick = { save() }) { Text("Save") } })
    },
  ) { padding ->
    Column(
      Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name (any script)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(roll, { roll = it.take(40) }, label = { Text("Roll (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(registration, { registration = it.take(40) }, label = { Text("Registration no. (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(dob, { dob = it.take(20) }, label = { Text("Date of birth (optional)") }, placeholder = { Text("dd-mm-yyyy") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(notes, { notes = it.take(500) }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp))
      SectionLabel("Form preset (the checklist)")
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = presetId == null, onClick = { presetId = null }, label = { Text("None") })
        for (p in presets) FilterChip(selected = presetId == p.id, onClick = { presetId = p.id }, label = { Text(p.name) })
      }
      ErrorText(error)
      Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save student") }
    }
  }
}

/**
 * A student's page: the preset's items as a checklist (done / missing / too big), each opening
 * the right tool for it; the student's files; all as a ZIP; edit and delete.
 */
@Composable
fun StudentScreen(
  id: String,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  /** Make item [index] of [presetId] with [tool] ("photo", "signature", "scan" or "pdf") */
  onMake: (tool: String, presetId: String, index: Int) -> Unit,
) {
  val context = LocalContext.current
  val app = context.docReady
  val students by app.students.students.collectAsState()
  val presets by app.presets.presets.collectAsState()
  val s = students.firstOrNull { it.id == id }
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf<String?>(null) }
  var confirmDelete by remember { mutableStateOf(false) }
  var alsoFiles by remember { mutableStateOf(false) }

  if (s == null) {
    Scaffold(topBar = { DocTopBar("Student", onBack) }) { padding ->
      Text("This student is no longer here.", modifier = Modifier.padding(padding).padding(16.dp))
    }
    return
  }
  val preset = presets.firstOrNull { it.id == s.presetId }

  /** All the student's files as one ZIP, then shared or saved */
  fun zipAll(save: Boolean) {
    busy = "Making the ZIP…"
    scope.launch {
      try {
        val outcome = withContext(Dispatchers.IO) {
          val copies = app.students.exportCopies(s)
          if (copies.isEmpty()) throw IllegalStateException("This student has no files yet.")
          val naming = NameContext(preset = preset?.name, presetPattern = preset?.namePattern, student = s.name, roll = s.roll)
          val zip = Zipper.zip(context, copies, Naming.apply(app.settings.naming.value, naming, s.name, 1))
          copies.forEach { it.file.delete() }
          if (save) app.saveTargets.phone.save(context, listOf(zip), s.name) else null to zip
        }
        when {
          outcome is SaveOutcome.Saved -> Toast.makeText(context, "Saved ${outcome.names.firstOrNull()} in ${outcome.where}", Toast.LENGTH_LONG).show()
          outcome is SaveOutcome.Failed -> Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
          outcome is Pair<*, *> -> {
            val zip = outcome.second as ReadyFile
            app.saveTargets.share.save(context, listOf(zip), s.name)
          }
        }
      } catch (e: Throwable) {
        Toast.makeText(context, e.message ?: "The ZIP could not be made.", Toast.LENGTH_LONG).show()
      } finally {
        busy = null
      }
    }
  }

  Scaffold(
    topBar = {
      DocTopBar(
        s.name,
        onBack,
        subtitle = listOfNotNull(s.roll?.let { "Roll $it" }, s.registration?.let { "Reg $it" }, s.dob?.let { "Born $it" }).joinToString(" · ").ifEmpty { null },
        actions = {
          IconButton(onClick = onEdit) { Icon(AppIcons.Edit, contentDescription = "Edit student") }
          IconButton(onClick = { confirmDelete = true }) { Icon(AppIcons.Delete, contentDescription = "Delete student") }
        },
      )
    },
    bottomBar = {
      if (s.files.isNotEmpty()) {
        BottomActions {
          OutlinedButton(onClick = { zipAll(save = false) }, enabled = busy == null, modifier = Modifier.height(52.dp)) { Text("Share all as ZIP") }
          Button(onClick = { zipAll(save = true) }, enabled = busy == null, modifier = Modifier.weight(1f).height(52.dp)) { Text("Save as ZIP") }
        }
      }
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
      s.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      busy?.let { BusyRow(it) }
      if (preset == null) {
        SectionLabel("Checklist")
        Text(
          if (presets.isEmpty()) "Make a preset for the form first (Presets), then link it here." else "Link the form's preset to see what is still missing:",
          style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (p in presets) FilterChip(selected = false, onClick = { app.students.save(s.copy(presetId = p.id)) }, label = { Text(p.name) })
        }
      } else {
        SectionLabel("${preset.name} · ${progress(s, preset)}")
        preset.items.forEachIndexed { i, item -> ChecklistRow(s, i, item, onMake = { tool -> onMake(tool, preset.id, i) }) }
      }
      SectionLabel("Files (${s.files.size})")
      if (s.files.isEmpty()) {
        Text("Files made for this student are kept here and in Downloads / DocReady / ${s.name}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      for (f in s.files.sortedByDescending { it.savedAt }) {
        StudentFileRow(f, onRemove = { app.students.removeFile(s.id, f) })
      }
    }
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("Delete “${s.name}”?") },
      text = {
        Column {
          Text("The student and the app's copies of their files are removed.")
          Row(Modifier.fillMaxWidth().clickable { alsoFiles = !alsoFiles }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = alsoFiles, onCheckedChange = { alsoFiles = it })
            Text("Also delete their files in Downloads / DocReady")
          }
        }
      },
      confirmButton = {
        TextButton(onClick = {
          confirmDelete = false
          scope.launch {
            val left = app.students.delete(s.id, alsoFiles)
            if (left > 0) {
              android.widget.Toast.makeText(
                context,
                "$left file${if (left == 1) "" else "s"} in Downloads couldn't be removed; delete ${if (left == 1) "it" else "them"} in the Files app.",
                android.widget.Toast.LENGTH_LONG,
              ).show()
            }
            onBack()
          }
        }) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun ChecklistRow(s: Student, index: Int, item: com.docready.aamunim.data.model.PresetItem, onMake: (String) -> Unit) {
  val extra = DocReadyColors.extra
  val state = s.stateOf(item)
  val f = s.fileFor(item)
  val (color, word) = when (state) {
    ItemState.DONE -> extra.success to "Done"
    ItemState.TOO_BIG -> MaterialTheme.colorScheme.error to "Too big"
    ItemState.MISSING -> (if (item.required) extra.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant) to (if (item.required) "Missing" else "Optional")
  }
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
  ) {
    Column(Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(item.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(word, color = color, style = MaterialTheme.typography.labelLarge)
      }
      Text(item.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      f?.let { Text("${it.name} · ${bytesText(it.bytes)}", style = MaterialTheme.typography.bodySmall, color = color) }
      Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val again = if (state == ItemState.MISSING) "" else " again"
        when (item.kind) {
          ItemKind.PHOTO -> Button(onClick = { onMake("photo") }) { Text("Make photo$again") }
          ItemKind.SIGNATURE -> Button(onClick = { onMake("signature") }) { Text("Make signature$again") }
          ItemKind.DOCUMENT -> {
            Button(onClick = { onMake("scan") }) { Text("Scan$again") }
            OutlinedButton(onClick = { onMake("pdf") }) { Text("From PDF / photos") }
          }
        }
        // any files (scan, gallery, PDFs): pages picked if more than the item takes
        OutlinedButton(onClick = { onMake("item-add") }) { Text("Add") }
      }
    }
  }
}

@Composable
private fun StudentFileRow(f: StudentFile, onRemove: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  /** A copy in the share folder handed out (the student's own storage never is), made off the main thread */
  fun withShared(use: (Uri) -> Unit) {
    scope.launch {
      try {
        val uri = withContext(Dispatchers.IO) {
          val out = FileNames.outputFile(context, f.name)
          File(f.path).copyTo(out, overwrite = true)
          FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
        }
        use(uri)
      } catch (_: Exception) {
        Toast.makeText(context, "This file can't be opened or shared.", Toast.LENGTH_LONG).show()
      }
    }
  }
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        listOfNotNull(f.itemTitle, bytesText(f.bytes)).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = { withShared { ShareSaveTarget.open(context, it, f.mime) } }) { Icon(AppIcons.Open, contentDescription = "Open ${f.name}") }
    IconButton(onClick = { withShared { ShareSaveTarget.shareUris(context, listOf(it), f.mime) } }) { Icon(AppIcons.Share, contentDescription = "Share ${f.name}") }
    IconButton(onClick = onRemove) { Icon(AppIcons.Close, contentDescription = "Remove ${f.name}") }
  }
}

/**
 * "Add to <student>": the ready files attached to the student (app storage + Downloads/DocReady/<name>),
 * then [onDone] (back to the student's page).
 */
@Composable
fun AddToStudentButton(studentId: String, files: List<ReadyFile>?, preset: Preset?, modifier: Modifier = Modifier, onDone: () -> Unit) {
  val context = LocalContext.current
  val app = context.docReady
  val student = remember(studentId) { app.students.get(studentId) }
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  Button(
    onClick = {
      val f = files ?: return@Button
      busy = true
      scope.launch {
        val r = try {
          app.students.attach(studentId, f, preset)
        } catch (e: Throwable) {
          null.also { Toast.makeText(context, "Could not add the files: ${e.message ?: "the file is no longer there"}. Make it again.", Toast.LENGTH_LONG).show() }
        }
        busy = false
        if (r == null) return@launch
        when (r) {
          is SaveOutcome.Failed -> Toast.makeText(context, "Kept for ${student?.name ?: "the student"}; Downloads: ${r.message}", Toast.LENGTH_LONG).show()
          else -> Toast.makeText(context, "Added to ${student?.name ?: "the student"}", Toast.LENGTH_SHORT).show()
        }
        onDone()
      }
    },
    enabled = !busy && files != null && student != null,
    modifier = modifier.height(52.dp),
  ) { Text(if (busy) "Adding…" else "Add to ${student?.name?.substringBefore(' ') ?: "student"}") }
}

/** Pick a student (or make one quickly) for files made elsewhere (Split by preset) */
@Composable
fun StudentPickerDialog(onPick: (Student) -> Unit, onDismiss: () -> Unit) {
  val app = LocalContext.current.docReady
  val students by app.students.students.collectAsState()
  var query by remember { mutableStateOf("") }
  var newName by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Save to a student") },
    text = {
      Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        if (students.isNotEmpty()) {
          OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          for (s in students.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }.take(30)) {
            TextButton(onClick = { onPick(s) }, modifier = Modifier.fillMaxWidth()) {
              Text(listOfNotNull(s.name, s.roll).joinToString(" · "), modifier = Modifier.fillMaxWidth())
            }
          }
          VSpace(8)
        }
        Text("Or a new student:", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(newName, { newName = it.take(80) }, placeholder = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onPick(app.students.save(Student(StudentRepository.newId(), newName.trim()))) },
        enabled = newName.isNotBlank(),
      ) { Text("Add new") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
