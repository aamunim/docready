package com.docready.aamunim.imaging

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// Pure Kotlin (no Android): what is done with a page's recognised text. Tested off the phone.

/** A recognised word and its box, as parts (0-1) of the page it was read on */
data class OcrWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A page's text: its lines (each its words), in reading order as the recogniser gives them */
class OcrPage(val lines: List<List<OcrWord>>) {
  val words: List<OcrWord> get() = lines.flatten()

  /** The text, a line per line */
  val text: String get() = lines.joinToString("\n") { l -> l.joinToString(" ") { it.text } }

  val isEmpty: Boolean get() = lines.isEmpty()

  companion object {
    val Empty = OcrPage(emptyList())
  }
}

/**
 * A searchable PDF's text layer: each word drawn invisibly (text render mode 3) over its place on
 * the page picture, in Helvetica stretched to the word's width, so selecting / searching finds it
 * where it is seen. The picture itself is unchanged.
 */
object PdfText {
  /** The font object every page's text refers to as /F1 */
  const val FONT = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"

  /**
   * The content-stream part for [words] on a picture drawn at [dx], [dy] (PDF points, from the
   * bottom left), [dw] × [dh] in size. "" when there are no words.
   */
  fun layer(words: List<OcrWord>, dw: Double, dh: Double, dx: Double, dy: Double): String {
    if (words.isEmpty()) return ""
    val sb = StringBuilder("BT 3 Tr\n")
    for (w in words) {
      val text = pdfString(w.text)
      if (text.isEmpty()) continue
      val x = dx + w.left * dw
      val yTop = dy + (1.0 - w.top) * dh
      val yBottom = dy + (1.0 - w.bottom) * dh
      val h = yTop - yBottom
      val width = (w.right - w.left) * dw
      if (h <= 0.5 || width <= 0.5) continue
      // a word's box runs from above its capitals to below its descenders: about one em
      val size = h
      val baseline = yBottom + h * 0.22
      // Helvetica's letters are about half an em wide: stretched to the word's real width
      val natural = w.text.length * 0.5 * size
      val tz = (100.0 * width / max(0.01, natural)).coerceIn(10.0, 1000.0)
      sb.append(String.format(Locale.US, "/F1 %.1f Tf %.0f Tz 1 0 0 1 %.2f %.2f Tm (%s) Tj\n", size, tz, x, baseline, text))
    }
    sb.append("ET\n")
    return sb.toString()
  }

  /** A PDF literal string's inside: ( ) \ escaped; only characters the font's encoding has */
  fun pdfString(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
      when {
        c == '(' || c == ')' || c == '\\' -> sb.append('\\').append(c)
        c.code in 32..126 || c.code in 160..255 -> sb.append(c)
        c == '‘' || c == '’' -> sb.append('\'')
        c == '“' || c == '”' -> sb.append('"')
        c == '–' || c == '—' -> sb.append('-')
        c.isWhitespace() -> sb.append(' ')
        else -> sb.append('?')
      }
    }
    return sb.toString()
  }
}

/** What a page's text says about the applicant: offered as chips, never filled in without a tap */
enum class FactKind(val title: String) { NAME("name"), ROLL("roll"), REG("registration no."), DOB("date of birth") }

data class Fact(val kind: FactKind, val value: String)

object PageFacts {
  private const val SEP = "\\s*[:：.\\-–]?\\s*"
  private val NO = "(?:\\s*(?:No\\.?|Number|#))?"

  private val roll = Regex("(?i)\\bRoll$NO$SEP([0-9][0-9 ]{2,14}[0-9])")
  private val reg = Regex("(?i)\\b(?:Registration|Regn\\.?|Reg\\.?)$NO$SEP([0-9][0-9 /\\-]{2,18}[0-9])")
  private val dob = Regex(
    "(?i)\\b(?:Date\\s+of\\s+Birth|D\\.?\\s*O\\.?\\s*B\\.?|Birth\\s*Date)$SEP" +
      "([0-9]{1,2}\\s*[-/.]\\s*[0-9]{1,2}\\s*[-/.]\\s*[0-9]{2,4}" +
      "|[0-9]{1,2}(?:st|nd|rd|th)?\\s+[A-Za-z]{3,9}\\.?,?\\s+[0-9]{4}" +
      "|[A-Za-z]{3,9}\\.?\\s+[0-9]{1,2}(?:st|nd|rd|th)?,?\\s+[0-9]{4})",
  )
  private const val VALUE = "([A-Za-z][A-Za-z.'’ ]{1,48}[A-Za-z.])"

  /** The applicant's own name, said so: "Name of the Candidate", "Student's Name"… (preferred) */
  private val ownName = Regex(
    "(?i)(?:Name\\s+of\\s+(?:the\\s+)?(?:Candidate|Student|Examinee|Applicant)|" +
      "(?:Candidate|Student|Examinee|Applicant)['’]?s?\\s+Name)$SEP$VALUE",
  )

  /** Just "Name": used only when no line says whose name it is */
  private val bareName = Regex("(?i)(?:^|[^A-Za-z'’])Name$SEP$VALUE")

  /** Lines where a bare "Name" is the name of something else (the exam, a post, the school…) */
  private val notTheirs = Regex(
    "(?i)father|mother|guardian|husband|parent|spouse|wife|school|college|institut|board|centre|center|" +
      "signature|exam|post|subject|bank|branch|course|degree|university|company|organi[sz]ation|office",
  )

  /** Words a name read from a form never is (labels running on after it) */
  private val nameStop = Regex("(?i)\\s+(?:roll|reg|registration|father|mother|guardian|husband|date|dob|class|group|session|sex|gender|id|no|name)\\b.*$")

  /** The facts found in [text] (at most one of each kind: the first found), in kind order */
  fun find(text: String): List<Fact> {
    val out = LinkedHashMap<FactKind, String>()
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    for (line in lines) {
      if (FactKind.ROLL !in out) roll.find(line)?.let { out[FactKind.ROLL] = it.groupValues[1].replace(" ", "") }
      if (FactKind.REG !in out) reg.find(line)?.let { out[FactKind.REG] = it.groupValues[1].replace(" ", "") }
      if (FactKind.DOB !in out) dob.find(line)?.let { out[FactKind.DOB] = it.groupValues[1].replace(Regex("\\s+"), " ").trim() }
    }
    // the name: a line saying it is the applicant's first; a bare "Name" only if none does
    // (a parent's name later on the same line is cut off by the labels that follow the name)
    val own = lines.asSequence().mapNotNull { nameIn(ownName, it) }.firstOrNull()
    val bare = own ?: lines.asSequence().filter { !notTheirs.containsMatchIn(it) }.mapNotNull { nameIn(bareName, it) }.firstOrNull()
    bare?.let { out[FactKind.NAME] = it }
    return FactKind.entries.mapNotNull { k -> out[k]?.let { Fact(k, it) } }
  }

  /** The name after the label, cleaned; null for "of Examination…" or nothing name-like */
  private fun nameIn(r: Regex, line: String): String? {
    for (m in r.findAll(line)) {
      val v = m.groupValues[1].replace(nameStop, "").trim().trimEnd('.').trim()
      if (v.startsWith("of ", ignoreCase = true) || v.equals("of", ignoreCase = true)) continue
      if (v.length >= 3 && v.count { it.isLetter() } >= 3) return titleCase(v)
    }
    return null
  }

  /** "RAHIM UDDIN" → "Rahim Uddin" (names on certificates are often in capitals) */
  private fun titleCase(s: String): String =
    if (s.any { it.isLowerCase() }) s else s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
}

/** Words for telling papers apart: a page's own words and an item's keywords */
object Keywords {
  /** Words on nearly every form page: they tell nothing about which paper it is */
  private val STOP = setOf(
    "the", "and", "for", "with", "this", "that", "from", "has", "have", "was", "are", "his", "her", "its",
    "name", "date", "father", "mother", "page", "number", "roll", "reg", "registration", "signature",
    "government", "people", "republic", "bangladesh", "year", "group", "session", "board", "dhaka",
    "photo", "document", "copy", "file", "not", "any", "all", "who", "which", "under", "per",
  )

  /** A text's useful words: lower case, letters only, 3+ letters, common form words left out */
  fun tokens(text: String): List<String> =
    text.lowercase(Locale.US).split(Regex("[^a-z]+")).filter { it.length >= 3 && it !in STOP }

  /** A page's most frequent useful words (what is learned for an item when a page is given to it) */
  fun top(text: String, n: Int = 12): List<String> =
    tokens(text).groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
      .take(n).map { it.key }

  /**
   * How well a page's words match an item: each of the item's title words found counts 2 (the
   * title says what the paper is), each of the user's keywords 3, each learned word 1.
   */
  fun score(page: Set<String>, title: String, userKeywords: List<String>, learned: List<String>): Int {
    var s = 0
    for (t in tokens(title).toSet()) if (t in page) s += 2
    for (k in userKeywords.flatMap { tokens(it) }.toSet()) if (k in page) s += 3
    for (l in learned.toSet()) if (l in page) s += 1
    return s
  }
}

/**
 * Pictures that look alike: a difference hash (9 × 8 grey, each dot against its right
 * neighbour) and an average hash (8 × 8 against their mean), 64 bits each. Two pages are taken
 * as the same paper when both differ in at most [NEAR] bits.
 */
object PageHash {
  const val NEAR = 6

  data class Hash(val d: Long, val a: Long)

  /** [argb]: the picture's pixels, [w] × [h] */
  fun of(argb: IntArray, w: Int, h: Int): Hash {
    val g9 = grey(argb, w, h, 9, 8)
    var d = 0L
    for (y in 0 until 8) for (x in 0 until 8) {
      if (g9[y * 9 + x] > g9[y * 9 + x + 1]) d = d or (1L shl (y * 8 + x))
    }
    val g8 = grey(argb, w, h, 8, 8)
    val mean = g8.sum() / 64.0
    var a = 0L
    for (i in 0 until 64) if (g8[i] > mean) a = a or (1L shl i)
    return Hash(d, a)
  }

  fun distance(p: Hash, q: Hash): Int = max(java.lang.Long.bitCount(p.d xor q.d), java.lang.Long.bitCount(p.a xor q.a))

  fun near(p: Hash, q: Hash): Boolean = distance(p, q) <= NEAR

  /** For each page, the first earlier page it looks like (index), or null */
  fun duplicates(hashes: List<Hash?>): List<Int?> = hashes.mapIndexed { i, h ->
    if (h == null) null else (0 until i).firstOrNull { j -> hashes[j]?.let { near(it, h) } == true }
  }

  /** Mean grey of each cell of a [cw] × [ch] grid over the picture (box average, no aliasing) */
  private fun grey(argb: IntArray, w: Int, h: Int, cw: Int, ch: Int): DoubleArray {
    val sum = DoubleArray(cw * ch)
    val n = IntArray(cw * ch)
    for (y in 0 until h) {
      val cy = min(ch - 1, y * ch / h)
      for (x in 0 until w) {
        val c = argb[y * w + x]
        val cx = min(cw - 1, x * cw / w)
        val lum = ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000.0
        sum[cy * cw + cx] += lum
        n[cy * cw + cx]++
      }
    }
    return DoubleArray(cw * ch) { if (n[it] == 0) 0.0 else sum[it] / n[it] }
  }
}

/**
 * The shape of the ink on a page (grey picture): how much is dark, and how much of the page its
 * box covers (a few outlying dots left out). A signature: a little ink in one small area.
 */
data class InkShape(val darkShare: Float, val boxShare: Float, val midShare: Float = 0f) {
  /** Mostly white with one cluster of dark strokes, and almost no words */
  fun looksLikeSignature(words: Int): Boolean = words <= 6 && darkShare in 0.001f..0.08f && boxShare < 0.35f

  companion object {
    fun of(argb: IntArray, w: Int, h: Int): InkShape {
      // the paper: a bright level most pixels reach; ink: well below it
      val lum = IntArray(w * h) { val c = argb[it]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 }
      val hist = IntArray(256)
      for (v in lum) hist[v]++
      var acc = 0
      var paper = 255
      for (v in 255 downTo 0) {
        acc += hist[v]
        if (acc >= lum.size * 0.4) {
          paper = v
          break
        }
      }
      val dark = (paper * 0.6).toInt()
      val xs = ArrayList<Int>()
      val ys = ArrayList<Int>()
      for (y in 0 until h) for (x in 0 until w) if (lum[y * w + x] < dark) {
        xs.add(x)
        ys.add(y)
      }
      // photo tones: neither paper nor ink
      val mid = lum.count { it in dark..(paper * 0.85).toInt() }.toFloat() / lum.size
      if (xs.isEmpty()) return InkShape(0f, 0f, mid)
      xs.sort()
      ys.sort()
      fun at(l: List<Int>, q: Double) = l[min(l.size - 1, (l.size * q).toInt())]
      val bw = at(xs, 0.98) - at(xs, 0.02) + 1
      val bh = at(ys, 0.98) - at(ys, 0.02) + 1
      return InkShape(xs.size.toFloat() / (w * h), bw.toFloat() * bh / (w * h), mid)
    }
  }
}
