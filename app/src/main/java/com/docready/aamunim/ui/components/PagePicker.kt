package com.docready.aamunim.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.docready.aamunim.data.model.PageLimits
import com.docready.aamunim.ui.theme.DocReadyColors

/**
 * One page in a page picker: its picture, its place in the choice ([order], 0-based; -1 = not
 * chosen) as a number badge, and a suggestion ("Looks like SSC certificate?") as a highlight only.
 * Tap chooses; hold (or the magnifier) shows it large.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PickTile(thumb: ImageBitmap?, label: String, order: Int, suggestion: String?, onTap: () -> Unit, onZoom: () -> Unit) {
  val chosen = order >= 0
  val extra = DocReadyColors.extra
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier
        .fillMaxWidth()
        .aspectRatio(0.72f)
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
        .combinedClickable(onClick = onTap, onLongClick = onZoom),
    ) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
          if (chosen) 2.dp else 1.dp,
          if (chosen) MaterialTheme.colorScheme.primary else if (suggestion != null) extra.warningBorder else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxSize(),
      ) {
        thumb?.let { Image(it, contentDescription = label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp)) }
      }
      if (chosen) {
        Box(
          Modifier.padding(5.dp).size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
          contentAlignment = Alignment.Center,
        ) { Text("${order + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
      }
      IconButton(onClick = onZoom, modifier = Modifier.align(Alignment.TopEnd).size(44.dp)) {
        Icon(AppIcons.Search, contentDescription = "See $label large", modifier = Modifier.size(18.dp))
      }
    }
    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    if (suggestion != null) {
      Surface(shape = RoundedCornerShape(8.dp), color = extra.warningContainer) {
        Text(
          suggestion,
          color = extra.onWarningContainer,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
      }
    }
  }
}

/**
 * Choosing pages (in order) when there are more than an item takes ([range]): the chosen
 * positions in their order go to [onUse]; nothing is merged beyond the limit.
 */
@Composable
fun PagePickerDialog(thumbs: List<ImageBitmap?>, range: IntRange, itemTitle: String, onUse: (List<Int>) -> Unit, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val chosen = remember { mutableStateListOf<Int>() }
  var zoom by remember { mutableStateOf<Int?>(null) }
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
          Text(PageLimits.chooseTitle(range), style = MaterialTheme.typography.titleLarge)
          Text(
            "${thumbs.size} pages; $itemTitle takes ${PageLimits.describe(range)}. Tap pages in the order you want them; hold one to see it large.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        LazyVerticalGrid(
          columns = GridCells.Adaptive(104.dp),
          contentPadding = PaddingValues(16.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f),
        ) {
          itemsIndexed(thumbs) { i, t ->
            PickTile(t, "Page ${i + 1}", chosen.indexOf(i), null, onTap = {
              when {
                chosen.remove(i) -> Unit
                PageLimits.canSelectMore(chosen.size, range) -> chosen.add(i)
                range.last == 1 -> {
                  chosen.clear()
                  chosen.add(i)
                }
                else -> Toast.makeText(context, "This item takes at most ${range.last} pages: unselect one first.", Toast.LENGTH_SHORT).show()
              }
            }, onZoom = { zoom = i })
          }
        }
        BottomActions {
          OutlinedButton(onClick = onDismiss, modifier = Modifier.height(52.dp)) { Text("Cancel") }
          Button(
            onClick = { onUse(chosen.toList()) },
            enabled = PageLimits.selectionOk(chosen.size, range),
            modifier = Modifier.weight(1f).height(52.dp),
          ) { Text("Use selected (${chosen.size})") }
        }
      }
    }
  }
  zoom?.let { i ->
    Dialog(onDismissRequest = { zoom = null }) {
      Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          thumbs.getOrNull(i)?.let { Image(it, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) }
          TextButton(onClick = { zoom = null }) { Text("Close") }
        }
      }
    }
  }
}
