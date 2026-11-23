package com.docready.aamunim.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docready.aamunim.data.PresetRepository
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.OutputFormat
import com.docready.aamunim.data.model.PhotoBackground
import com.docready.aamunim.data.model.Preset
import com.docready.aamunim.data.model.PresetItem
import com.docready.aamunim.data.model.label
import com.docready.aamunim.data.model.summary
import com.docready.aamunim.docReady
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.Badge
import com.docready.aamunim.ui.components.ChoiceChips
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.NumberField
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.components.VSpace

private const val OFFICIAL_NOTE =
  "Built-in sizes are common ones: always check them against each form's official instructions. " +
    "You can copy a preset and change it."

private enum class PresetFilter(val title: String) { ALL("All"), BUILT_IN("Built-in"), MINE("Mine") }

@Composable
fun PresetsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
  val repo = LocalContext.current.docReady.presets
  val presets by repo.presets.collectAsState()
  var query by rememberSaveable { mutableStateOf("") }
  var filter by rememberSaveable { mutableStateOf(PresetFilter.ALL) }
  val shown = presets.filter {
    (filter == PresetFilter.ALL || (filter == PresetFilter.BUILT_IN) == it.builtIn) &&
      (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true))
  }
  Scaffold(
    topBar = {
      DocTopBar("Presets", onBack, actions = { TextButton(onClick = onNew) { Text("+ New") } })
    },
  ) { padding ->
    LazyColumn(
      Modifier.padding(padding).fillMaxSize(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
          placeholder = { Text("Search presets") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      item { ChoiceChips(PresetFilter.entries.toList(), filter, { it.title }, { filter = it }) }
      items(shown, key = { it.id }) { p -> PresetCard(p) { onOpen(p.id) } }
      if (shown.isEmpty()) {
        item {
          Text(
            if (filter == PresetFilter.MINE && query.isBlank()) "No presets of your own yet. Tap “+ New”, or copy a built-in one." else "No preset matches.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      item {
        Text(OFFICIAL_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun PresetCard(p: Preset, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(p.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Badge(if (p.builtIn) "Built-in" else "Mine")
      }
      for (item in p.items) {
        Text(item.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
      }
    }
  }
}

/** A preset's files, each with a button to make it; copy / edit / delete */
@Composable
fun PresetDetailScreen(
  id: String,
  onBack: () -> Unit,
  onMake: (Preset, Int) -> Unit,
  onEdit: (String) -> Unit,
) {
  val repo = LocalContext.current.docReady.presets
  val presets by repo.presets.collectAsState()
  val preset = presets.firstOrNull { it.id == id }
  var confirmDelete by remember { mutableStateOf(false) }
  Scaffold(
    topBar = {
      DocTopBar(
        preset?.name ?: "Preset",
        onBack,
        subtitle = if (preset?.builtIn == true) "Built-in (read-only)" else "Your preset",
        actions = {
          if (preset != null) {
            IconButton(onClick = { repo.copy(preset.id)?.let { onEdit(it.id) } }) { Icon(AppIcons.Copy, contentDescription = "Copy and edit") }
            if (!preset.builtIn) {
              IconButton(onClick = { onEdit(preset.id) }) { Icon(AppIcons.Edit, contentDescription = "Edit") }
              IconButton(onClick = { confirmDelete = true }) { Icon(AppIcons.Delete, contentDescription = "Delete") }
            }
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (preset == null) {
        Text("This preset is no longer here.")
      } else {
        preset.items.forEachIndexed { i, item ->
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(
                when (item.kind) {
                  ItemKind.PHOTO -> AppIcons.Person
                  ItemKind.SIGNATURE -> AppIcons.Signature
                  ItemKind.DOCUMENT -> AppIcons.Scan
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Spacer(Modifier.width(12.dp))
              Column(Modifier.weight(1f)) {
                Text(item.kind.label, fontWeight = FontWeight.SemiBold)
                Text(item.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Button(onClick = { onMake(preset, i) }) { Text("Make") }
            }
          }
        }
        if (preset.builtIn) {
          OutlinedButton(onClick = { repo.copy(preset.id)?.let { onEdit(it.id) } }, modifier = Modifier.fillMaxWidth()) {
            Text("Copy and change it")
          }
        }
        Text(OFFICIAL_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
  if (confirmDelete && preset != null) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("Delete “${preset.name}”?") },
      text = { Text("Files you already made stay where they are.") },
      confirmButton = {
        TextButton(onClick = {
          confirmDelete = false
          repo.delete(preset.id)
          onBack()
        }) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
  }
}

/** One file of a preset while it is being edited (text fields keep what is typed) */
private class EditItem(item: PresetItem) {
  var kind by mutableStateOf(item.kind)
  var width by mutableStateOf(item.widthPx?.toString() ?: "")
  var height by mutableStateOf(item.heightPx?.toString() ?: "")
  var maxKb by mutableStateOf(item.maxKb?.toString() ?: "")
  var format by mutableStateOf(item.format)
  var background by mutableStateOf(item.background ?: PhotoBackground.WHITE)
}

/** New preset (id "new") or one of the user's own */
@Composable
fun PresetEditScreen(id: String, onDone: () -> Unit) {
  val repo = LocalContext.current.docReady.presets
  val existing = remember(id) { repo.get(id)?.takeIf { !it.builtIn } }
  var name by remember(id) { mutableStateOf(existing?.name ?: "") }
  val items = remember(id) {
    mutableStateListOf<EditItem>().apply {
      (existing?.items ?: listOf(PresetItem(ItemKind.PHOTO, 300, 300, 100, OutputFormat.JPG, PhotoBackground.WHITE))).forEach { add(EditItem(it)) }
    }
  }
  var error by remember { mutableStateOf<String?>(null) }

  fun save() {
    if (name.isBlank()) {
      error = "Give the preset a name."
      return
    }
    if (items.isEmpty()) {
      error = "Add at least one file."
      return
    }
    val built = items.mapIndexed { i, e ->
      val w = e.width.toIntOrNull()
      val h = e.height.toIntOrNull()
      val kb = e.maxKb.toIntOrNull()
      if (e.kind != ItemKind.DOCUMENT && (w == null || h == null || w !in 20..4000 || h !in 20..4000)) {
        error = "File ${i + 1}: width and height must be 20 to 4000 px."
        return
      }
      if (kb != null && kb !in 2..50_000) {
        error = "File ${i + 1}: max KB must be 2 to 50000 (or empty)."
        return
      }
      val format = when (e.kind) {
        ItemKind.DOCUMENT -> if (e.format == OutputFormat.JPG) OutputFormat.JPG else OutputFormat.PDF
        ItemKind.PHOTO -> OutputFormat.JPG
        ItemKind.SIGNATURE -> if (e.format == OutputFormat.PNG) OutputFormat.PNG else OutputFormat.JPG
      }
      PresetItem(
        kind = e.kind,
        widthPx = if (e.kind == ItemKind.DOCUMENT) null else w,
        heightPx = if (e.kind == ItemKind.DOCUMENT) null else h,
        maxKb = kb,
        format = format,
        background = if (e.kind == ItemKind.PHOTO) e.background else null,
      )
    }
    repo.save(Preset(existing?.id ?: PresetRepository.newId(), name.trim(), built))
    onDone()
  }

  Scaffold(
    topBar = {
      DocTopBar(
        if (existing == null) "New preset" else "Edit preset",
        onDone,
        actions = { TextButton(onClick = { save() }) { Text("Save") } },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(60) },
        label = { Text("Name (e.g. My scholarship form)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      items.forEachIndexed { i, e ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          SectionLabel("File ${i + 1}", Modifier.weight(1f))
          if (items.size > 1) {
            IconButton(onClick = { items.removeAt(i) }) { Icon(AppIcons.Close, contentDescription = "Remove file ${i + 1}", modifier = Modifier.size(20.dp)) }
          }
        }
        ChoiceChips(ItemKind.entries.toList(), e.kind, { it.label }, {
          e.kind = it
          if (it == ItemKind.DOCUMENT) e.format = OutputFormat.PDF else if (e.format == OutputFormat.PDF) e.format = OutputFormat.JPG
          if (it == ItemKind.SIGNATURE && e.width.isEmpty()) {
            e.width = "300"
            e.height = "80"
          }
        })
        VSpace(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (e.kind != ItemKind.DOCUMENT) {
            NumberField("Width px", e.width, { e.width = it }, Modifier.weight(1f))
            NumberField("Height px", e.height, { e.height = it }, Modifier.weight(1f))
          }
          NumberField("Max KB", e.maxKb, { e.maxKb = it }, Modifier.weight(1f))
        }
        VSpace(8)
        val formats = when (e.kind) {
          ItemKind.PHOTO -> listOf(OutputFormat.JPG)
          ItemKind.SIGNATURE -> listOf(OutputFormat.JPG, OutputFormat.PNG)
          ItemKind.DOCUMENT -> listOf(OutputFormat.PDF, OutputFormat.JPG)
        }
        ChoiceChips(formats, if (e.format in formats) e.format else formats[0], { it.name }, { e.format = it })
        if (e.kind == ItemKind.PHOTO) {
          VSpace(8)
          ChoiceChips(PhotoBackground.entries.toList(), e.background, { it.label }, { e.background = it })
        }
        if (e.kind == ItemKind.DOCUMENT) {
          Text("Pages are A4.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
      }
      VSpace(12)
      OutlinedButton(
        onClick = { items.add(EditItem(PresetItem(ItemKind.SIGNATURE, 300, 80, 60, OutputFormat.JPG))) },
        modifier = Modifier.fillMaxWidth(),
      ) { Text("+ Add a file") }
      ErrorText(error)
      VSpace(12)
      Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save preset") }
      VSpace(8)
      Text(OFFICIAL_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
