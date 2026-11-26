package com.docready.aamunim.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's line icons (24 x 24, 2-unit strokes), drawn from the mockups' SVG paths, so no
 * icon library is needed. Tinted by Icon(tint = ...).
 */
object AppIcons {
  private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

  private fun icon(name: String, vararg paths: String): ImageVector {
    val b = ImageVector.Builder(
      name = name,
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    )
    for (p in paths) {
      b.addPath(
        pathData = addPathNodes(p),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
      )
    }
    return b.build()
  }

  val DocCheck = icon("doc_check", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "m9 14 2 2 4-4")
  val Scan = icon(
    "scan",
    "M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2",
    "M7 12h10",
  )
  val Person = icon("person", circle(12f, 9f, 4f), "M5 21a7 7 0 0 1 14 0")
  val Account = icon("account", circle(12f, 8f, 4f), "M4 21a8 8 0 0 1 16 0")
  val Signature = icon("signature", "M3 17c3-6 5-6 6-2s3 4 5-1 4-3 7 1", "M3 21h18")
  val Pages = icon(
    "pages",
    "M6 3h8a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
    "M8 21h10a2 2 0 0 0 2-2V7",
  )
  val Folder = icon("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
  val Drive = icon("drive", "M7 4h10l5 9-5 7H7l-5-7z")
  val SignIn = icon("sign_in", "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4", "m10 17 5-5-5-5", "M15 12H3")
  val Share = icon("share", circle(18f, 5f, 3f), circle(6f, 12f, 3f), circle(18f, 19f, 3f), "m8.6 13.5 6.8 4M15.4 6.5l-6.8 4")
  val Search = icon("search", circle(11f, 11f, 7f), "m20 20-3.5-3.5")
  val Check = icon("check", "m5 12 5 5 9-10")
  val Warning = icon("warning", "M12 3 2 20h20z", "M12 10v4", "M12 17h.01")
  val Back = icon("back", "M15 18 9 12l6-6")
  val Forward = icon("forward", "m9 18 6-6-6-6")
  val Undo = icon("undo", "M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
  val Redo = icon("redo", "m15 14 5-5-5-5", "M20 9H9.5a5.5 5.5 0 0 0 0 11H13")
  val Up = icon("up", "m18 15-6-6-6 6")
  val Down = icon("down", "m6 9 6 6 6-6")
  val Add = icon("add", "M12 5v14M5 12h14")
  val Close = icon("close", "M6 6l12 12M18 6 6 18")
  val Delete = icon("delete", "M4 7h16", "M10 11v6M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
  val Edit = icon("edit", "M4 20h4L19 9l-4-4L4 16z")
  val Copy = icon("copy", "M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1z", "M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1")
  val Camera = icon(
    "camera",
    "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z",
    circle(12f, 13f, 3.5f),
  )
  val Image = icon(
    "image",
    "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
    "m3 16 5-5 4 4 3-3 6 6",
    circle(15.5f, 8.5f, 1.5f),
  )
  // ---- new navigation and screens (from the v2 mockups)
  val Home = icon("home", "m3 11 9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")
  val People = icon("people", circle(9f, 8f, 3.5f), "M2.5 20a6.5 6.5 0 0 1 13 0", "M16 4.5a3.5 3.5 0 0 1 0 7M18 14a6.5 6.5 0 0 1 3.5 6")
  val PresetList = icon("preset_list", "M4 6h16M4 12h10M4 18h7", circle(18f, 17f, 3f))
  val Settings = icon(
    "settings",
    circle(12f, 12f, 3f),
    "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
  )
  val FolderNav = icon("folder_nav", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
  val ScanFrame = icon("scan_frame", "M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2", "M4 12h16")
  val Chevron = icon("chevron", "m9 6 6 6-6 6")
  val SplitTiles = icon("split_tiles", "M4.5 4h4a1.5 1.5 0 0 1 1.5 1.5v13a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 3 18.5v-13A1.5 1.5 0 0 1 4.5 4z", "M15.5 4h4A1.5 1.5 0 0 1 21 5.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 14 9.5v-4A1.5 1.5 0 0 1 15.5 4z", "M15.5 13h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z")
  val Bars = icon("bars", "M4 20h16", "M7 16V9M12 16V5M17 16v-4")
  val DocLines = icon("doc_lines", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5M9 13h6M9 17h4")
  val SignWave = icon("sign_wave", "M3 17c3-1 4-8 7-8s1 7 4 7 3-4 7-4", "M3 21h18")
  val Qr = icon("qr", "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4z", "M14 14h2v2h-2zM18 14h2M14 18h2v2M18 18h2v2h-2")
  val Sort = icon("sort", "M7 4v16M4 7l3-3 3 3", "M17 20V4M14 17l3 3 3-3")
  val Grid = icon("grid", "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z")
  val ListLines = icon("list_lines", "M8 6h13M8 12h13M8 18h13", "M3.5 6h.01M3.5 12h.01M3.5 18h.01")
  val Star = icon("star", "m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9z")
  val More = icon("more", "M12 5h.01M12 12h.01M12 19h.01")
  val Spark = icon("spark", "M12 3v4M12 17v4M3 12h4M17 12h4", "m6 6 2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6")

  /** OCR: the page's text */
  val TextIcon = icon("text", "M4 6V4h16v2", "M12 4v16", "M9 20h6")
  val Gauge = icon("gauge", "M4 18a8 8 0 1 1 16 0", "m12 18 4-5", "M4 21h16")
  val Clock = icon("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
  val Rotate = icon("rotate", "M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")
  val Open = icon("open", "M14 4h6v6", "M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
}
