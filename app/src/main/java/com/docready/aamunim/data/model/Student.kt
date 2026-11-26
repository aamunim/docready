package com.docready.aamunim.data.model

import kotlinx.serialization.Serializable

/** A file kept for a student: in the app's own storage, and (when saved) in Downloads too */
@Serializable
data class StudentFile(
  val name: String,
  /** The app's own copy (files/students/<id>/…) */
  val path: String,
  val mime: String,
  val bytes: Long,
  /** (older versions: the item's place in the preset; only read to find [itemId] once) */
  val itemIndex: Int? = null,
  /** Which item of the student's preset it is ([PresetItem.id]; null: another file) */
  val itemId: String? = null,
  val itemTitle: String? = null,
  /** Its copy in Downloads/DocReady/<student> (a content link the app may delete) */
  val downloadsUri: String? = null,
  val savedAt: Long = 0L,
  /** Google Drive: the file uploaded from this one (a new revision next time), and its state */
  val driveFileId: String? = null,
  val drive: com.docready.aamunim.data.DriveState = com.docready.aamunim.data.DriveState.NONE,
  val driveError: String? = null,
  /** An unfinished resumable upload: its session address, and the size it was started for */
  val driveSession: String? = null,
  val driveSessionSize: Long? = null,
)

/** A student (an applicant): the papers one form asks of them, made and kept together */
@Serializable
data class Student(
  val id: String,
  val name: String,
  val roll: String? = null,
  val dob: String? = null,
  val registration: String? = null,
  val notes: String? = null,
  val presetId: String? = null,
  val files: List<StudentFile> = emptyList(),
  val createdAt: Long = 0L,
  val updatedAt: Long = 0L,
)

/** How one preset item stands for a student */
enum class ItemState { DONE, TOO_BIG, MISSING }

/** The newest file for [item] (matched by the item's id, so reordering items never moves ticks), or null */
fun Student.fileFor(item: PresetItem): StudentFile? =
  if (item.id.isEmpty()) null else files.filter { it.itemId == item.id }.maxByOrNull { it.savedAt }

fun Student.stateOf(item: PresetItem): ItemState {
  val f = fileFor(item) ?: return ItemState.MISSING
  val limit = item.maxKb?.let { it * 1024L }
  return if (limit != null && f.bytes > limit) ItemState.TOO_BIG else ItemState.DONE
}
