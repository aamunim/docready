package com.docready.aamunim.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docready.aamunim.data.model.bytesText
import com.docready.aamunim.docReady
import com.docready.aamunim.save.ShareSaveTarget
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.BottomActions
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.IconTile
import com.docready.aamunim.ui.theme.DocReadyColors
import java.text.DateFormat
import java.util.Date

/** A local folder: the files saved in it on this phone, to open or share again */
@Composable
fun FolderScreen(name: String, onBack: () -> Unit) {
  val context = LocalContext.current
  val repo = context.docReady.folders
  val folders by repo.folders.collectAsState()
  val folder = folders.firstOrNull { it.name.equals(name, ignoreCase = true) }
  var confirmForget by remember { mutableStateOf(false) }
  val files = folder?.files?.sortedByDescending { it.savedAt } ?: emptyList()
  val dates = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

  fun tryRun(block: () -> Unit) {
    try {
      block()
    } catch (_: Exception) {
      Toast.makeText(context, "This file is no longer on the phone, or no app can open it.", Toast.LENGTH_LONG).show()
    }
  }

  Scaffold(
    topBar = {
      DocTopBar(
        name,
        onBack,
        subtitle = "Downloads / DocReady / $name",
        actions = { TextButton(onClick = { confirmForget = true }) { Text("Remove") } },
      )
    },
    bottomBar = {
      if (files.isNotEmpty()) {
        BottomActions {
          Button(
            onClick = {
              tryRun {
                val mimes = files.map { it.mime }.distinct()
                ShareSaveTarget.shareUris(context, files.map { Uri.parse(it.uri) }, if (mimes.size == 1) mimes[0] else "*/*")
              }
            },
            modifier = Modifier.weight(1f).height(52.dp),
          ) { Text("Share all (${files.size})") }
        }
      }
    },
  ) { padding ->
    LazyColumn(
      Modifier.padding(padding).fillMaxSize(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (files.isEmpty()) {
        item { Text("No files in this folder.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
      items(files, key = { it.uri }) { f ->
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
          Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(
              if (f.mime == "application/pdf") AppIcons.Pages else AppIcons.Image,
              DocReadyColors.extra.folderIcon,
              DocReadyColors.extra.folderContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(
                bytesText(f.bytes) + " · " + dates.format(Date(f.savedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(onClick = { tryRun { ShareSaveTarget.open(context, Uri.parse(f.uri), f.mime) } }) {
              Icon(AppIcons.Open, contentDescription = "Open ${f.name}")
            }
            IconButton(onClick = { tryRun { ShareSaveTarget.shareUris(context, listOf(Uri.parse(f.uri)), f.mime) } }) {
              Icon(AppIcons.Share, contentDescription = "Share ${f.name}")
            }
          }
        }
      }
    }
  }
  if (confirmForget) {
    AlertDialog(
      onDismissRequest = { confirmForget = false },
      title = { Text("Remove “$name” from the list?") },
      text = { Text("The files stay in Downloads / DocReady. Only the app's list forgets them.") },
      confirmButton = {
        TextButton(onClick = {
          confirmForget = false
          repo.forget(name)
          onBack()
        }) { Text("Remove") }
      },
      dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
    )
  }
}
