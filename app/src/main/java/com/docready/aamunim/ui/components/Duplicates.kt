package com.docready.aamunim.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docready.aamunim.imaging.PageHash
import com.docready.aamunim.ui.theme.DocReadyColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pages that look like an earlier page (the same paper added twice): a perceptual hash of each
 * page's small picture ([thumb]), worked out in the background as pages come. "Remove
 * duplicates" removes them, with Undo. [changed] is called after pages are removed or put back.
 */
class Duplicates<T : Any>(
  scope: CoroutineScope,
  private val pages: SnapshotStateList<T>,
  private val thumb: (T) -> ImageBitmap?,
  private val changed: () -> Unit,
  /**
   * Called before pages are removed: what else to keep about them (a split's items and cuts),
   * as a step run after Undo has put them back
   */
  private val keep: (List<T>) -> (() -> Unit) = { {} },
) {
  private val hashes = mutableStateMapOf<T, PageHash.Hash>()

  init {
    scope.launch {
      snapshotFlow { pages.toList() }.collectLatest { list ->
        for (p in list) {
          if (p in hashes) continue
          val t = thumb(p) ?: continue
          hashes[p] = withContext(Dispatchers.Default) { hashOf(t) }
        }
      }
    }
  }

  /** For each page (by position), the earlier page it looks like, or null */
  val sameAs: List<Int?> get() = PageHash.duplicates(pages.map { hashes[it] })

  val count: Int get() = sameAs.count { it != null }

  /** The pages last removed with their places, for Undo (null: nothing to undo) */
  var removed by mutableStateOf<List<Pair<Int, T>>?>(null)
    private set
  private var restore: () -> Unit = {}

  fun removeAll() {
    val same = sameAs
    val gone = pages.indices.filter { same[it] != null }.map { it to pages[it] }
    if (gone.isEmpty()) return
    restore = keep(gone.map { it.second })
    for ((i, _) in gone.asReversed()) pages.removeAt(i)
    removed = gone
    changed()
  }

  fun undo() {
    val back = removed ?: return
    for ((i, p) in back) pages.add(minOf(i, pages.size), p)
    removed = null
    changed()
    // (after the list's own clean-up, which forgets what removed pages had)
    restore()
    restore = {}
  }

  /** The list changed in another way: Undo would put pages back in the wrong places */
  fun forgetUndo() {
    removed = null
    restore = {}
  }

  private fun hashOf(b: ImageBitmap): PageHash.Hash {
    val px = IntArray(b.width * b.height)
    b.readPixels(px)
    return PageHash.of(px, b.width, b.height)
  }
}

/** "Looks like page 3" on a page's tile (amber) */
@Composable
fun DuplicateBadge(of: Int, modifier: Modifier = Modifier) {
  val extra = DocReadyColors.extra
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = extra.warningContainer,
    border = BorderStroke(1.dp, extra.warningBorder),
    modifier = modifier,
  ) {
    Text(
      "Looks like page ${of + 1}",
      color = extra.onWarningContainer,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

/** Above a page list: how many pages look like earlier ones, Remove duplicates (asks first), Undo */
@Composable
fun <T : Any> DuplicatesBar(d: Duplicates<T>, enabled: Boolean, modifier: Modifier = Modifier) {
  val extra = DocReadyColors.extra
  var asking by remember { mutableStateOf(false) }
  val n = d.count
  val undo = d.removed
  if (n == 0 && undo == null) return
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = extra.warningContainer,
    border = BorderStroke(1.dp, extra.warningBorder),
    modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
  ) {
    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        if (n > 0) {
          Text(
            if (n == 1) "1 page looks like an earlier page" else "$n pages look like earlier pages",
            color = extra.onWarningContainer,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        if (undo != null) {
          Text(
            "Removed ${undo.size} duplicate page${if (undo.size == 1) "" else "s"}",
            color = extra.onWarningContainer,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      if (undo != null) TextButton(onClick = { d.undo() }, enabled = enabled) { Text("Undo") }
      if (n > 0) TextButton(onClick = { asking = true }, enabled = enabled) { Text("Remove duplicates") }
    }
  }
  if (asking) {
    AlertDialog(
      onDismissRequest = { asking = false },
      title = { Text("Remove duplicate pages?") },
      text = { Text("${if (n == 1) "1 page looks" else "$n pages look"} like an earlier page (marked on the pages). The first of each is kept. You can undo this.") },
      confirmButton = {
        TextButton(onClick = {
          asking = false
          d.removeAll()
        }) { Text("Remove $n") }
      },
      dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
    )
  }
}
