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
  /** Which item of the student's preset it is (null: another file) */
  val itemIndex: Int? = null,
  val itemTitle: String? = null,
  /** Its copy in Downloads/DocReady/<student> (a content link the app may delete) */
  val downloadsUri: String? = null,
  val savedAt: Long = 0L,
)

/** A student (an applicant): the papers one form asks of them, made and kept together */
@Serializable
data class Student(
  val id: String,
  val name: String,
  val roll: String? = null,
  val dob: String? = null,
  val notes: String? = null,
  val presetId: String? = null,
  val files: List<StudentFile> = emptyList(),
  val createdAt: Long = 0L,
  val updatedAt: Long = 0L,
)

/** How one preset item stands for a student */
enum class ItemState { DONE, TOO_BIG, MISSING }

/** The newest file for item [index], or null */
fun Student.fileFor(index: Int): StudentFile? = files.filter { it.itemIndex == index }.maxByOrNull { it.savedAt }

fun Student.stateOf(index: Int, item: PresetItem): ItemState {
  val f = fileFor(index) ?: return ItemState.MISSING
  val limit = item.maxKb?.let { it * 1024L }
  return if (limit != null && f.bytes > limit) ItemState.TOO_BIG else ItemState.DONE
}
