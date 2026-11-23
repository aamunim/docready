package com.docready.aamunim

import android.app.Application
import android.content.Context
import com.docready.aamunim.data.FolderRepository
import com.docready.aamunim.data.PresetRepository
import com.docready.aamunim.save.SaveTargets

/** The app's few long-lived parts (no sign-in, no network in Phase 1) */
class DocReadyApplication : Application() {
  val presets: PresetRepository by lazy { PresetRepository(this) }
  val folders: FolderRepository by lazy { FolderRepository(this) }
  val saveTargets: SaveTargets by lazy { SaveTargets(folders) }
}

val Context.docReady: DocReadyApplication
  get() = applicationContext as DocReadyApplication
