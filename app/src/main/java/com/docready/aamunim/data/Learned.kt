package com.docready.aamunim.data

import android.content.Context
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Words learned for preset items (Split by preset): when the user gives a page to an item, the
 * page's most frequent words are counted for that item (by its lasting id), and later pages with
 * those words are suggested for it. Kept on the phone only (learned.json), a few dozen words an item.
 */
class LearnedWords(context: Context) {
  private val file = File(context.filesDir, "learned.json")
  private val lock = Any()
  private val serializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Int.serializer()))
  private var counts: Map<String, Map<String, Int>> = read()

  /** The item's learned words, most used first */
  fun words(itemId: String): List<String> = synchronized(lock) {
    counts[itemId]?.entries?.sortedByDescending { it.value }?.map { it.key } ?: emptyList()
  }

  /** [words] counted once more for the item; at most [MAX_WORDS] kept (the least used go). Blocking IO. */
  fun learn(itemId: String, words: List<String>) {
    if (itemId.isEmpty() || words.isEmpty()) return
    synchronized(lock) {
      val m = (counts[itemId] ?: emptyMap()).toMutableMap()
      for (w in words.toSet()) m[w] = (m[w] ?: 0) + 1
      val kept = m.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(MAX_WORDS).associate { it.key to it.value }
      counts = counts + (itemId to kept)
      try {
        file.writeTextAtomic(AppJson.encodeToString(serializer, counts))
      } catch (_: Exception) {
        // (learning is a help: a full disk only means it is not kept)
      }
    }
  }

  private fun read(): Map<String, Map<String, Int>> = try {
    if (file.exists()) AppJson.decodeFromString(serializer, file.readText()) else emptyMap()
  } catch (_: Exception) {
    emptyMap()
  }

  companion object {
    const val MAX_WORDS = 40
  }
}
