package com.docready.aamunim.imaging.beauty

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class MarkKind { FINGER, HOLE, STAPLE }

/**
 * What the analysis found on one page. Boxes and lines are parts of the page as it was analysed
 * (after the user's corners / turns / angle, before Beautify's own straightening, upside-down
 * turn and border trim), so switching those on or off never moves them.
 */
class BeautyFindings(
  val deskew: Float,
  /** A large tilt (over 15°): offered, never applied by itself */
  val askAngle: Float?,
  /** The page may be upside down: offered, never applied by itself */
  val maybeUpsideDown: Boolean,
  /** A uniform dark band at the edges: offered (outlined), never applied by itself */
  val trim: FBox?,
  val glare: List<FBox>,
  /** Some glare is pure white: what was under it can't come back */
  val glareLoss: Boolean,
  val shadow: Boolean,
  val stains: Boolean,
  val yellow: Boolean,
  val folds: List<Triple<Boolean, Float, Float>>,
  val edges: List<Pair<MarkKind, FBox>>,
  val blurry: Boolean,
  val noisy: Boolean,
  val faded: Boolean,
  val protect: List<FBox>,
)

/**
 * Looks at a page (at most about 1000 px, as the page will be framed) and says which Beautify
 * steps it needs. Pure Kotlin: no Android types.
 */
object BeautyAnalyzer {
  fun analyze(page: Px, signature: Boolean = false): BeautyFindings {
    // 1. straightening by the text lines (a large tilt is only offered); upside down only offered
    val skew = textAngle(page)
    var askAngle: Float? = null
    var deskew = 0f
    if (abs(skew) > 15f) askAngle = skew else if (abs(skew) >= 0.3f) deskew = skew
    val level = if (deskew != 0f) BeautySteps.rotate(page, deskew) else page
    val maybeUpsideDown = !signature && upsideDown(level)
    // 10a. a uniform black border (only offered)
    val trimBox = if (signature) null else blackBorder(page)

    // everything else on the page as analysed (the frame the boxes are kept in)
    val p = page
    val protect = if (signature) emptyList() else protectedAreas(p)
    val protectPx = protect.map { it.toPx(p.w, p.h) }
    val grid = PaperGrid.of(p)
    val paper = grid.global()
    val paperLum = (paper[0] * 299 + paper[1] * 587 + paper[2] * 114) / 1000f

    val (glare, glareLoss) = if (signature) Pair(emptyList(), false) else glareAreas(p, paperLum)
    val (shadow, stains) = if (signature) Pair(false, false) else unevenPaper(grid, paper, protectPx)
    val chroma = max(paper[0], max(paper[1], paper[2])) - min(paper[0], min(paper[1], paper[2]))
    val yellow = !signature && (chroma > 10f || paperLum < 228f)
    val folds = if (signature) emptyList() else foldLines(p, grid)
    val edges = if (signature) emptyList() else edgeMarks(p, paper)
    val sharpness = sharpness(p)
    return BeautyFindings(
      deskew = deskew,
      askAngle = askAngle,
      maybeUpsideDown = maybeUpsideDown,
      trim = trimBox,
      glare = glare,
      glareLoss = glareLoss,
      shadow = shadow,
      stains = stains,
      yellow = yellow,
      folds = folds,
      edges = edges,
      blurry = sharpness < 60.0,
      noisy = noisy(p, grid, protectPx),
      faded = faded(p, grid, protectPx),
      protect = protect,
    )
  }

  // ------------------------------------------------------------------ 1. text angle

  /**
   * The turn (degrees, as Android's Matrix.postRotate) that makes the text lines level: the ink
   * is projected onto rows at each candidate angle; level lines give the sharpest row profile.
   * 0 when there is no clear text.
   */
  fun textAngle(p: Px): Float {
    val pts = inkPoints(p) ?: return 0f
    val r = (sqrt((p.w * p.w + p.h * p.h).toDouble()) / 2 + 2).toInt()
    var best = 0f
    var bestScore = -1.0
    var a = -45f
    while (a <= 45f) {
      val s = profileScore(pts, a, r)
      if (s > bestScore) {
        bestScore = s
        best = a
      }
      a += 1f
    }
    val coarse = best
    a = coarse - 1f
    while (a <= coarse + 1f) {
      val s = profileScore(pts, a, r)
      if (s > bestScore) {
        bestScore = s
        best = a
      }
      a += 0.1f
    }
    // a clear winner only (no lines: every angle scores about the same)
    val flat = profileScore(pts, best + 20f, r)
    if (bestScore < flat * 1.15) return 0f
    return (Math.round(best * 10f) / 10f)
  }

  /** The ink's points (x, y from the middle), at most about 30 000 */
  private fun inkPoints(p: Px): FloatArray? {
    // (Otsu's ink class is "at most th"; a page with no writing has hardly any of it)
    val th = otsu(p)
    if (th > 200) return null
    var count = 0
    for (c in p.data) if (Px.lum(c) <= th) count++
    val total = p.w * p.h
    if (count < total / 400 || count > total * 2 / 5) return null
    val stride = max(1, count / 30_000)
    val out = FloatArray((count / stride + 1) * 2)
    var k = 0
    var seen = 0
    val cx = p.w / 2f
    val cy = p.h / 2f
    for (y in 0 until p.h) for (x in 0 until p.w) {
      if (Px.lum(p.data[y * p.w + x]) <= th) {
        if (seen++ % stride == 0 && k + 1 < out.size) {
          out[k++] = x - cx
          out[k++] = y - cy
        }
      }
    }
    return out.copyOf(k)
  }

  private fun profileScore(pts: FloatArray, degrees: Float, r: Int): Double {
    val rad = Math.toRadians(degrees.toDouble())
    val s = sin(rad).toFloat()
    val c = cos(rad).toFloat()
    val bins = IntArray(2 * r + 1)
    var i = 0
    while (i < pts.size) {
      val y = pts[i] * s + pts[i + 1] * c
      val b = (y + r).toInt()
      if (b in bins.indices) bins[b]++
      i += 2
    }
    var sum = 0.0
    for (v in bins) sum += v.toDouble() * v
    return sum
  }

  /** Otsu's threshold between ink and paper */
  fun otsu(p: Px): Int {
    val hist = IntArray(256)
    for (c in p.data) hist[Px.lum(c)]++
    val n = p.w * p.h.toDouble()
    var sum = 0.0
    for (t in 0 until 256) sum += t * hist[t].toDouble()
    var sumB = 0.0
    var wB = 0.0
    var best = 0.0
    var th = 128
    for (t in 0 until 256) {
      wB += hist[t]
      if (wB == 0.0) continue
      val wF = n - wB
      if (wF == 0.0) break
      sumB += t * hist[t]
      val mB = sumB / wB
      val mF = (sum - sumB) / wF
      val between = wB * wF * (mB - mF) * (mB - mF)
      if (between > best) {
        best = between
        th = t
      }
    }
    return th
  }

  /**
   * A guess that the page is upside down (only ever offered to the user). Per text line:
   * a headline row far denser than the rest (Bangla's matra) belongs at the line's top; else
   * (Latin) the sparse tails around the dense core count — ascenders above outnumber
   * descenders below. Most of at least 6 lines must point the same way.
   */
  fun upsideDown(p: Px): Boolean {
    val th = otsu(p)
    if (th > 200) return false
    val rows = IntArray(p.h)
    for (y in 0 until p.h) for (x in 0 until p.w) if (Px.lum(p.data[y * p.w + x]) <= th) rows[y]++
    val peak = rows.maxOrNull() ?: return false
    if (peak == 0) return false
    val cut = max(1, peak / 15)
    var upright = 0
    var flipped = 0
    var y = 0
    while (y < p.h) {
      if (rows[y] < cut) {
        y++
        continue
      }
      val start = y
      while (y < p.h && rows[y] >= cut) y++
      val end = y
      val len = end - start
      if (len < 6) continue
      var top = start
      for (k in start until end) if (rows[k] > rows[top]) top = k
      val sorted = (start until end).map { rows[it] }.sorted()
      val median = sorted[sorted.size / 2]
      if (rows[top] > median * 2.5f) {
        // a headline: at the top when upright
        val pos = (top - start + 0.5f) / len
        if (pos < 0.35f) upright++ else if (pos > 0.65f) flipped++
      } else {
        val core = rows[top] / 2
        var c0 = start
        while (c0 < end && rows[c0] < core) c0++
        var c1 = end - 1
        while (c1 > c0 && rows[c1] < core) c1--
        var above = 0
        var below = 0
        for (k in start until c0) above += rows[k]
        for (k in c1 + 1 until end) below += rows[k]
        if (above > below * 1.4f + 2) upright++ else if (below > above * 1.4f + 2) flipped++
      }
    }
    val votes = upright + flipped
    return votes >= 6 && flipped >= votes * 0.75f
  }

  // ------------------------------------------------------------------ 10. border, glare

  /** A dark band all along a side (a scanner lid, a table): cut away, at most 20 % a side */
  fun blackBorder(p: Px): FBox? {
    fun dark(line: Int, horizontal: Boolean): Boolean {
      val n = if (horizontal) p.w else p.h
      var darkCount = 0
      for (k in 0 until n) {
        val c = if (horizontal) p.data[line * p.w + k] else p.data[k * p.w + line]
        if (Px.lum(c) < 60) darkCount++
      }
      // a border is uniformly dark (a title banner has light letters in it: never cut)
      if (darkCount <= n * 0.9) return false
      var lightest = 0
      for (k in 0 until n) {
        val c = if (horizontal) p.data[line * p.w + k] else p.data[k * p.w + line]
        val l = Px.lum(c)
        if (l > lightest) lightest = l
      }
      return lightest < 120
    }
    /** Mostly paper: the band really ends where the page starts (not inside a dark banner) */
    fun paper(line: Int, horizontal: Boolean): Boolean {
      val n = if (horizontal) p.w else p.h
      var darkCount = 0
      for (k in 0 until n) {
        val c = if (horizontal) p.data[line * p.w + k] else p.data[k * p.w + line]
        if (Px.lum(c) < 100) darkCount++
      }
      return darkCount < n * 0.3
    }
    var top = 0
    while (top < p.h / 5 && dark(top, true)) top++
    if (top >= p.h || !paper(min(p.h - 1, top + 1), true)) top = 0
    var bottom = p.h
    while (bottom > p.h * 4 / 5 && dark(bottom - 1, true)) bottom--
    if (bottom <= 0 || !paper(max(0, bottom - 2), true)) bottom = p.h
    var left = 0
    while (left < p.w / 5 && dark(left, false)) left++
    if (left >= p.w || !paper(min(p.w - 1, left + 1), false)) left = 0
    var right = p.w
    while (right > p.w * 4 / 5 && dark(right - 1, false)) right--
    if (right <= 0 || !paper(max(0, right - 2), false)) right = p.w
    val minCut = max(2, min(p.w, p.h) / 200)
    val l = if (left >= minCut) left + 1 else 0
    val t = if (top >= minCut) top + 1 else 0
    val r = if (p.w - right >= minCut) right - 1 else p.w
    val b = if (p.h - bottom >= minCut) bottom - 1 else p.h
    if (l == 0 && t == 0 && r == p.w && b == p.h) return null
    return FBox(l.toFloat() / p.w, t.toFloat() / p.h, r.toFloat() / p.w, b.toFloat() / p.h)
  }

  /** Blown-out patches on a card that is not white itself (laminated glare); true = some is pure white */
  fun glareAreas(p: Px, paperLum: Float): Pair<List<FBox>, Boolean> {
    if (paperLum >= 238f) return Pair(emptyList(), false)
    val cell = max(4, max(p.w, p.h) / 120)
    val gw = (p.w + cell - 1) / cell
    val gh = (p.h + cell - 1) / cell
    val mask = BooleanArray(gw * gh)
    for (gy in 0 until gh) for (gx in 0 until gw) {
      var bright = 0
      var n = 0
      for (y in gy * cell until min(p.h, (gy + 1) * cell)) for (x in gx * cell until min(p.w, (gx + 1) * cell)) {
        val c = p.data[y * p.w + x]
        if (Px.lum(c) >= 248 && Px.sat(c) < 0.08f) bright++
        n++
      }
      mask[gy * gw + gx] = bright * 2 > n
    }
    val minCells = max(2, gw * gh / 500)
    val found = blobs(mask, gw, gh, minCells)
    var pure = 0
    var inside = 0
    val boxes = found.map { bl ->
      val b = IBox(bl.box.left * cell, bl.box.top * cell, min(p.w, bl.box.right * cell), min(p.h, bl.box.bottom * cell))
      for (y in b.top until b.bottom) for (x in b.left until b.right) {
        val c = p.data[y * p.w + x]
        inside++
        if (Px.r(c) >= 254 && Px.g(c) >= 254 && Px.b(c) >= 254) pure++
      }
      grow(b, p.w, p.h, 0.1f)
    }
    return Pair(boxes, inside > 0 && pure > inside * 0.4)
  }

  private fun grow(b: IBox, w: Int, h: Int, part: Float): FBox {
    val gx = b.width * part
    val gy = b.height * part
    return FBox(
      max(0f, (b.left - gx) / w),
      max(0f, (b.top - gy) / h),
      min(1f, (b.right + gx) / w),
      min(1f, (b.bottom + gy) / h),
    )
  }

  // ------------------------------------------------------------------ 3, 4. paper colour

  /** Photos, logos, colour stamps: colourful or solid dark areas bigger than writing */
  fun protectedAreas(p: Px): List<FBox> {
    val cell = max(6, max(p.w, p.h) / 32)
    val gw = (p.w + cell - 1) / cell
    val gh = (p.h + cell - 1) / cell
    val mask = BooleanArray(gw * gh)
    for (gy in 0 until gh) for (gx in 0 until gw) {
      var colourful = 0
      var dark = 0
      var n = 0
      for (y in gy * cell until min(p.h, (gy + 1) * cell) step 2) for (x in gx * cell until min(p.w, (gx + 1) * cell) step 2) {
        val c = p.data[y * p.w + x]
        val l = Px.lum(c)
        if (Px.sat(c) > 0.35f && l < 230) colourful++
        if (l < 80) dark++
        n++
      }
      mask[gy * gw + gx] = n > 0 && (colourful > n * 0.3 || dark > n * 0.45)
    }
    return blobs(mask, gw, gh)
      .filter { it.box.width >= 2 && it.box.height >= 2 }
      .map { bl ->
        FBox(
          max(0f, ((bl.box.left - 1) * cell).toFloat() / p.w),
          max(0f, ((bl.box.top - 1) * cell).toFloat() / p.h),
          min(1f, ((bl.box.right + 1) * cell).toFloat() / p.w),
          min(1f, ((bl.box.bottom + 1) * cell).toFloat() / p.h),
        )
      }
  }

  /**
   * Uneven paper: a large spread of brightness → shadows; colour patches that differ from the
   * usual paper colour (not only darker) → stains. Protected areas are left out.
   */
  fun unevenPaper(grid: PaperGrid, paper: FloatArray, protect: List<IBox>): Pair<Boolean, Boolean> {
    val lums = ArrayList<Float>()
    var stainCells = 0
    var cells = 0
    val pl = max(1f, (paper[0] * 299 + paper[1] * 587 + paper[2] * 114) / 1000f)
    for (gy in 0 until grid.gh) for (gx in 0 until grid.gw) {
      val cx = gx * grid.cell + grid.cell / 2
      val cy = gy * grid.cell + grid.cell / 2
      if (protect.any { it.contains(cx, cy) }) continue
      val i = gy * grid.gw + gx
      val l = max(1f, grid.lumAt(i))
      lums.add(l)
      cells++
      // the colour's shape (each channel over its brightness), not its brightness
      val d = max(
        abs(grid.r[i] / l - paper[0] / pl),
        max(abs(grid.g[i] / l - paper[1] / pl), abs(grid.b[i] / l - paper[2] / pl)),
      )
      if (d > 0.06f && l > pl - 80f) stainCells++
    }
    if (lums.isEmpty()) return Pair(false, false)
    lums.sort()
    val spread = lums[lums.size * 9 / 10] - lums[lums.size / 10]
    return Pair(spread > 22f, stainCells > cells * 0.02)
  }

  // ------------------------------------------------------------------ 2. folds

  /**
   * Folds: rows (or columns) where much of the line is faint shading — a little darker than the
   * paper, much lighter than writing — and hardly any writing; thin bands only.
   */
  fun foldLines(p: Px, grid: PaperGrid): List<Triple<Boolean, Float, Float>> {
    val paper = FloatArray(3)
    val faintRow = IntArray(p.h)
    val inkRow = IntArray(p.h)
    val faintCol = IntArray(p.w)
    val inkCol = IntArray(p.w)
    // barely darker than the paper: the soft sides of a fold
    val slightRow = IntArray(p.h)
    val slightCol = IntArray(p.w)
    for (y in 0 until p.h) for (x in 0 until p.w) {
      grid.at(x, y, paper)
      val pl = max(1f, (paper[0] * 299 + paper[1] * 587 + paper[2] * 114) / 1000f)
      val q = Px.lum(p.data[y * p.w + x]) / pl
      if (q in 0.70f..0.94f) {
        faintRow[y]++
        faintCol[x]++
      } else if (q > 0.94f && q < 0.985f) {
        slightRow[y]++
        slightCol[x]++
      } else if (q < 0.6f) {
        inkRow[y]++
        inkCol[x]++
      }
    }
    val out = ArrayList<Triple<Boolean, Float, Float>>()
    fun bands(faint: IntArray, ink: IntArray, slight: IntArray, across: Int, horizontal: Boolean) {
      val n = faint.size
      val margin = n * 3 / 100
      var i = margin
      while (i < n - margin) {
        if (faint[i] < across * 0.28 || ink[i] > across * 0.10) {
          i++
          continue
        }
        val start = i
        while (i < n - margin && faint[i] >= across * 0.28 && ink[i] <= across * 0.10) i++
        val len = i - start
        // a fold is soft shading wider than a printed rule, fading out at its sides (a ruled line
        // or a table border has crisp edges)
        fun shaded(k: Int) = k in 0 until n && faint[k] + slight[k] >= across * 0.25
        val soft = shaded(start - 1) && shaded(i)
        if (len >= max(3, n / 300) && len <= max(4, n / 50) && soft) {
          out.add(Triple(horizontal, (start + i) / 2f / n, max(1f, len / 2f) / n))
        }
      }
    }
    bands(faintRow, inkRow, slightRow, p.w, true)
    bands(faintCol, inkCol, slightCol, p.h, false)
    return out.take(6)
  }

  // ------------------------------------------------------------------ 8. edges

  /** Fingers (skin tone touching the edge), punch holes (dark rounds), staples (thin dark marks), near the edges only */
  fun edgeMarks(p: Px, paper: FloatArray): List<Pair<MarkKind, FBox>> {
    val band = max(4, min(p.w, p.h) * 7 / 100)
    val small = min(p.w, p.h)
    fun inBand(b: IBox) = b.left < band || b.top < band || b.right > p.w - band || b.bottom > p.h - band
    val out = ArrayList<Pair<MarkKind, FBox>>()

    val skin = BooleanArray(p.w * p.h)
    val dark = BooleanArray(p.w * p.h)
    for (y in 0 until p.h) for (x in 0 until p.w) {
      val near = x < band || y < band || x >= p.w - band || y >= p.h - band
      if (!near) continue
      val c = p.data[y * p.w + x]
      val r = Px.r(c)
      val g = Px.g(c)
      val b = Px.b(c)
      val yy = 0.299f * r + 0.587f * g + 0.114f * b
      val cb = 128 - 0.168736f * r - 0.331264f * g + 0.5f * b
      val cr = 128 + 0.5f * r - 0.418688f * g - 0.081312f * b
      // skin tone, and clearly not the paper's own colour (aged paper can be skin-coloured)
      val fromPaper = abs(r - paper[0]) + abs(g - paper[1]) + abs(b - paper[2])
      skin[y * p.w + x] = yy > 50 && cr in 135f..175f && cb in 80f..125f && fromPaper > 45f
      dark[y * p.w + x] = yy < 110 && Px.sat(c) < 0.35f
    }
    for (bl in blobs(skin, p.w, p.h, max(20, p.w * p.h / 500))) {
      if (bl.touchesBorder && inBand(bl.box) && bl.count < p.w * p.h / 7) out.add(MarkKind.FINGER to toF(bl.box, p))
    }
    for (bl in blobs(dark, p.w, p.h, 6)) {
      val b = bl.box
      if (!inBand(b)) continue
      val bw = b.width.toFloat()
      val bh = b.height.toFloat()
      val longSide = max(bw, bh)
      val shortSide = min(bw, bh)
      val fill = bl.count / (bw * bh)
      val round = shortSide / longSide > 0.7f && fill in 0.55f..0.92f && longSide in small * 0.01f..small * 0.06f
      val staple = longSide / max(1f, shortSide) > 3f && longSide in small * 0.03f..small * 0.10f && shortSide < small * 0.012f
      if (round && !bl.touchesBorder) out.add(MarkKind.HOLE to toF(b, p))
      else if (staple) out.add(MarkKind.STAPLE to toF(b, p))
    }
    return out.take(12)
  }

  private fun toF(b: IBox, p: Px) = FBox(b.left.toFloat() / p.w, b.top.toFloat() / p.h, b.right.toFloat() / p.w, b.bottom.toFloat() / p.h)

  // ------------------------------------------------------------------ 5, 6. ink, noise, blur

  /** Sharpness as DocTools.quality measures it (under ~60 looks blurry), on a ≤ 500 px copy */
  fun sharpness(p: Px): Double {
    val step = max(1, max(p.w, p.h) / 500)
    val w = p.w / step
    val h = p.h / step
    if (w < 3 || h < 3) return 100.0
    val g = IntArray(w * h) { i -> Px.lum(p.data[(i / w) * step * p.w + (i % w) * step]) }
    var sum = 0.0
    var sumSq = 0.0
    var n = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
      val i = y * w + x
      val lap = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
      sum += lap
      sumSq += lap * lap
      n++
    }
    val mean = sum / max(1, n)
    return sqrt(max(0.0, sumSq / max(1, n) - mean * mean)) * 4
  }

  /** Grain / speckles on the paper */
  fun noisy(p: Px, grid: PaperGrid, protect: List<IBox> = emptyList()): Boolean {
    val paper = FloatArray(3)
    var flat = 0
    var grainy = 0
    for (y in 1 until p.h - 1 step 2) for (x in 1 until p.w - 1 step 2) {
      if (protect.any { it.contains(x, y) }) continue // (a photo's texture is not grain)
      grid.at(x, y, paper)
      val pl = (paper[0] * 299 + paper[1] * 587 + paper[2] * 114) / 1000f
      val l = Px.lum(p.data[y * p.w + x])
      val n1 = Px.lum(p.data[y * p.w + x - 1])
      val n2 = Px.lum(p.data[y * p.w + x + 1])
      val n3 = Px.lum(p.data[(y - 1) * p.w + x])
      val n4 = Px.lum(p.data[(y + 1) * p.w + x])
      // paper all round (the edge of writing is not grain)
      if (l < pl - 40f || n1 < pl - 40f || n2 < pl - 40f || n3 < pl - 40f || n4 < pl - 40f) continue
      flat++
      val m = (n1 + n2 + n3 + n4) / 4f
      if (abs(l - m) > 12f) grainy++
    }
    return flat > 0 && grainy > flat * 0.03
  }

  /** Faded ink: there is writing, but it is not much darker than the paper */
  fun faded(p: Px, grid: PaperGrid, protect: List<IBox> = emptyList()): Boolean {
    val paper = FloatArray(3)
    val ratios = ArrayList<Float>()
    for (y in 0 until p.h step 2) for (x in 0 until p.w step 2) {
      if (protect.any { it.contains(x, y) }) continue // (a photo's shades are not ink)
      grid.at(x, y, paper)
      val pl = max(1f, (paper[0] * 299 + paper[1] * 587 + paper[2] * 114) / 1000f)
      val q = Px.lum(p.data[y * p.w + x]) / pl
      if (q < 0.8f) ratios.add(q)
    }
    if (ratios.size < p.w * p.h / 4 / 500) return false
    ratios.sort()
    return ratios[ratios.size / 2] > 0.5f
  }
}
