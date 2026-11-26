package com.docready.aamunim.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How saved files are named (Settings → File names) */
@Serializable
data class NamingSettings(
  /** Tokens: {name} {roll} {item} {preset} {date} {seq} */
  val pattern: String = "{item}",
  val dateFormat: String = "yyyy-MM-dd",
  /** Put between the parts and instead of spaces: "_", "-" or " " */
  val separator: String = "_",
  /** Bangla letters written with English letters (for portals that refuse them) */
  val transliterate: Boolean = false,
)

/** What the tokens stand for when a file is saved */
data class NameContext(
  val preset: String? = null,
  /** The preset's own pattern, used instead of the general one */
  val presetPattern: String? = null,
  val student: String? = null,
  val roll: String? = null,
)

/** The app's small settings, kept as JSON in its private files (settings.json) */
class SettingsRepository(context: Context) {
  private val file = File(context.filesDir, "settings.json")
  private val _naming = MutableStateFlow(read())
  val naming: StateFlow<NamingSettings> = _naming.asStateFlow()

  fun saveNaming(n: NamingSettings) {
    file.writeTextAtomic(AppJson.encodeToString(NamingSettings.serializer(), n))
    _naming.value = n
  }

  private fun read(): NamingSettings = try {
    if (file.exists()) AppJson.decodeFromString(NamingSettings.serializer(), file.readText()) else NamingSettings()
  } catch (_: Exception) {
    NamingSettings()
  }
}

object Naming {
  val TOKENS = listOf("{name}", "{roll}", "{item}", "{preset}", "{date}", "{seq}")
  val DATE_FORMATS = listOf("yyyy-MM-dd", "dd-MM-yyyy", "yyyyMMdd", "dd.MM.yyyy")
  val SEPARATORS = listOf("_", "-", " ")

  /**
   * A file's name (without extension) from the pattern: tokens filled, empty parts dropped,
   * spaces turned into the separator, Bangla optionally in English letters, only characters a file
   * name can hold. Empty → [item].
   */
  fun apply(s: NamingSettings, ctx: NameContext, item: String, seq: Int, date: Date = Date()): String {
    val pattern = ctx.presetPattern?.takeIf { it.isNotBlank() } ?: s.pattern.ifBlank { "{item}" }
    val dateText = try {
      SimpleDateFormat(s.dateFormat, Locale.US).format(date)
    } catch (_: Exception) {
      SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
    }
    var out = pattern
      .replace("{name}", ctx.student.orEmpty())
      .replace("{roll}", ctx.roll.orEmpty())
      .replace("{item}", item)
      .replace("{preset}", ctx.preset.orEmpty())
      .replace("{date}", dateText)
      .replace("{seq}", seq.toString())
    // English letters only: whatever the table left (joiners, a lone nukta, other scripts) goes
    if (s.transliterate) out = Bangla.latinOnly(Bangla.toLatin(out))
    val sep = s.separator.ifEmpty { "_" }
    // only characters a file name can hold; spaces → the separator; no doubled / dangling separators
    out = out.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), " ").trim()
    out = out.replace(Regex("\\s+"), sep)
    if (sep.isNotBlank()) {
      val q = Regex.escape(sep)
      out = out.replace(Regex("($q){2,}"), sep).replace(Regex("^($q)+|($q)+$"), "")
    }
    out = out.trim('.', ' ').take(100)
    val fallback = if (s.transliterate) Bangla.latinOnly(Bangla.toLatin(item)).trim() else item
    return out.ifEmpty { fallback.ifEmpty { "file" } }
  }
}

/**
 * Bangla script written with English letters (a simple, offline table; good for names on forms).
 * A consonant carries an "a" unless a vowel sign or hasanta follows, or it ends the word.
 */
object Bangla {
  private val independent = mapOf(
    'অ' to "o", 'আ' to "a", 'ই' to "i", 'ঈ' to "i", 'উ' to "u", 'ঊ' to "u", 'ঋ' to "ri",
    'এ' to "e", 'ঐ' to "oi", 'ও' to "o", 'ঔ' to "ou",
  )
  private val signs = mapOf(
    'া' to "a", 'ি' to "i", 'ী' to "i", 'ু' to "u", 'ূ' to "u", 'ৃ' to "ri",
    'ে' to "e", 'ৈ' to "oi", 'ো' to "o", 'ৌ' to "ou",
  )
  private val consonants = mapOf(
    'ক' to "k", 'খ' to "kh", 'গ' to "g", 'ঘ' to "gh", 'ঙ' to "ng",
    'চ' to "ch", 'ছ' to "chh", 'জ' to "j", 'ঝ' to "jh", 'ঞ' to "n",
    'ট' to "t", 'ঠ' to "th", 'ড' to "d", 'ঢ' to "dh", 'ণ' to "n",
    'ত' to "t", 'থ' to "th", 'দ' to "d", 'ধ' to "dh", 'ন' to "n",
    'প' to "p", 'ফ' to "f", 'ব' to "b", 'ভ' to "bh", 'ম' to "m",
    'য' to "j", 'র' to "r", 'ল' to "l", 'শ' to "sh", 'ষ' to "sh", 'স' to "s", 'হ' to "h",
    '\u09DC' to "r", '\u09DD' to "rh", '\u09DF' to "y", '\u09CE' to "t",
  )
  private val others = mapOf('ং' to "ng", 'ঃ' to "h", 'ঁ' to "", '।' to ".")
  private const val HASANTA = '্'

  private fun isBanglaLetter(c: Char?) = c != null && c in 'ঀ'..'৿' && c != '।' && !c.isDigit()

  /** Only characters safe in any file name, in English letters: A-Z a-z 0-9 space . _ ( ) - */
  fun latinOnly(text: String): String = text.replace(Regex("[^A-Za-z0-9 ._()-]"), "")

  fun toLatin(text: String): String {
    // (ড় ঢ় য় may come as a letter + nukta: the single letters, which Unicode never composes)
    // zero-width joiners (typed in some names) would hide a hasanta or a vowel sign: dropped first;
    // a lone nukta and the au length mark (U+09D7) have no sound of their own
    val s = text.replace("\u200C", "").replace("\u200D", "")
      .replace("\u09A1\u09BC", "\u09DC").replace("\u09A2\u09BC", "\u09DD").replace("\u09AF\u09BC", "\u09DF")
      .replace("\u09C7\u09BE", "\u09CB").replace("\u09C7\u09D7", "\u09CC")
      .replace("\u09BC", "").replace("\u09D7", "")
    val out = StringBuilder()
    var i = 0
    var wordStart = true
    while (i < s.length) {
      val c = s[i]
      val next = s.getOrNull(i + 1)
      val piece: String = when {
        c in consonants -> {
          val base = consonants.getValue(c)
          when {
            next == HASANTA || (next != null && next in signs) -> base
            // the word's last letter: no "a"
            !isBanglaLetter(next) -> base
            else -> base + "a"
          }
        }
        c in signs -> signs.getValue(c)
        c in independent -> independent.getValue(c)
        c == HASANTA -> ""
        c in others -> others.getValue(c)
        c in '০'..'৯' -> (c - '০').toString()
        else -> c.toString()
      }
      // each word starts with a capital (names)
      out.append(if (wordStart && piece.isNotEmpty() && c in 'ঀ'..'৿') piece.replaceFirstChar { it.uppercaseChar() } else piece)
      if (piece.isNotEmpty()) wordStart = c.isWhitespace() || c == '_' || c == '-'
      i++
    }
    return out.toString()
  }
}
