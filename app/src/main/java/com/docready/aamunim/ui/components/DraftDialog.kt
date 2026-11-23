package com.docready.aamunim.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.docready.aamunim.data.Draft
import com.docready.aamunim.data.DraftHost
import com.docready.aamunim.data.summary

/** "Continue where you left off?" for a tool's unfinished work */
@Composable
fun ContinueDraftDialog(draft: Draft, onContinue: () -> Unit, onDiscard: () -> Unit) {
  AlertDialog(
    // (no answer by tapping outside: the draft would be lost or kept by accident)
    onDismissRequest = {},
    title = { Text("Continue where you left off?") },
    text = { Text("${draft.title}\n${draft.summary()}\n\nDiscard throws this unfinished work away.") },
    confirmButton = { Button(onClick = onContinue) { Text("Continue") } },
    dismissButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
  )
}

/** The tool's earlier draft offered, if there is one */
@Composable
fun DraftOffer(host: DraftHost) {
  val d = host.offer ?: return
  ContinueDraftDialog(d, onContinue = { host.continueDraft() }, onDiscard = { host.discard() })
}
