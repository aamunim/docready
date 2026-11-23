package com.docready.aamunim.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import com.docready.aamunim.data.Draft
import com.docready.aamunim.data.summary
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docready.aamunim.data.model.LocalFolder
import com.docready.aamunim.docReady
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.IconTile
import com.docready.aamunim.ui.components.SectionLabel
import com.docready.aamunim.ui.theme.DocReadyColors
import com.docready.aamunim.ui.theme.SectionLabelStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  onScan: () -> Unit,
  onPhoto: () -> Unit,
  onSignature: () -> Unit,
  onPdfTools: () -> Unit,
  onSplit: () -> Unit,
  onPresets: () -> Unit,
  onNewPreset: () -> Unit,
  onStudents: () -> Unit = {},
  onCheckSize: () -> Unit = {},
  onDraft: (Draft) -> Unit = {},
  onPreset: (String) -> Unit,
  onFolder: (String) -> Unit,
  onAccounts: () -> Unit,
) {
  val app = LocalContext.current.docReady
  val presets by app.presets.presets.collectAsState()
  val folders by app.folders.folders.collectAsState()
  val drafts by app.drafts.drafts.collectAsState()
  // (read once per visit; drafts older than 30 days are removed on the first read)
  LaunchedEffect(Unit) { app.drafts.refresh() }
  val scope = rememberCoroutineScope()
  var deleting by remember { mutableStateOf<Draft?>(null) }
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)),
              contentAlignment = Alignment.Center,
            ) {
              Icon(AppIcons.DocCheck, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("DocReady", style = MaterialTheme.typography.titleLarge)
          }
        },
        actions = {
          IconButton(onClick = onAccounts) { Icon(AppIcons.Account, contentDescription = "Accounts and settings") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
      )
    },
  ) { padding ->
    Column(
      Modifier
        .padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)
        .padding(bottom = 24.dp),
    ) {
      // the accounts are optional: Phase 1 works without any sign-in
      Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AccountPill(AppIcons.Drive, "Google Drive", Modifier.weight(1f), onAccounts)
        AccountPill(AppIcons.SignIn, "ITTC sign-in", Modifier.weight(1f), onAccounts)
      }

      if (drafts.isNotEmpty()) {
        SectionLabel("Continue where you left off")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          for (d in drafts.take(5)) DraftRow(d, onOpen = { onDraft(d) }, onDelete = { deleting = d })
        }
        if (drafts.size > 5) {
          Text("${drafts.size - 5} more: open their tools to continue them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
      }

      SectionLabel("Make it ready")
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionCard(AppIcons.Scan, "Scan document", "Certificates, marksheets", primary = true, onClick = onScan, modifier = Modifier.weight(1f))
        ActionCard(AppIcons.Person, "Photo", "Face crop, background", primary = false, onClick = onPhoto, modifier = Modifier.weight(1f))
      }
      Spacer(Modifier.height(10.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionCard(AppIcons.Signature, "Signature", "Cut and clean", primary = false, onClick = onSignature, modifier = Modifier.weight(1f))
        ActionCard(AppIcons.Pages, "PDF tools", "Merge, compress, NID", primary = false, onClick = onPdfTools, modifier = Modifier.weight(1f))
      }
      Spacer(Modifier.height(10.dp))
      SplitCard(onSplit)
      Spacer(Modifier.height(10.dp))
      val students by app.students.students.collectAsState()
      WideCard(
        AppIcons.Person,
        "Students",
        if (students.isEmpty()) "Each applicant's papers together, with a checklist" else "${students.size} student${if (students.size == 1) "" else "s"} · checklist, files, ZIP",
        onStudents,
      )
      Spacer(Modifier.height(10.dp))
      WideCard(AppIcons.Gauge, "Check size", "Any photo or PDF: KB, pages, pixels; make it fit a limit", onCheckSize)

      Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Presets", style = SectionLabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onPresets) { Text("All presets") }
      }
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (presets.isEmpty()) {
          // no presets yet: the way to make the first one
          Surface(
            onClick = onNewPreset,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.height(40.dp),
          ) {
            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
              Text("+ Create a preset", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
          }
        }
        for (p in presets.take(8)) {
          Surface(
            onClick = { onPreset(p.id) },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.height(40.dp),
          ) {
            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
              Text(p.name, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
          }
        }
      }

      SectionLabel("Recent folders")
      if (folders.isEmpty()) {
        Text(
          "Files you save on this phone are kept in folders (Downloads / DocReady). They appear here.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          for (f in folders.take(6)) FolderRow(f) { onFolder(f.name) }
        }
      }
    }
  }
  deleting?.let { d ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      title = { Text("Delete this draft?") },
      text = { Text("${d.title}\n${d.summary()}\n\nIts unfinished pages are thrown away. Files you already saved are not touched.") },
      confirmButton = {
        TextButton(onClick = {
          deleting = null
          scope.launch { app.drafts.discard(d.key) }
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun AccountPill(icon: ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = modifier.height(44.dp),
  ) {
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Column {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Coming soon", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
      }
    }
  }
}

@Composable
private fun ActionCard(
  icon: ImageVector,
  title: String,
  subtitle: String,
  primary: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val container = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
  val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(18.dp),
    color = container,
    border = if (primary) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = modifier.height(112.dp),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
      Icon(icon, contentDescription = null, tint = if (primary) content else MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
      Column {
        Text(title, color = content, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
          subtitle,
          color = if (primary) content.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 12.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun FolderRow(folder: LocalFolder, onClick: () -> Unit) {
  val extra = DocReadyColors.extra
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
  ) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      IconTile(AppIcons.Folder, extra.folderIcon, extra.folderContainer)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(folder.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val n = folder.files.size
        Text(
          "$n file${if (n == 1) "" else "s"} · on this phone",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** "Split by preset": one merged PDF → each paper a form asks for */
@Composable
private fun SplitCard(onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth().height(72.dp),
  ) {
    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(AppIcons.Copy, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text("Split by preset", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
          "One PDF of all papers → photo, signature, each certificate",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 12.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** A full-width action card (Students) */
@Composable
private fun WideCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth().height(72.dp),
  ) {
    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/** An unfinished piece of work: tap to continue it */
@Composable
private fun DraftRow(d: Draft, onOpen: () -> Unit, onDelete: () -> Unit) {
  Surface(
    onClick = onOpen,
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.primaryContainer,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(AppIcons.Clock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(d.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(d.summary(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
      }
      IconButton(onClick = onDelete) { Icon(AppIcons.Close, contentDescription = "Delete draft", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
  }
}
