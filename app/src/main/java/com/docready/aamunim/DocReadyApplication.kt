package com.docready.aamunim

import android.app.Application
import android.content.Context
import com.docready.aamunim.data.FolderRepository
import com.docready.aamunim.data.PresetRepository
import com.docready.aamunim.save.SaveTargets

/** The app's few long-lived parts (no sign-in, no network in Phase 1) */
class DocReadyApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    // (text reading may ask Play services for its model)
    com.docready.aamunim.imaging.Ocr.appContext = this
  }

  val presets: PresetRepository by lazy { PresetRepository(this) }
  val folders: FolderRepository by lazy { FolderRepository(this) }
  /** The Folders tab's folders and file records (made once from the older list of folders) */
  val folderTree: com.docready.aamunim.data.FolderTree by lazy { com.docready.aamunim.data.FolderTree(this, folders) }
  val saveTargets: SaveTargets by lazy { SaveTargets(folderTree) }
  val settings: com.docready.aamunim.data.SettingsRepository by lazy { com.docready.aamunim.data.SettingsRepository(this) }
  val drafts: com.docready.aamunim.data.DraftRepository by lazy { com.docready.aamunim.data.DraftRepository(this) }
  val learned: com.docready.aamunim.data.LearnedWords by lazy { com.docready.aamunim.data.LearnedWords(this) }
  val students: com.docready.aamunim.data.StudentRepository by lazy {
    com.docready.aamunim.data.StudentRepository(this, saveTargets.phone, settings, presets)
  }
}

val Context.docReady: DocReadyApplication
  get() = applicationContext as DocReadyApplication
