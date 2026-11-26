package com.docready.aamunim.data.model

/**
 * How many pages a preset item takes. Pure Kotlin (tested on the JVM).
 *
 * Items keep [PresetItem.minPages] / [PresetItem.maxPages]; unset means at most one page.
 * Older presets had a free-text "pages" hint ("1", "1-2", "1–2"): it is read once into the
 * numbers ([migrate]), and still written for older versions of the app ([hintOf]).
 */
object PageLimits {
  /** "Any number": as many pages as the app takes at once */
  const val ANY = 150

  private val exactly = Regex("^\\s*(\\d{1,3})\\s*(?:pages?|p)?\\s*$", RegexOption.IGNORE_CASE)
  private val range = Regex("^\\s*(\\d{1,3})\\s*(?:-|–|—|to)\\s*(\\d{1,3})\\s*(?:pages?|p)?\\s*$", RegexOption.IGNORE_CASE)
  private val upTo = Regex("^\\s*(?:up\\s*to|max(?:imum)?|at\\s*most|≤|<=)\\s*(\\d{1,3})\\s*(?:pages?|p)?\\s*$", RegexOption.IGNORE_CASE)
  private val any = Regex("^\\s*(?:any|all|no\\s*limit)\\s*$", RegexOption.IGNORE_CASE)

  /** An old hint as (min, max) pages; null when it can't be understood */
  fun parseHint(hint: String?): Pair<Int?, Int>? {
    val h = hint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    exactly.matchEntire(h)?.let { m ->
      val n = m.groupValues[1].toInt().coerceIn(1, ANY)
      return n to n
    }
    range.matchEntire(h)?.let { m ->
      var a = m.groupValues[1].toInt().coerceIn(1, ANY)
      var b = m.groupValues[2].toInt().coerceIn(1, ANY)
      if (a > b) a = b.also { b = a }
      return a to b
    }
    upTo.matchEntire(h)?.let { m -> return null to m.groupValues[1].toInt().coerceIn(1, ANY) }
    if (any.matches(h)) return null to ANY
    return null
  }

  /** What an old hint becomes: understood, else at most one page */
  fun migrate(hint: String?): Pair<Int?, Int> = parseHint(hint) ?: (null to 1)

  /** The hint older versions of the app show ("2", "1-3", "up to 3", "any") */
  fun hintOf(min: Int?, max: Int?): String {
    val hi = (max ?: 1).coerceIn(1, ANY)
    val lo = (min ?: 1).coerceIn(1, hi)
    return when {
      hi >= ANY && lo <= 1 -> "any"
      lo == hi -> "$hi"
      lo <= 1 -> "up to $hi"
      else -> "$lo-$hi"
    }
  }

  /**
   * The pages an item takes: a photo or a signature, and a document made as a picture (JPG /
   * PNG), take exactly one; a PDF document its own limits (at most one when unset)
   */
  fun range(kind: ItemKind, format: OutputFormat, minPages: Int?, maxPages: Int?): IntRange {
    if (kind != ItemKind.DOCUMENT || format != OutputFormat.PDF) return 1..1
    val hi = (maxPages ?: 1).coerceIn(1, ANY)
    val lo = (minPages ?: 1).coerceIn(1, hi)
    return lo..hi
  }

  /** "1 page", "exactly 2 pages", "up to 3 pages", "any number of pages" */
  fun describe(r: IntRange): String = when {
    r.last == 1 -> "1 page"
    r.last >= ANY && r.first <= 1 -> "any number of pages"
    r.first == r.last -> "exactly ${r.last} pages"
    r.first <= 1 -> "up to ${r.last} pages"
    else -> "${r.first}–${r.last} pages"
  }

  /** The picker's heading for [r] */
  fun chooseTitle(r: IntRange): String = when {
    r.last == 1 -> "Choose 1 page"
    r.first == r.last -> "Choose ${r.last} pages"
    else -> "Choose up to ${r.last} pages"
  }

  /** The picker is needed: more pages came in than the item takes */
  fun needsPicking(total: Int, r: IntRange): Boolean = total > r.last

  /** Whether one more page may be selected */
  fun canSelectMore(selected: Int, r: IntRange): Boolean = selected < r.last

  /** "Use selected" is allowed: at least the minimum, at most the maximum */
  fun selectionOk(selected: Int, r: IntRange): Boolean = selected >= minOf(r.first, r.last) && selected in 1..r.last
}

/** The pages this item takes (see [PageLimits.range]) */
val PresetItem.pageRange: IntRange get() = PageLimits.range(kind, format, minPages, maxPages)

/** Older presets: the "pages" hint read into numbers (documents only); others as they are */
fun PresetItem.withPageNumbers(): PresetItem {
  if (kind != ItemKind.DOCUMENT) return if (pagesHint == null && minPages == null && maxPages == null) this else copy(pagesHint = null, minPages = null, maxPages = null)
  if (maxPages != null || pagesHint == null) return if (pagesHint == null) this else copy(pagesHint = null)
  val (lo, hi) = PageLimits.migrate(pagesHint)
  return copy(minPages = lo, maxPages = hi, pagesHint = null)
}
