package com.docready.aamunim.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docready.aamunim.data.FolderLogic
import com.docready.aamunim.data.FolderNode

/** The folders as a list, each under its parent (indented), for choosing one */
fun flatTree(folders: List<FolderNode>, exclude: Set<String> = emptySet()): List<Pair<FolderNode, Int>> {
  val out = ArrayList<Pair<FolderNode, Int>>()
  fun walk(parent: String?, depth: Int) {
    for (f in FolderLogic.children(folders, parent).sortedBy { it.name.lowercase() }) {
      if (f.id in exclude) continue
      out.add(f to depth)
      walk(f.id, depth + 1)
    }
  }
  walk(null, 0)
  return out
}

/**
 * Choosing a folder (save sheet "Change", Folders "Move"): the tree, the current one marked;
 * "New folder" makes one inside the chosen one (or at the top) and picks it.
 */
@Composable
fun FolderPickerDialog(
  folders: List<FolderNode>,
  current: String?,
  title: String = "Save to folder",
  exclude: Set<String> = emptySet(),
  onPick: (String) -> Unit,
  onNew: (parentId: String?, name: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var chosen by remember { mutableStateOf(current) }
  var naming by remember { mutableStateOf(false) }
  var name by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          items(flatTree(folders, exclude), key = { it.first.id }) { (f, depth) ->
            val on = f.id == chosen
            Surface(
              onClick = { chosen = f.id },
              shape = RoundedCornerShape(12.dp),
              color = if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(Modifier.padding(start = (12 + depth * 18).dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                  Icon(AppIcons.FolderNav, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(f.name, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
        if (naming) {
          OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text(if (chosen == null) "New folder" else "New folder inside the chosen one") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          )
          Row {
            TextButton(onClick = { naming = false }) { Text("Cancel") }
            TextButton(onClick = {
              onNew(chosen, name)
              naming = false
              name = ""
            }, enabled = name.isNotBlank()) { Text("Make it") }
          }
        } else {
          TextButton(onClick = { naming = true }) {
            Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("New folder")
          }
        }
      }
    },
    confirmButton = { Button(onClick = { chosen?.let(onPick) }, enabled = chosen != null) { Text("Choose") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
