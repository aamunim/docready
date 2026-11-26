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
import com.docready.aamunim.ui.components.AppBottomBar
import com.docready.aamunim.ui.components.AppIcons
import com.docready.aamunim.ui.components.NavTab
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import com.docready.aamunim.intake.Intake
import com.docready.aamunim.intake.IntakeTarget
import com.docready.aamunim.ui.screens.AccountsScreen
import com.docready.aamunim.ui.screens.CheckSizeScreen
import com.docready.aamunim.ui.screens.FileNamesScreen
import com.docready.aamunim.ui.screens.FolderScreen
import com.docready.aamunim.ui.screens.HomeScreen
import com.docready.aamunim.ui.screens.SearchScreen
import com.docready.aamunim.ui.screens.ItemChooserScreen
import com.docready.aamunim.ui.screens.ItemUploadScreen
import com.docready.aamunim.ui.screens.PdfMode
import com.docready.aamunim.ui.screens.PdfToolsScreen
import com.docready.aamunim.ui.screens.PhotoScreen
import com.docready.aamunim.ui.screens.PresetDetailScreen
import com.docready.aamunim.ui.screens.PresetEditScreen
import com.docready.aamunim.ui.screens.PresetQrImportScreen
import com.docready.aamunim.ui.screens.PresetQrScreen
import com.docready.aamunim.ui.screens.PresetsScreen
import com.docready.aamunim.ui.screens.ScanScreen
import com.docready.aamunim.ui.screens.SignatureScreen
import com.docready.aamunim.ui.screens.StudentEditScreen
import com.docready.aamunim.ui.screens.StudentScreen
import com.docready.aamunim.ui.screens.StudentsScreen

/** The screens' routes (Navigation Compose, string routes) */
object Routes {
  const val HOME = "home"
  const val PHOTO = "photo?preset={preset}&item={item}&student={student}&draft={draft}"
  const val SIGNATURE = "signature?preset={preset}&item={item}&student={student}"
  const val SCAN = "scan?preset={preset}&item={item}&student={student}&draft={draft}"
  const val PDF = "pdf?preset={preset}&item={item}&student={student}&draft={draft}"
  const val STUDENTS = "students"
  const val STUDENT = "student/{id}"
  const val STUDENT_EDIT = "student-edit/{id}"
  const val SPLIT = "split?draft={draft}"
  const val CHECK_SIZE = "check-size"
  const val PRESETS = "presets"
  const val PRESET = "preset/{id}"
  const val PRESET_EDIT = "preset-edit/{id}"
  const val ACCOUNTS = "accounts"
  const val FILE_NAMES = "file-names"
  const val FOLDER = "folder/{name}"
  const val SEARCH = "search"
  const val PRESET_QR = "preset-qr/{id}"
  const val ITEM_ADD = "item-add?preset={preset}&item={item}&student={student}&draft={draft}"
  const val ITEM_CHOOSE = "item-choose"
  const val PRESET_QR_SCAN = "preset-qr-scan"

  fun presetQr(id: String) = "preset-qr/${Uri.encode(id)}"

  /** A tool, optionally for a preset's item and a student (the result is added to them) */
  fun tool(base: String, presetId: String? = null, item: Int = -1, student: String? = null, draft: Boolean = false): String {
    val args = listOfNotNull(
      presetId?.let { "preset=${Uri.encode(it)}&item=$item" },
      student?.let { "student=${Uri.encode(it)}" },
      if (draft) "draft=1" else null,
    )
    return if (args.isEmpty()) base else base + "?" + args.joinToString("&")
  }

  fun student(id: String) = "student/${Uri.encode(id)}"
  fun studentEdit(id: String) = "student-edit/${Uri.encode(id)}"

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
  navArgument("student") {
    type = NavType.StringType
    nullable = true
    defaultValue = null
  },
  draftArg,
)

/** "1": opened from the Drafts list (the tool's draft is brought back without asking) */
private val draftArg get() = navArgument("draft") {
  type = NavType.StringType
  nullable = true
  defaultValue = null
}

private fun android.os.Bundle?.resume(): Boolean = this?.getString("draft") == "1"

/** The bottom navigation's tabs, in order (a tab can be inserted, e.g. Folders second) */
private val navTabs: List<NavTab> = listOf(
  NavTab("home", Routes.HOME, "Home", AppIcons.Home),
  NavTab("students", Routes.STUDENTS, "Students", AppIcons.People),
  NavTab("presets", Routes.PRESETS, "Presets", AppIcons.PresetList),
  NavTab("settings", Routes.ACCOUNTS, "Settings", AppIcons.Settings),
)

@Composable
fun DocReadyApp() {
  val nav = rememberNavController()
  // (a second quick tap on Back must never pop Home and leave a blank screen)
  val back: () -> Unit = {
    if (nav.previousBackStackEntry != null) nav.popBackStack()
  }
  val entry by nav.currentBackStackEntryAsState()
  val current = entry?.destination?.route
  val tabs = navTabs
  val onTab = tabs.any { it.route == current }
  val goTab: (NavTab) -> Unit = { t ->
    nav.navigate(t.route) {
      popUpTo(Routes.HOME) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }
  fun studentArg(a: android.os.Bundle?): String? = a?.getString("student")

  Scaffold(
    bottomBar = { if (onTab) AppBottomBar(tabs, current, goTab) },
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
  ) { inner ->
  NavHost(navController = nav, startDestination = Routes.HOME, modifier = Modifier.padding(inner).consumeWindowInsets(inner)) {
    composable(Routes.HOME) {
      HomeScreen(
        onScan = { nav.navigate(Routes.tool("scan")) },
        onPhoto = { nav.navigate(Routes.tool("photo")) },
        onSignature = { nav.navigate(Routes.tool("signature")) },
        onPdfTools = { nav.navigate(Routes.tool("pdf")) },
        onSplit = { nav.navigate("split") },
        onPresets = { goTab(tabs.first { it.key == "presets" }) },
        onNewPreset = { nav.navigate(Routes.presetEdit("new")) },
        onPreset = { nav.navigate(Routes.preset(it)) },
        onFolder = { nav.navigate(Routes.folder(it)) },
        onAccounts = { goTab(tabs.first { it.key == "settings" }) },
        onStudents = { nav.navigate(Routes.STUDENTS) },
        onCheckSize = { nav.navigate(Routes.CHECK_SIZE) },
        onSearch = { nav.navigate(Routes.SEARCH) },
        onDraft = { d ->
          // the tool brings its draft back at once ("scan", "pdf", "split" or "photo")
          nav.navigate(Routes.tool(if (d.tool == "pick") "item-add" else d.tool, d.presetId, d.itemIndex, d.studentId, draft = true))
        },
      )
    }
    composable(Routes.PHOTO, arguments = toolArgs) { entry ->
      val args = entry.arguments
      PhotoScreen(args?.getString("preset"), args?.getInt("item") ?: -1, back, onNewPreset = { nav.navigate(Routes.presetEdit("new")) }, studentId = studentArg(args), resume = args.resume())
    }
    composable(Routes.SIGNATURE, arguments = toolArgs) { entry ->
      val args = entry.arguments
      SignatureScreen(args?.getString("preset"), args?.getInt("item") ?: -1, back, onNewPreset = { nav.navigate(Routes.presetEdit("new")) }, studentId = studentArg(args))
    }
    composable(Routes.SCAN, arguments = toolArgs) { entry ->
      val args = entry.arguments
      ScanScreen(args?.getString("preset"), args?.getInt("item") ?: -1, back, onNewPreset = { nav.navigate(Routes.presetEdit("new")) }, studentId = studentArg(args), resume = args.resume())
    }
    composable(Routes.PDF, arguments = toolArgs) { entry ->
      val args = entry.arguments
      PdfToolsScreen(
        back,
        onNewPreset = { nav.navigate(Routes.presetEdit("new")) },
        presetId = args?.getString("preset"),
        itemIndex = args?.getInt("item") ?: -1,
        studentId = studentArg(args),
        resume = args.resume(),
      )
    }
    composable(Routes.STUDENTS) {
      StudentsScreen(null, onOpen = { nav.navigate(Routes.student(it)) }, onNew = { nav.navigate(Routes.studentEdit("new")) })
    }
    composable(Routes.STUDENT) { entry ->
      val id = entry.arguments?.getString("id") ?: ""
      StudentScreen(
        id,
        onBack = back,
        onEdit = { nav.navigate(Routes.studentEdit(id)) },
        onMake = { tool, presetId, i -> nav.navigate(Routes.tool(tool, presetId, i, id)) },
        onOpenFolder = { nav.navigate(Routes.folder(it)) },
      )
    }
    composable(Routes.STUDENT_EDIT) { entry ->
      val id = entry.arguments?.getString("id") ?: "new"
      StudentEditScreen(id, onBack = back, onSaved = { saved ->
        // a new student opens on their page; an edit goes back to it
        if (id == "new") {
          nav.popBackStack()
          nav.navigate(Routes.student(saved))
        } else {
          back()
        }
      })
    }
    // (a destination of its own: Share / Open with can land in it while PDF tools is open)
    composable(Routes.SPLIT, arguments = listOf(draftArg)) { entry ->
      PdfToolsScreen(back, startMode = PdfMode.SPLIT, onNewPreset = { nav.navigate(Routes.presetEdit("new")) }, resume = entry.arguments.resume())
    }
    composable(Routes.CHECK_SIZE) { CheckSizeScreen(back) }
    composable(Routes.SEARCH) {
      SearchScreen(back, onPreset = { nav.navigate(Routes.preset(it)) }, onStudent = { nav.navigate(Routes.student(it)) })
    }
    composable(Routes.PRESETS) {
      PresetsScreen(
        onBack = null,
        onOpen = { nav.navigate(Routes.preset(it)) },
        onNew = { nav.navigate(Routes.presetEdit("new")) },
        onScanQr = { nav.navigate(Routes.PRESET_QR_SCAN) },
      )
    }
    composable(Routes.ITEM_ADD, arguments = toolArgs) { entry ->
      val args = entry.arguments
      val presetId = args?.getString("preset")
      val item = args?.getInt("item") ?: -1
      val student = studentArg(args)
      ItemUploadScreen(presetId, item, student, back, onHandOff = { tool ->
        // the picker gives way to the item's own tool (Back from it goes where Add was tapped)
        nav.popBackStack()
        nav.navigate(Routes.tool(tool, presetId, item, student))
      }, resume = args.resume())
    }
    composable(Routes.ITEM_CHOOSE) {
      ItemChooserScreen({
        Intake.dismissIf(IntakeTarget.ITEM)
        back()
      }) { presetId, i ->
        nav.popBackStack()
        nav.navigate(Routes.tool("item-add", presetId, i))
      }
    }
    composable(Routes.PRESET_QR) { entry -> PresetQrScreen(entry.arguments?.getString("id") ?: "", back) }
    composable(Routes.PRESET_QR_SCAN) {
      PresetQrImportScreen(back, onImported = { id ->
        nav.popBackStack()
        nav.navigate(Routes.preset(id))
      })
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
        onShowQr = { nav.navigate(Routes.presetQr(it)) },
        onAdd = { presetId, i -> nav.navigate(Routes.tool("item-add", presetId, i)) },
      )
    }
    composable(Routes.PRESET_EDIT) { entry ->
      PresetEditScreen(entry.arguments?.getString("id") ?: "new", onDone = back)
    }
    composable(Routes.ACCOUNTS) { AccountsScreen(null, onFileNames = { nav.navigate(Routes.FILE_NAMES) }) }
    composable(Routes.FILE_NAMES) { FileNamesScreen(back) }
    composable(Routes.FOLDER) { entry -> FolderScreen(entry.arguments?.getString("name") ?: "", back) }
  }
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
      IntakeTarget.PDF_TOOLS -> nav.navigate(Routes.tool("pdf")) { launchSingleTop = true }
      IntakeTarget.SPLIT -> nav.navigate("split") { launchSingleTop = true }
      IntakeTarget.PHOTO -> nav.navigate(Routes.tool("photo")) { launchSingleTop = true }
      IntakeTarget.SIGNATURE -> nav.navigate(Routes.tool("signature")) { launchSingleTop = true }
      IntakeTarget.ITEM -> nav.navigate(Routes.ITEM_CHOOSE) { launchSingleTop = true }
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
            TextButton(onClick = { Intake.choose(IntakeTarget.ITEM) }) { Text("Use for a preset item…") }
          }
        }
      },
      confirmButton = { TextButton(onClick = { Intake.dismiss() }) { Text(if (r.files.isEmpty()) "OK" else "Cancel") } },
    )
  }
}
