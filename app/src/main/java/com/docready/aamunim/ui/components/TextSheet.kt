package com.docready.aamunim.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** A page's text (OCR): selectable, Copy, Share. "" = no text was found. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSheet(text: String, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
      Text("Text on this page", style = MaterialTheme.typography.titleLarge)
      Text(
        "Read on the phone (English letters). Check names and numbers before you use them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
      )
      if (text.isBlank()) {
        Text("No English text was found on this page.", style = MaterialTheme.typography.bodyMedium)
      } else {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
          SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "Text copied", Toast.LENGTH_SHORT).show()
          }, modifier = Modifier.weight(1f)) { Text("Copy") }
          Button(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            try {
              context.startActivity(Intent.createChooser(send, "Share the text"))
            } catch (_: ActivityNotFoundException) {
              Toast.makeText(context, "No app to share text with.", Toast.LENGTH_LONG).show()
            }
          }, modifier = Modifier.weight(1f)) { Text("Share") }
        }
      }
    }
  }
}
