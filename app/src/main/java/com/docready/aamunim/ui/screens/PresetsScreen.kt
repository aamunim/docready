package com.docready.aamunim.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.docready.aamunim.data.FileNames
import com.docready.aamunim.data.PresetCodec
import com.docready.aamunim.data.PresetRepository
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.OutputFormat
import com.docready.aamunim.data.model.PhotoBackground
import com.docready.aamunim.data.model.Preset
import com.docready.aamunim.data.model.PresetItem
import com.docready.aamunim.data.model.label
import com.docready.aamunim.data.model.summary
import com.docready.aamunim.data.model.title
import com.docready.aamunim.docReady
import com.docready.aamunim.save.ShareSaveTarget
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.Badge
import com.docready.aamunim.ui.components.ChoiceChips
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.ErrorText
import com.docready.aamunim.ui.components.NumberField
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.components.VSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OFFICIAL_NOTE =
  "Check each size and KB against the form's official instructions."

/** A preset file chosen in the file picker (JSON may come as any of these kinds) */
private val PRESET_FILE_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

/** The picked preset file read and saved (off the main thread); a message for anything wrong */
suspend fun importPreset(context: Context, uri: Uri): Result<Preset> = withContext(Dispatchers.IO) {
  runCatching {
    val file = try {
      FileNames.copyIn(context, uri, "json", PresetCodec.MAX_BYTES.toLong())
    } catch (_: IllegalArgumentException) {
      throw IllegalArgumentException("This file is too big to be a DocReady preset.")
    }
    try {
      context.docReady.presets.import(file)
    } finally {
      file.delete()
    }
  }
}

/** The preset sent with the share sheet (WhatsApp, Gmail, Files...) as a .docready.json file */
fun sharePreset(context: Context, preset: Preset) {
  try {
    val file = PresetCodec.write(context, preset)
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    ShareSaveTarget.shareUris(context, listOf(uri), PresetCodec.MIME)
  } catch (e: Exception) {
    Toast.makeText(context, e.message ?: "The preset could not be shared.", Toast.LENGTH_LONG).show()
  }
}

/** "Import a preset": the file picker, then the preset saved and opened */
@Composable
private fun rememberPresetImport(onImported: (Preset) -> Unit): () -> Unit {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      scope.launch {
        importPreset(context, uri)
          .onSuccess {
            Toast.makeText(context, "Preset “${it.name}” imported.", Toast.LENGTH_SHORT).show()
            onImported(it)
          }
          .onFailure { Toast.makeText(context, it.message ?: "This preset could not be imported.", Toast.LENGTH_LONG).show() }
      }
    }
  }
  return {
    try {
      launcher.launch(PRESET_FILE_TYPES)
    } catch (_: ActivityNotFoundException) {
      Toast.makeText(context, "No file picker found on this phone.", Toast.LENGTH_LONG).show()
    }
  }
}

@Composable
fun PresetsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
  val repo = LocalContext.current.docReady.presets
  val presets by repo.presets.collectAsState()
  var query by rememberSaveable { mutableStateOf("") }
  val import = rememberPresetImport { onOpen(it.id) }
  val shown = presets.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
  Scaffold(
    topBar = {
      DocTopBar("Presets", onBack, actions = {
        TextButton(onClick = import) { Text("Import") }
        if (presets.isNotEmpty()) TextButton(onClick = onNew) { Text("+ New") }
      })
    },
  ) { padding ->
    if (presets.isEmpty()) {
      Column(
        Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        VSpace(24)
        Icon(AppIcons.Pages, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        VSpace(12)
        Text("No presets yet", style = MaterialTheme.typography.titleLarge)
        VSpace(8)
        Text(
          "A preset is the list of papers a form asks for, with each one's size and KB. Make one for each form you fill.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        VSpace(20)
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Create your first preset") }
        Text("or", modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = import, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Import a preset someone sent you") }
        VSpace(24)
        Text(
          "Later, on each preset: Edit · Duplicate · Share (WhatsApp, file) · Delete",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
      return@Scaffold
    }
    LazyColumn(
      Modifier.padding(padding).fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
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
      items(shown, key = { it.id }) { p -> PresetCard(p) { onOpen(p.id) } }
      if (shown.isEmpty()) item { Text("No preset matches.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
      item { Text(OFFICIAL_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        Badge("${p.items.size} item${if (p.items.size == 1) "" else "s"}")
      }
      for (item in p.items) {
        Text(item.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
      }
    }
  }
}

/** "Create a preset" when the user has none (shown where a preset picker would be empty) */
@Composable
fun NoPresetsCard(onCreate: () -> Unit, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.secondaryContainer,
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(14.dp)) {
      Text("No presets yet", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
      Text(
        "Save the form's papers and sizes once as a preset, then use it here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      TextButton(onClick = onCreate) { Text("Create a preset") }
    }
  }
}

/**
 * Sizes from a preset for one kind of file (Photo / Signature / Scan): chips of every matching
 * item; none at all → "Create a preset".
 */
@Composable
fun PresetItemShortcut(kind: ItemKind, onPick: (Preset, PresetItem) -> Unit, onCreate: () -> Unit) {
  val presets by LocalContext.current.docReady.presets.presets.collectAsState()
  if (presets.isEmpty()) {
    NoPresetsCard(onCreate, Modifier.padding(top = 8.dp))
    return
  }
  val matching = presets.flatMap { p -> p.items.filter { it.kind == kind }.map { p to it } }
  if (matching.isEmpty()) return
  SectionLabel("Sizes from a preset")
  Row(
    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for ((p, item) in matching) {
      androidx.compose.material3.FilterChip(
        selected = false,
        onClick = { onPick(p, item) },
        label = { Text("${p.name} · ${item.title}") },
      )
    }
  }
}

/** A preset's files, each with a button to make it; share / duplicate / edit / delete */
@Composable
fun PresetDetailScreen(
  id: String,
  onBack: () -> Unit,
  onMake: (Preset, Int) -> Unit,
  onEdit: (String) -> Unit,
) {
  val context = LocalContext.current
  val repo = context.docReady.presets
  val presets by repo.presets.collectAsState()
  val preset = presets.firstOrNull { it.id == id }
  var confirmDelete by remember { mutableStateOf(false) }
  Scaffold(
    topBar = {
      DocTopBar(
        preset?.name ?: "Preset",
        onBack,
        actions = {
          if (preset != null) {
            IconButton(onClick = { sharePreset(context, preset) }) { Icon(AppIcons.Share, contentDescription = "Share preset") }
            IconButton(onClick = { repo.duplicate(preset.id)?.let { onEdit(it.id) } }) { Icon(AppIcons.Copy, contentDescription = "Duplicate") }
            IconButton(onClick = { onEdit(preset.id) }) { Icon(AppIcons.Edit, contentDescription = "Edit") }
            IconButton(onClick = { confirmDelete = true }) { Icon(AppIcons.Delete, contentDescription = "Delete") }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(item.title, fontWeight = FontWeight.SemiBold)
                  if (item.required) {
                    Spacer(Modifier.width(6.dp))
                    Badge("Required")
                  }
                }
                Text(item.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Button(onClick = { onMake(preset, i) }) { Text("Make") }
            }
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

/** One item of a preset while it is being edited (text fields keep what is typed) */
private class EditItem(item: PresetItem) {
  var kind by mutableStateOf(item.kind)
  var width by mutableStateOf(item.widthPx?.toString() ?: "")
  var height by mutableStateOf(item.heightPx?.toString() ?: "")
  var maxKb by mutableStateOf(item.maxKb?.toString() ?: "")
  var format by mutableStateOf(item.format)
  var background by mutableStateOf(item.background ?: PhotoBackground.WHITE)
  var name by mutableStateOf(item.name ?: "")
  var required by mutableStateOf(item.required)
  var pagesHint by mutableStateOf(item.pagesHint ?: "")

  /** As text, to be kept in the saved screen state */
  fun toSaved(): ArrayList<String> =
    arrayListOf(kind.name, width, height, maxKb, format.name, background.name, name, required.toString(), pagesHint)

  companion object {
    fun fromSaved(v: List<String>): EditItem = EditItem(PresetItem(ItemKind.valueOf(v[0]))).apply {
      width = v[1]
      height = v[2]
      maxKb = v[3]
      format = OutputFormat.valueOf(v[4])
      background = PhotoBackground.valueOf(v[5])
      name = v.getOrNull(6) ?: ""
      required = v.getOrNull(7)?.toBooleanStrictOrNull() ?: true
      pagesHint = v.getOrNull(8) ?: ""
    }

    fun newOf(kind: ItemKind): EditItem = EditItem(
      when (kind) {
        ItemKind.DOCUMENT -> PresetItem(ItemKind.DOCUMENT, null, null, null, OutputFormat.PDF)
        ItemKind.PHOTO -> PresetItem(ItemKind.PHOTO, 300, 300, 100, OutputFormat.JPG, PhotoBackground.WHITE)
        ItemKind.SIGNATURE -> PresetItem(ItemKind.SIGNATURE, 300, 80, 60, OutputFormat.JPG)
      },
    )
  }
}

private val EditItemsSaver = listSaver<SnapshotStateList<EditItem>, ArrayList<String>>(
  save = { list -> list.map { it.toSaved() } },
  restore = { saved -> mutableStateListOf<EditItem>().apply { saved.forEach { add(EditItem.fromSaved(it)) } } },
)

/** New preset (id "new") or one of the user's own */
@Composable
fun PresetEditScreen(id: String, onDone: () -> Unit) {
  val context = LocalContext.current
  val repo = context.docReady.presets
  val existing = remember(id) { repo.get(id) }
  // (kept when the screen turns or the app is closed in the background)
  var name by rememberSaveable(id) { mutableStateOf(existing?.name ?: "") }
  val items = rememberSaveable(id, saver = EditItemsSaver) {
    mutableStateListOf<EditItem>().apply { existing?.items?.forEach { add(EditItem(it)) } }
  }
  var error by rememberSaveable { mutableStateOf<String?>(null) }
  var confirmDelete by remember { mutableStateOf(false) }

  fun build(): Preset? {
    if (name.isBlank()) {
      error = "Give the preset a name (e.g. B.Ed admission)."
      return null
    }
    if (items.isEmpty()) {
      error = "Add at least one item: + Document, + Photo or + Signature."
      return null
    }
    val built = items.mapIndexed { i, e ->
      val w = e.width.toIntOrNull()
      val h = e.height.toIntOrNull()
      val kb = e.maxKb.toIntOrNull()
      if (e.kind != ItemKind.DOCUMENT && (w == null || h == null || w !in 20..4000 || h !in 20..4000)) {
        error = "Item ${i + 1}: width and height must be 20 to 4000 px."
        return null
      }
      if (kb != null && kb !in 2..50_000) {
        error = "Item ${i + 1}: max KB must be 2 to 50000 (or empty for no limit)."
        return null
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
        name = e.name.trim().take(60).ifEmpty { null },
        required = e.required,
        pagesHint = if (e.kind == ItemKind.DOCUMENT) e.pagesHint.trim().take(20).ifEmpty { null } else null,
      )
    }
    return Preset(existing?.id ?: PresetRepository.newId(), name.trim().take(60), built)
  }

  fun save() {
    val p = build() ?: return
    repo.save(p)
    onDone()
  }

  Scaffold(
    topBar = {
      DocTopBar(
        if (existing == null) "New preset" else "Edit preset",
        onDone,
        actions = {
          if (existing != null) {
            IconButton(onClick = { build()?.let { sharePreset(context, it) } }) { Icon(AppIcons.Share, contentDescription = "Share preset") }
            IconButton(onClick = { confirmDelete = true }) { Icon(AppIcons.Delete, contentDescription = "Delete preset") }
          }
          TextButton(onClick = { save() }) { Text("Save") }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(60) },
        label = { Text("Preset name (e.g. B.Ed admission)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      if (items.isEmpty()) {
        VSpace(12)
        Text(
          "Add each paper the form asks for: the photo, the signature, and every document (SSC certificate, NID…).",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        SectionLabel("Items · use the arrows to reorder")
      }
      items.forEachIndexed { i, e -> ItemEditor(i, e, items) }
      VSpace(12)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { items.add(EditItem.newOf(ItemKind.DOCUMENT)) }, modifier = Modifier.weight(1f)) { Text("+ Document") }
        OutlinedButton(onClick = { items.add(EditItem.newOf(ItemKind.PHOTO)) }, modifier = Modifier.weight(1f)) { Text("+ Photo") }
        OutlinedButton(onClick = { items.add(EditItem.newOf(ItemKind.SIGNATURE)) }, modifier = Modifier.weight(1f)) { Text("+ Signature") }
      }
      ErrorText(error)
      VSpace(12)
      Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save preset") }
      if (existing != null) {
        VSpace(8)
        OutlinedButton(
          onClick = {
            val p = build() ?: return@OutlinedButton
            repo.save(p.copy(id = PresetRepository.newId(), name = "${p.name} (copy)".take(60)))
            Toast.makeText(context, "Saved a copy: “${p.name} (copy)”.", Toast.LENGTH_SHORT).show()
            onDone()
          },
          modifier = Modifier.fillMaxWidth(),
        ) { Text("Duplicate preset") }
      }
      VSpace(8)
      Text(OFFICIAL_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
  if (confirmDelete && existing != null) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("Delete “${existing.name}”?") },
      text = { Text("Files you already made stay where they are.") },
      confirmButton = {
        TextButton(onClick = {
          confirmDelete = false
          repo.delete(existing.id)
          onDone()
        }) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun ItemEditor(i: Int, e: EditItem, items: SnapshotStateList<EditItem>) {
  VSpace(10)
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "${i + 1} · ${e.name.trim().ifEmpty { e.kind.label }}",
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { items.add(i - 1, items.removeAt(i)) }, enabled = i > 0) { Icon(AppIcons.Up, contentDescription = "Move up") }
        IconButton(onClick = { items.add(i + 1, items.removeAt(i)) }, enabled = i < items.size - 1) { Icon(AppIcons.Down, contentDescription = "Move down") }
        IconButton(onClick = { items.removeAt(i) }) { Icon(AppIcons.Close, contentDescription = "Remove item ${i + 1}", modifier = Modifier.size(20.dp)) }
      }
      ChoiceChips(ItemKind.entries.toList(), e.kind, { it.label }, {
        e.kind = it
        if (it == ItemKind.DOCUMENT) e.format = OutputFormat.PDF else if (e.format == OutputFormat.PDF) e.format = OutputFormat.JPG
        if (it != ItemKind.DOCUMENT && e.width.isEmpty()) {
          e.width = "300"
          e.height = if (it == ItemKind.SIGNATURE) "80" else "300"
        }
      })
      VSpace(8)
      OutlinedTextField(
        value = e.name,
        onValueChange = { e.name = it.take(60) },
        label = { Text(if (e.kind == ItemKind.DOCUMENT) "Name (e.g. SSC certificate)" else "Name (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      VSpace(8)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (e.kind != ItemKind.DOCUMENT) {
          NumberField("Width px", e.width, { e.width = it }, Modifier.weight(1f))
          NumberField("Height px", e.height, { e.height = it }, Modifier.weight(1f))
        }
        NumberField("Max KB", e.maxKb, { e.maxKb = it }, Modifier.weight(1f))
        if (e.kind == ItemKind.DOCUMENT) {
          OutlinedTextField(
            value = e.pagesHint,
            onValueChange = { e.pagesHint = it.take(20) },
            label = { Text("Pages") },
            placeholder = { Text("1–2") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
        }
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
      Row(
        Modifier.fillMaxWidth().clickable { e.required = !e.required }.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(checked = e.required, onCheckedChange = { e.required = it })
        Text("Required", style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}
