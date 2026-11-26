package com.docready.aamunim.ui.screens

import com.docready.aamunim.ui.components.AppCard
import com.docready.aamunim.ui.components.CircleIconButton
import com.docready.aamunim.ui.components.FitState
import com.docready.aamunim.ui.components.SlimProgress
import com.docready.aamunim.ui.components.StatusBadge
import com.docready.aamunim.ui.components.ProgressRing
import com.docready.aamunim.ui.components.dashedBorder
import androidx.compose.ui.unit.sp
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
fun StudentsScreen(onBack: (() -> Unit)?, onOpen: (String) -> Unit, onNew: () -> Unit) {
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
  Scaffold(
    topBar = { if (onBack != null) DocTopBar("Students", onBack) },
    floatingActionButton = {
      if (students.isNotEmpty()) {
        androidx.compose.material3.ExtendedFloatingActionButton(
          onClick = onNew,
          shape = RoundedCornerShape(18.dp),
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          icon = { Icon(AppIcons.Add, contentDescription = null) },
          text = { Text("Add student", fontWeight = FontWeight.Bold) },
        )
      }
    },
  ) { padding ->
    LazyColumn(
      Modifier.padding(padding).fillMaxSize(),
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Students", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
          if (students.isNotEmpty()) {
            CircleIconButton(
              AppIcons.Sort,
              "Sort: ${sort.title}. Tap to sort by ${if (sort == StudentSort.NAME) "recent" else "name"}",
              onClick = { sort = if (sort == StudentSort.NAME) StudentSort.RECENT else StudentSort.NAME },
            )
          }
        }
      }
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
            placeholder = { Text("Name, roll or registration") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
          )
        }
        item {
          // the counters: everyone, all ready, missing papers
          val withPreset = students.mapNotNull { st -> presets.firstOrNull { it.id == st.presetId }?.let { st to it } }
          val ready = withPreset.count { (st, p) -> doneOf(st, p).let { it.first == it.second } }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Counter("${students.size}", "Students", CounterTone.PLAIN, Modifier.weight(1f))
            Counter("$ready", "All ready", CounterTone.GREEN, Modifier.weight(1f))
            Counter("${withPreset.size - ready}", "Missing papers", CounterTone.AMBER, Modifier.weight(1f))
          }
        }
        items(shown, key = { it.id }) { s ->
          val preset = presets.firstOrNull { it.id == s.presetId }
          StudentRow(s, preset) { onOpen(s.id) }
        }
        if (shown.isEmpty()) item { Text("No student matches.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
    }
  }
}

/** (done, of how many) required items of [p] for [s] */
private fun doneOf(s: Student, p: Preset): Pair<Int, Int> {
  val required = p.items.filter { it.required }
  return required.count { s.stateOf(it) == ItemState.DONE } to required.size
}

private enum class CounterTone { PLAIN, GREEN, AMBER }

@Composable
private fun Counter(value: String, label: String, tone: CounterTone, modifier: Modifier) {
  val extra = DocReadyColors.extra
  val (bg, fg) = when (tone) {
    CounterTone.PLAIN -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
    CounterTone.GREEN -> extra.successContainer to extra.onSuccessContainer
    CounterTone.AMBER -> extra.warningContainer to extra.onWarningContainer
  }
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = bg,
    border = if (tone == CounterTone.PLAIN) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
    modifier = modifier,
  ) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
      Text(value, fontFamily = com.docready.aamunim.ui.theme.Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = fg)
      Text(label, fontSize = 11.5.sp, color = if (tone == CounterTone.PLAIN) MaterialTheme.colorScheme.onSurfaceVariant else fg, maxLines = 1)
    }
  }
}

/** A student: initials, name, roll and preset, a progress bar, and Ready / 3/5 / Set preset */
@Composable
private fun StudentRow(s: Student, preset: Preset?, onClick: () -> Unit) {
  val extra = DocReadyColors.extra
  val (done, total) = preset?.let { doneOf(s, it) } ?: (0 to 0)
  val all = preset != null && done == total
  AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier.size(44.dp).background(if (all) extra.successContainer else extra.chip, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          initials(s.name),
          fontFamily = com.docready.aamunim.ui.theme.Manrope,
          fontWeight = FontWeight.Bold,
          fontSize = 15.sp,
          color = if (all) extra.onSuccessContainer else extra.text2,
        )
      }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(s.name, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(s.roll?.let { "Roll $it" } ?: s.registration?.let { "Reg $it" }, preset?.name ?: "No preset yet").joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SlimProgress(
          if (total == 0) 0f else done.toFloat() / total,
          Modifier.padding(top = 3.dp),
          if (all) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        )
      }
      Spacer(Modifier.width(10.dp))
      when {
        preset == null -> StatusBadge("Set preset", FitState.NONE)
        all -> StatusBadge("Ready", FitState.FITS)
        else -> StatusBadge("$done/$total", FitState.CLOSE)
      }
    }
  }
}

/** "SA" from "Student A" (any script: the first letters) */
private fun initials(name: String): String =
  name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

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
  /** The student's folder (Downloads / DocReady / <name>) */
  onOpenFolder: (String) -> Unit = {},
) {
  val context = LocalContext.current
  val app = context.docReady
  val students by app.students.students.collectAsState()
  val allFacts by app.students.facts.collectAsState()
  var menu by remember { mutableStateOf(false) }
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

  /** A too-big file brought under its item's limit (the tools' fitting), then kept for the student */
  fun fixToFit(item: com.docready.aamunim.data.model.PresetItem, index: Int) {
    val f = s.fileFor(item) ?: return
    val p = preset ?: return
    busy = "Making it fit…"
    scope.launch {
      try {
        val r = withContext(Dispatchers.Default) {
          val out = FileNames.outputFile(context, f.name)
          File(f.path).copyTo(out, overwrite = true)
          val fixed = com.docready.aamunim.imaging.SizeFixer.fit(context, ReadyFile(out, f.name, f.mime, item.maxKb, exactSize = item.kind != ItemKind.DOCUMENT, itemIndex = index))
          if (item.maxKb != null && fixed.file.length() > item.maxKb * 1024L) throw IllegalStateException("It can't get under ${item.maxKb} KB, even at the lowest readable quality.")
          fixed
        }
        app.students.attach(s.id, listOf(r), p)
        Toast.makeText(context, "${item.title} fits now.", Toast.LENGTH_SHORT).show()
      } catch (e: Throwable) {
        Toast.makeText(context, e.message ?: "It could not be made smaller.", Toast.LENGTH_LONG).show()
      } finally {
        busy = null
      }
    }
  }

  Scaffold(
    bottomBar = {
      BottomActions {
        OutlinedButton(onClick = { onOpenFolder(s.name) }, modifier = Modifier.weight(1f).height(50.dp)) {
          Icon(AppIcons.FolderNav, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Open folder")
        }
        Button(onClick = { zipAll(save = false) }, enabled = busy == null && s.files.isNotEmpty(), modifier = Modifier.weight(1f).height(50.dp)) {
          Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Share all as ZIP")
        }
      }
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
      // back · edit · more
      Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) { Icon(AppIcons.Back, contentDescription = "Back to Students") }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onEdit, modifier = Modifier.size(44.dp)) { Icon(AppIcons.Edit, contentDescription = "Edit student") }
        Box {
          IconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp)) { Icon(AppIcons.More, contentDescription = "More options") }
          androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("Save all as ZIP") }, enabled = s.files.isNotEmpty(), onClick = {
              menu = false
              zipAll(save = true)
            })
            androidx.compose.material3.DropdownMenuItem(text = { Text("Delete student", color = MaterialTheme.colorScheme.error) }, onClick = {
              menu = false
              confirmDelete = true
            })
          }
        }
      }
      // the ring, the name, roll / reg, the preset
      val (done, total) = preset?.let { doneOf(s, it) } ?: (0 to 0)
      Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ProgressRing(if (total == 0) 0f else done.toFloat() / total, if (preset == null) "–" else "$done/$total")
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(s.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
          listOfNotNull(s.roll?.let { "Roll $it" }, s.registration?.let { "Reg $it" }, s.dob?.let { "Born $it" }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Text("Preset: ${preset?.name ?: "none yet"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      Column(Modifier.padding(horizontal = 20.dp)) {
        // what a page said about them (from OCR): offered, never filled in without a tap
        val fact = allFacts[s.id]?.firstOrNull { f -> !hasFact(s, f) }
        if (fact != null) {
          Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
              .dashedBorder(MaterialTheme.colorScheme.outline, 16.dp).padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(AppIcons.Spark, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Found on a page: ${fact.kind.title} ${fact.value}", style = MaterialTheme.typography.bodySmall, color = DocReadyColors.extra.text2, modifier = Modifier.weight(1f))
            TextButton(onClick = { app.students.dropFact(s.id, fact) }) { Text("No") }
            Button(
              onClick = {
                app.students.save(withFact(s, fact))
                app.students.dropFact(s.id, fact)
              },
              colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) { Text("Use", fontWeight = FontWeight.Bold) }
          }
        }
        s.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp)) }
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
          Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Checklist", Modifier.weight(1f))
            Text("Tap to add or fix", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          preset.items.forEachIndexed { i, item ->
            ChecklistRow(s, item, onAdd = { onMake("item-add", preset.id, i) }, onFix = { fixToFit(item, i) }, enabled = busy == null)
          }
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

/** The student already has this value */
private fun hasFact(s: Student, f: com.docready.aamunim.imaging.Fact): Boolean = when (f.kind) {
  com.docready.aamunim.imaging.FactKind.NAME -> s.name.equals(f.value, ignoreCase = true)
  com.docready.aamunim.imaging.FactKind.ROLL -> s.roll == f.value
  com.docready.aamunim.imaging.FactKind.REG -> s.registration == f.value
  com.docready.aamunim.imaging.FactKind.DOB -> s.dob == f.value
}

private fun withFact(s: Student, f: com.docready.aamunim.imaging.Fact): Student = when (f.kind) {
  com.docready.aamunim.imaging.FactKind.NAME -> s.copy(name = f.value.take(80))
  com.docready.aamunim.imaging.FactKind.ROLL -> s.copy(roll = f.value.take(40))
  com.docready.aamunim.imaging.FactKind.REG -> s.copy(registration = f.value.take(40))
  com.docready.aamunim.imaging.FactKind.DOB -> s.copy(dob = f.value.take(20))
}

/**
 * One item of the checklist: ✓ done, ! too big (Fix to fit), + missing (Add). Tapping the row
 * opens Add for the item (any files; pages picked when more than it takes).
 */
@Composable
private fun ChecklistRow(s: Student, item: com.docready.aamunim.data.model.PresetItem, onAdd: () -> Unit, onFix: () -> Unit, enabled: Boolean) {
  val extra = DocReadyColors.extra
  val state = s.stateOf(item)
  val f = s.fileFor(item)
  val (mark, markBg, markFg) = when (state) {
    ItemState.DONE -> Triple("✓", extra.successContainer, extra.onSuccessContainer)
    ItemState.TOO_BIG -> Triple("!", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
    ItemState.MISSING -> Triple("+", extra.warningContainer, extra.onWarningContainer)
  }
  val rule = when {
    state == ItemState.TOO_BIG && f != null -> item.summary() + " · now " + bytesText(f.bytes)
    else -> item.summary()
  }
  val border = when (state) {
    ItemState.TOO_BIG -> BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
    ItemState.MISSING -> BorderStroke(0.dp, androidx.compose.ui.graphics.Color.Transparent)
    else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
  }
  Surface(
    onClick = onAdd,
    enabled = enabled,
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface,
    border = border,
    modifier = Modifier
      .fillMaxWidth()
      .padding(bottom = 10.dp)
      .then(if (state == ItemState.MISSING) Modifier.dashedBorder(extra.warningBorder, 18.dp) else Modifier),
  ) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(36.dp).background(markBg, CircleShape), contentAlignment = Alignment.Center) {
        Text(mark, color = markFg, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
      }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(item.title + if (!item.required) " (optional)" else "", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
        Text(rule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        f?.let { Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
      }
      Spacer(Modifier.width(8.dp))
      when (state) {
        ItemState.DONE -> Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = extra.onSuccessContainer)
        ItemState.TOO_BIG -> Button(
          onClick = onFix,
          enabled = enabled,
          colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
          contentPadding = PaddingValues(horizontal = 12.dp),
          modifier = Modifier.height(44.dp),
        ) { Text("Fix to fit", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        ItemState.MISSING -> Button(
          onClick = onAdd,
          enabled = enabled,
          contentPadding = PaddingValues(horizontal = 14.dp),
          modifier = Modifier.height(44.dp),
        ) { Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
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
