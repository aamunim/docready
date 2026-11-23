package com.docready.aamunim.ui

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.docready.aamunim.data.model.ItemKind
import com.docready.aamunim.intake.Intake
import com.docready.aamunim.intake.IntakeTarget
import com.docready.aamunim.ui.screens.AccountsScreen
import com.docready.aamunim.ui.screens.FolderScreen
import com.docready.aamunim.ui.screens.HomeScreen
import com.docready.aamunim.ui.screens.PdfMode
import com.docready.aamunim.ui.screens.PdfToolsScreen
import com.docready.aamunim.ui.screens.PhotoScreen
import com.docready.aamunim.ui.screens.PresetDetailScreen
import com.docready.aamunim.ui.screens.PresetEditScreen
import com.docready.aamunim.ui.screens.PresetsScreen
import com.docready.aamunim.ui.screens.ScanScreen
import com.docready.aamunim.ui.screens.SignatureScreen

/** The screens' routes (Navigation Compose, string routes) */
object Routes {
  const val HOME = "home"
  const val PHOTO = "photo?preset={preset}&item={item}"
  const val SIGNATURE = "signature?preset={preset}&item={item}"
  const val SCAN = "scan?preset={preset}&item={item}"
  const val PDF = "pdf"
  const val SPLIT = "split"
  const val PRESETS = "presets"
  const val PRESET = "preset/{id}"
  const val PRESET_EDIT = "preset-edit/{id}"
  const val ACCOUNTS = "accounts"
  const val FOLDER = "folder/{name}"

  fun tool(base: String, presetId: String? = null, item: Int = -1): String =
    if (presetId == null) base else "$base?preset=${Uri.encode(presetId)}&item=$item"

  fun preset(id: String) = "preset/${Uri.encode(id)}"
  fun presetEdit(id: String) = "preset-edit/${Uri.encode(id)}"
  fun folder(name: String) = "folder/${Uri.encode(name)}"
}

private val toolArgs = listOf(
  navArgument("preset") {
    type = NavType.StringType
    nullable = true
    defaultValue = null
  },
  navArgument("item") {
    type = NavType.IntType
    defaultValue = -1
  },
)

@Composable
fun DocReadyApp() {
  val nav = rememberNavController()
  // (a second quick tap on Back must never pop Home and leave a blank screen)
  val back: () -> Unit = {
    if (nav.previousBackStackEntry != null) nav.popBackStack()
  }

  NavHost(navController = nav, startDestination = Routes.HOME) {
    composable(Routes.HOME) {
      HomeScreen(
        onScan = { nav.navigate(Routes.tool("scan")) },
        onPhoto = { nav.navigate(Routes.tool("photo")) },
        onSignature = { nav.navigate(Routes.tool("signature")) },
        onPdfTools = { nav.navigate(Routes.PDF) },
        onSplit = { nav.navigate(Routes.SPLIT) },
        onPresets = { nav.navigate(Routes.PRESETS) },
        onNewPreset = { nav.navigate(Routes.presetEdit("new")) },
        onPreset = { nav.navigate(Routes.preset(it)) },
        onFolder = { nav.navigate(Routes.folder(it)) },
        onAccounts = { nav.navigate(Routes.ACCOUNTS) },
      )
    }
    composable(Routes.PHOTO, arguments = toolArgs) { entry ->
      val args = entry.arguments
      PhotoScreen(args?.getString("preset"), args?.getInt("item") ?: -1, back, onNewPreset = { nav.navigate(Routes.presetEdit("new")) })
    }
    composable(Routes.SIGNATURE, arguments = toolArgs) { entry ->
      val args = entry.arguments
      SignatureScreen(args?.getString("preset"), args?.getInt("item") ?: -1, back, onNewPreset = { nav.navigate(Routes.presetEdit("new")) })
    }
    composable(Routes.SCAN, arguments = toolArgs) { entry ->
      val args = entry.arguments
      ScanScreen(args?.getString("preset"), args?.getInt("item") ?: -1, back, onNewPreset = { nav.navigate(Routes.presetEdit("new")) })
    }
    composable(Routes.PDF) { PdfToolsScreen(back, onNewPreset = { nav.navigate(Routes.presetEdit("new")) }) }
    // (a destination of its own: Share / Open with can land in it while PDF tools is open)
    composable(Routes.SPLIT) {
      PdfToolsScreen(back, startMode = PdfMode.SPLIT, onNewPreset = { nav.navigate(Routes.presetEdit("new")) })
    }
    composable(Routes.PRESETS) {
      PresetsScreen(
        onBack = back,
        onOpen = { nav.navigate(Routes.preset(it)) },
        onNew = { nav.navigate(Routes.presetEdit("new")) },
      )
    }
    composable(Routes.PRESET) { entry ->
      PresetDetailScreen(
        id = entry.arguments?.getString("id") ?: "",
        onBack = back,
        onMake = { preset, i ->
          val base = when (preset.items[i].kind) {
            ItemKind.PHOTO -> "photo"
            ItemKind.SIGNATURE -> "signature"
            ItemKind.DOCUMENT -> "scan"
          }
          nav.navigate(Routes.tool(base, preset.id, i))
        },
        onEdit = { nav.navigate(Routes.presetEdit(it)) },
      )
    }
    composable(Routes.PRESET_EDIT) { entry ->
      PresetEditScreen(entry.arguments?.getString("id") ?: "new", onDone = back)
    }
    composable(Routes.ACCOUNTS) { AccountsScreen(back) }
    composable(Routes.FOLDER) { entry -> FolderScreen(entry.arguments?.getString("name") ?: "", back) }
  }

  IntakeHandler(nav)
}

/** Files shared to the app: PDFs / several files → PDF tools; one photo → the user picks */
@Composable
private fun IntakeHandler(nav: NavHostController) {
  val request by Intake.request.collectAsState()
  val r = request ?: return
  LaunchedEffect(r) {
    when (r.target) {
      IntakeTarget.PDF_TOOLS -> nav.navigate(Routes.PDF) { launchSingleTop = true }
      IntakeTarget.SPLIT -> nav.navigate(Routes.SPLIT) { launchSingleTop = true }
      IntakeTarget.PHOTO -> nav.navigate(Routes.tool("photo")) { launchSingleTop = true }
      IntakeTarget.SIGNATURE -> nav.navigate(Routes.tool("signature")) { launchSingleTop = true }
      null -> Unit
    }
  }
  if (r.target == null) {
    AlertDialog(
      onDismissRequest = { Intake.dismiss() },
      title = { Text(if (r.files.isEmpty()) "Nothing to open" else "What do you want to make?") },
      text = {
        if (r.files.isEmpty()) {
          Text("DocReady takes photos and PDFs. ${r.skipped} shared file(s) could not be used.")
        } else {
          val onePhoto = r.files.size == 1 && !r.files[0].isPdf
          Column {
            if (r.skipped > 0) Text("${r.skipped} file(s) were not photos or PDFs and are left out.")
            if (onePhoto) {
              TextButton(onClick = { Intake.choose(IntakeTarget.PHOTO) }) { Text("Photo (face crop, background, size)") }
              TextButton(onClick = { Intake.choose(IntakeTarget.SIGNATURE) }) { Text("Signature (cut and clean)") }
            } else {
              TextButton(onClick = { Intake.choose(IntakeTarget.SPLIT) }) { Text("Split by preset (each paper of a form)") }
            }
            TextButton(onClick = { Intake.choose(IntakeTarget.PDF_TOOLS) }) { Text("PDF tools (merge, compress, NID)") }
          }
        }
      },
      confirmButton = { TextButton(onClick = { Intake.dismiss() }) { Text(if (r.files.isEmpty()) "OK" else "Cancel") } },
    )
  }
}
