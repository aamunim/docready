package com.docready.aamunim

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.docready.aamunim.intake.Intake
import com.docready.aamunim.ui.DocReadyApp
import com.docready.aamunim.ui.theme.DocReadyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContent {
      DocReadyTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
          DocReadyApp()
        }
      }
    }
    // a share that opened the app (not again when the screen is rebuilt or restored from Recents)
    if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
      takeShare(intent)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    takeShare(intent)
  }

  private fun takeShare(intent: Intent?) {
    if (intent == null || !Intake.isShare(intent)) return
    lifecycleScope.launch {
      val (files, skipped) = withContext(Dispatchers.IO) { Intake.copyShared(applicationContext, intent) }
      Intake.offer(files, skipped)
    }
    // taken: a rebuilt screen does not take it again
    setIntent(Intent(Intent.ACTION_MAIN))
  }
}
