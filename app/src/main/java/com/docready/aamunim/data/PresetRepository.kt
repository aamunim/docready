package com.docready.aamunim.data

import android.content.Context
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.OutputFormat
import com.docready.aamunim.data.model.PhotoBackground
import com.docready.aamunim.data.model.Preset
import com.docready.aamunim.data.model.withItemIds
import com.docready.aamunim.data.model.PresetItem
import com.docready.aamunim.data.model.safeFileName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * The user's presets (every preset is made by the user, or imported), kept as JSON in the
 * app's private files (presets.json). Small, so read and written whole. Presets saved by older
 * versions (including copies of the old built-in ones) load as normal presets.
 */
class PresetRepository(context: Context) {
  private val file = File(context.filesDir, "presets.json")
  private val lock = Any()
  private val _presets = MutableStateFlow(read())
  val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

  fun get(id: String?): Preset? = if (id == null) null else _presets.value.firstOrNull { it.id == id }

  fun save(preset: Preset): Preset {
    val stored = preset.withItemIds(::newId).copy(builtIn = false, updatedAt = System.currentTimeMillis())
    synchronized(lock) {
      write(_presets.value.filter { it.id != stored.id } + stored)
    }
    return stored
  }

  fun delete(id: String) {
    synchronized(lock) { write(_presets.value.filter { it.id != id }) }
  }

  /** A copy to change ("B.Ed (copy)") */
  fun duplicate(id: String): Preset? {
    val src = get(id) ?: return null
    return save(src.copy(id = newId(), name = "${src.name} (copy)".take(60)))
  }

  /** A preset from a shared file, checked strictly, saved as a new preset of this user */
  fun import(file: File): Preset = save(PresetCodec.read(file).copy(id = newId()))

  private fun write(list: List<Preset>) {
    val sorted = list.sortedBy { it.name.lowercase() }
    file.writeTextAtomic(AppJson.encodeToString(ListSerializer(Preset.serializer()), sorted))
    _presets.value = sorted
  }

  private fun read(): List<Preset> = try {
    if (file.exists()) {
      val loaded = AppJson.decodeFromString(ListSerializer(Preset.serializer()), file.readText())
      // items saved by older versions get their ids now, kept from then on
      val list = loaded.map { it.withItemIds(::newId).copy(builtIn = false) }.sortedBy { it.name.lowercase() }
      if (list.zip(loaded.sortedBy { it.name.lowercase() }).any { (a, b) -> a.items != b.items }) {
        // (a failed write-back, e.g. a full disk, must never lose the presets: written next save)
        try {
          file.writeTextAtomic(AppJson.encodeToString(ListSerializer(Preset.serializer()), list))
        } catch (_: Exception) {
        }
      }
      list
    } else {
      emptyList()
    }
  } catch (_: Exception) {
    emptyList()
  }

  companion object {
    fun newId(): String = UUID.randomUUID().toString()
  }
}

/** The shared file: a small, versioned JSON document (".docready.json") */
@Serializable
private class PresetFile(val format: String, val version: Int, val preset: SharedPreset)

@Serializable
private class SharedPreset(val name: String, val items: List<PresetItem>)

/**
 * Presets to and from files people send each other. Reading is strict: a size cap, the
 * DocReady format mark, a known version, sane numbers and plain text only. Nothing in the file
 * is ever run; a bad file only gives a clear message.
 */
object PresetCodec {
  const val MIME = "application/json"
  const val EXTENSION = ".docready.json"
  private const val FORMAT = "docready-preset"
  const val MAX_BYTES = 64 * 1024
  private const val MAX_ITEMS = 40

  private val strict = Json {
    ignoreUnknownKeys = true
    isLenient = false
    encodeDefaults = true
    prettyPrint = true
  }

  /** The preset as a file in the app's share folder, named after it */
  fun write(context: Context, preset: Preset): File {
    val out = FileNames.outputFile(context, safeFileName(preset.name, "preset") + EXTENSION)
    val doc = PresetFile(FORMAT, 1, SharedPreset(preset.name, preset.items))
    out.writeText(strict.encodeToString(PresetFile.serializer(), doc))
    return out
  }

  fun read(file: File): Preset {
    if (!file.exists() || file.length() == 0L) throw IllegalArgumentException("The file is empty.")
    if (file.length() > MAX_BYTES) throw IllegalArgumentException("This file is too big to be a DocReady preset.")
    val doc = try {
      strict.decodeFromString(PresetFile.serializer(), file.readText())
    } catch (_: Exception) {
      throw IllegalArgumentException("This is not a DocReady preset file.")
    }
    if (doc.format != FORMAT) throw IllegalArgumentException("This is not a DocReady preset file.")
    if (doc.version != 1) throw IllegalArgumentException("This preset was made by a newer DocReady: update the app first.")
    val name = clean(doc.preset.name, 60)
    if (name.isEmpty()) throw IllegalArgumentException("The preset has no name.")
    val items = doc.preset.items
    if (items.isEmpty() || items.size > MAX_ITEMS) throw IllegalArgumentException("The preset must have 1 to $MAX_ITEMS items.")
    return Preset(id = PresetRepository.newId(), name = name, items = items.mapIndexed { i, it -> checked(it, i + 1) })
  }

  /** One item made safe: sizes in range, formats that fit its kind, plain short text */
  private fun checked(item: PresetItem, n: Int): PresetItem {
    val w = item.widthPx
    val h = item.heightPx
    val kb = item.maxKb
    if (item.kind != ItemKind.DOCUMENT && (w == null || h == null || w !in 20..4000 || h !in 20..4000)) {
      throw IllegalArgumentException("Item $n: width and height must be 20 to 4000 px.")
    }
    if (kb != null && kb !in 2..50_000) throw IllegalArgumentException("Item $n: max KB must be 2 to 50000.")
    val format = when (item.kind) {
      ItemKind.DOCUMENT -> if (item.format == OutputFormat.JPG) OutputFormat.JPG else OutputFormat.PDF
      ItemKind.PHOTO -> OutputFormat.JPG
      ItemKind.SIGNATURE -> if (item.format == OutputFormat.PNG) OutputFormat.PNG else OutputFormat.JPG
    }
    return PresetItem(
      kind = item.kind,
      widthPx = if (item.kind == ItemKind.DOCUMENT) null else w,
      heightPx = if (item.kind == ItemKind.DOCUMENT) null else h,
      maxKb = kb,
      format = format,
      background = if (item.kind == ItemKind.PHOTO) item.background ?: PhotoBackground.WHITE else null,
      name = item.name?.let { clean(it, 60) }?.ifEmpty { null },
      required = item.required,
      pagesHint = if (item.kind == ItemKind.DOCUMENT) item.pagesHint?.let { clean(it, 20) }?.ifEmpty { null } else null,
      // (ids from the file are not trusted: an imported preset's items get their own)
      id = PresetRepository.newId(),
      keywords = item.keywords.map { clean(it, 30) }.filter { it.isNotEmpty() }.distinct().take(20),
    )
  }

  /** Plain text only: control marks out, trimmed, at most [max] characters (letters of any script stay) */
  private fun clean(s: String, max: Int): String = s.replace(Regex("\\p{Cntrl}"), " ").trim().take(max).trim()
}
