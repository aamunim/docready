package com.docready.aamunim.intake

import java.io.File

/** Where a page came from: it decides the page's first look in the tool */
enum class PageKind {
  /** Google's document scanner (already straightened and cropped): Magic */
  SCANNED,

  /** A photo of paper (camera, gallery, a file): Magic, and its corners looked for */
  PHOTO,

  /** A PDF's page: kept as it was made (Beautify available) */
  PDF,
}

/** One page handed to a tool: a picture file (a PDF's page already made into one) */
class HandoffPage(val file: File, val kind: PageKind)

/**
 * Pages chosen for a preset item (the page picker), handed to the tool that makes the item's
 * file (Photo, Signature, Scan). Kept in memory only for the moment of the hand-over; the tool's
 * own draft keeps them after that.
 */
object Handoff {
  private val pending = HashMap<String, List<HandoffPage>>()

  @Synchronized
  fun put(key: String, pages: List<HandoffPage>) {
    pending[key] = pages
  }

  /** The pages for [key] (then gone), or null */
  @Synchronized
  fun take(key: String): List<HandoffPage>? = pending.remove(key)
}
