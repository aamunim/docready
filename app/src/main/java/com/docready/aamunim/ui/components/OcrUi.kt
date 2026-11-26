package com.docready.aamunim.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.docready.aamunim.data.model.Student
import com.docready.aamunim.docReady
import com.docready.aamunim.imaging.Fact
import com.docready.aamunim.imaging.FactKind

/** "Searchable PDF": the pages' English text kept as an invisible layer (select, search, copy) */
@Composable
fun SearchableSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Searchable PDF", style = MaterialTheme.typography.bodyMedium)
      Text(
        "English text read on the phone and kept under the pictures: it can be searched and copied. A little bigger; slower to make.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

/** The student with [kind] set to [value] */
private fun Student.with(kind: FactKind, value: String): Student = when (kind) {
  FactKind.NAME -> copy(name = value.take(80))
  FactKind.ROLL -> copy(roll = value.take(40))
  FactKind.REG -> copy(registration = value.take(40))
  FactKind.DOB -> copy(dob = value.take(20))
}

private fun Student.has(kind: FactKind, value: String): Boolean = when (kind) {
  FactKind.NAME -> name.equals(value, ignoreCase = true)
  FactKind.ROLL -> roll == value
  FactKind.REG -> registration == value
  FactKind.DOB -> dob == value
}

/**
 * What the page says about the student, as chips: "Use as name: Rahim Uddin?". Nothing changes
 * until a chip is tapped; one the student already has is not offered.
 */
@Composable
fun FactChips(studentId: String, facts: List<Fact>) {
  val app = LocalContext.current.docReady
  val students by app.students.students.collectAsState()
  val s = students.firstOrNull { it.id == studentId } ?: return
  val open = facts.filter { !s.has(it.kind, it.value) }
  if (open.isEmpty()) return
  Text(
    "Found on the page (check before you use it):",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 10.dp),
  )
  Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    for (f in open) {
      AssistChip(
        onClick = { app.students.get(studentId)?.let { app.students.save(it.with(f.kind, f.value)) } },
        label = { Text("Use as ${f.kind.title}: ${f.value}?") },
      )
    }
  }
}
