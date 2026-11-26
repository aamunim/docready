package com.docready.aamunim.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docready.aamunim.data.NameContext
import com.docready.aamunim.data.Naming
import com.docready.aamunim.data.NamingSettings
import com.docready.aamunim.docReady
import com.docready.aamunim.ui.components.ChoiceChips
import com.docready.aamunim.ui.components.DocTopBar
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.components.VSpace

/** Settings → File names: the pattern every saved file is named by, with a live preview */
@Composable
fun FileNamesScreen(onBack: () -> Unit) {
  val repo = LocalContext.current.docReady.settings
  val s by repo.naming.collectAsState()
  fun set(n: NamingSettings) = repo.saveNaming(n)
  Scaffold(
    topBar = {
      DocTopBar("File names", onBack, actions = { TextButton(onClick = { set(NamingSettings()) }) { Text("Reset") } })
    },
  ) { padding ->
    Column(
      Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
      Text(
        "Every saved or shared file is named by this pattern. A preset can have its own (in the preset's editor).",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      VSpace(12)
      OutlinedTextField(
        value = s.pattern,
        onValueChange = { set(s.copy(pattern = it.take(80))) },
        label = { Text("Pattern") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in Naming.TOKENS) AssistChip(onClick = { set(s.copy(pattern = (s.pattern + t).take(80))) }, label = { Text(t) })
      }
      Text(
        "{name} student · {roll} roll / reg · {item} the file (photo, SSC certificate…) · {preset} · {date} · {seq} 1, 2, 3…",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
      )

      SectionLabel("Date")
      ChoiceChips(Naming.DATE_FORMATS, s.dateFormat, { it }, { set(s.copy(dateFormat = it)) })
      SectionLabel("Between the parts")
      ChoiceChips(Naming.SEPARATORS, s.separator, { if (it == " ") "space" else it }, { set(s.copy(separator = it)) })
      Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Bangla names → English letters", fontWeight = FontWeight.SemiBold)
          Text("For portals that refuse Bangla file names (রহিম → Rahim)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = s.transliterate, onCheckedChange = { set(s.copy(transliterate = it)) })
      }

      SectionLabel("Preview")
      Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
          val sample = NameContext(preset = "B.Ed admission", student = "রহিম উদ্দিন", roll = "12345")
          Text(Naming.apply(s, sample, "photo_300x300", 1) + ".jpg", fontWeight = FontWeight.SemiBold)
          Text(Naming.apply(s, sample, "ssc_certificate", 2) + ".pdf", fontWeight = FontWeight.SemiBold)
          Text(Naming.apply(s, NameContext(), "scan_3_pages", 1) + ".pdf", style = MaterialTheme.typography.bodySmall)
          Text(
            "A name already in the folder gets “ (2)”, “ (3)”… Characters a file name can't hold are left out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
          )
        }
      }
    }
  }
}
