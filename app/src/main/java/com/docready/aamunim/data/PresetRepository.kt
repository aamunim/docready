package com.docready.aamunim.data

import android.content.Context
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.data.model.OutputFormat
import com.docready.aamunim.data.model.PhotoBackground
import com.docready.aamunim.data.model.Preset
import com.docready.aamunim.data.model.PresetItem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Form presets: the built-in ones (read-only, can be copied) and the user's own, kept as
 * JSON in the app's private files (presets.json). Small, so read and written whole.
 */
class PresetRepository(context: Context) {
  private val file = File(context.filesDir, "presets.json")
  private val lock = Any()
  private val _presets = MutableStateFlow(BUILT_IN + readUser())
  val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

  fun get(id: String?): Preset? = if (id == null) null else _presets.value.firstOrNull { it.id == id }

  fun save(preset: Preset): Preset {
    val stored = preset.copy(builtIn = false, updatedAt = System.currentTimeMillis())
    synchronized(lock) {
      val user = userPresets().filter { it.id != stored.id } + stored
      write(user)
    }
    return stored
  }

  fun delete(id: String) {
    synchronized(lock) { write(userPresets().filter { it.id != id }) }
  }

  /** A user's own copy of any preset (to change a built-in one) */
  fun copy(id: String): Preset? {
    val src = get(id) ?: return null
    return save(src.copy(id = newId(), name = "${src.name} (copy)", builtIn = false))
  }

  private fun userPresets() = _presets.value.filter { !it.builtIn }

  private fun write(user: List<Preset>) {
    val sorted = user.sortedBy { it.name.lowercase() }
    file.writeTextAtomic(AppJson.encodeToString(ListSerializer(Preset.serializer()), sorted))
    _presets.value = BUILT_IN + sorted
  }

  private fun readUser(): List<Preset> = try {
    if (file.exists()) AppJson.decodeFromString(ListSerializer(Preset.serializer()), file.readText()).map { it.copy(builtIn = false) } else emptyList()
  } catch (_: Exception) {
    emptyList()
  }

  companion object {
    fun newId(): String = UUID.randomUUID().toString()

    /** Common sizes; each form's own instructions decide (see the note on the Presets screen) */
    val BUILT_IN = listOf(
      Preset(
        id = "builtin-job-application",
        name = "Online job application",
        builtIn = true,
        items = listOf(
          PresetItem(ItemKind.PHOTO, 300, 300, 100, OutputFormat.JPG, PhotoBackground.WHITE),
          PresetItem(ItemKind.SIGNATURE, 300, 80, 60, OutputFormat.JPG),
        ),
      ),
      Preset(
        id = "builtin-document-pdf",
        name = "Document PDF",
        builtIn = true,
        items = listOf(PresetItem(ItemKind.DOCUMENT, null, null, 1024, OutputFormat.PDF)),
      ),
    )
  }
}
