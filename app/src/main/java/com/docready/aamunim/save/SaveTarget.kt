package com.docready.aamunim.save

import android.content.Context
import java.io.File

/**
 * A ready file made by the app (in its private cache) with the name it should be saved as.
 * [maxKb] / [exactSize]: the preset item's limit it must meet (the size checker, "Fix to fit");
 * [itemIndex]: which preset item it is (a student's checklist).
 */
data class ReadyFile(
  val file: File,
  val name: String,
  val mime: String,
  val maxKb: Int? = null,
  val exactSize: Boolean = false,
  val itemIndex: Int? = null,
)

/**
 * Where ready files can go. Phase 1: this phone and the system share sheet. Phase 2 adds the
 * user's own Google Drive (drive.file scope) and the ITTC Drive; they are listed already
 * (shown as "Coming soon") so the save sheet and settings stay the same shape.
 */
enum class SaveTargetKind(val title: String) {
  PHONE("This phone"),
  SHARE("Share"),
  GOOGLE_DRIVE("My Google Drive"),
  ITTC("ITTC Drive (a lead's folder)"),
}

sealed interface SaveOutcome {
  /** [where]: a place people understand ("Downloads / DocReady / My form") */
  data class Saved(
    val where: String,
    val names: List<String>,
    val note: String? = null,
    /** What was saved, with its link (a student keeps it) */
    val files: List<com.docready.aamunim.data.model.SavedFile> = emptyList(),
  ) : SaveOutcome
  data object Shared : SaveOutcome
  data class Failed(val message: String) : SaveOutcome
}

interface SaveTarget {
  val kind: SaveTargetKind
  val available: Boolean

  /** Plain words under the title (where it goes, or why it can't be used yet) */
  fun description(folder: String): String

  /** [context] should be an Activity for targets that open other apps (Share) */
  suspend fun save(context: Context, files: List<ReadyFile>, folder: String): SaveOutcome
}

/** A place that comes in a later version: shown, never used */
class ComingSoonTarget(override val kind: SaveTargetKind) : SaveTarget {
  override val available = false

  override fun description(folder: String) = "Coming soon"

  override suspend fun save(context: Context, files: List<ReadyFile>, folder: String): SaveOutcome =
    SaveOutcome.Failed("${kind.title} is coming in a later version.")
}

/** All places, in the order the save sheet shows them */
class SaveTargets(tree: com.docready.aamunim.data.FolderTree) {
  val phone: PhoneSaveTarget = PhoneSaveTarget(tree)
  val share: SaveTarget = ShareSaveTarget
  val googleDrive: SaveTarget = ComingSoonTarget(SaveTargetKind.GOOGLE_DRIVE)
  val ittc: SaveTarget = ComingSoonTarget(SaveTargetKind.ITTC)

  val all: List<SaveTarget> get() = listOf(phone, share, googleDrive, ittc)
}
