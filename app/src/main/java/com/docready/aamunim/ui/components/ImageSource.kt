package com.docready.aamunim.ui.components

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/** Take a photo with the phone's camera app, or choose one (Android's photo picker) */
class ImageSource(val takePhoto: () -> Unit, val pickPhoto: () -> Unit)

/**
 * The camera app writes into a file of ours (FileProvider), so no camera permission is
 * needed; the photo picker needs no storage permission.
 */
@Composable
fun rememberImageSource(onImage: (Uri) -> Unit): ImageSource {
  val context = LocalContext.current
  val latest by rememberUpdatedState(onImage)
  // (kept across the app being closed while the camera is open)
  var pending by rememberSaveable { mutableStateOf<String?>(null) }
  val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
    val p = pending
    if (ok && p != null) latest(Uri.parse(p))
  }
  val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) latest(uri)
  }
  return remember(take, pick) {
    ImageSource(
      takePhoto = {
        try {
          val dir = File(context.cacheDir, "camera").apply { mkdirs() }
          dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
          val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
          val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
          pending = uri.toString()
          take.launch(uri)
        } catch (_: ActivityNotFoundException) {
          Toast.makeText(context, "No camera app found on this phone.", Toast.LENGTH_LONG).show()
        }
      },
      pickPhoto = {
        try {
          pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: ActivityNotFoundException) {
          Toast.makeText(context, "No photo picker found on this phone.", Toast.LENGTH_LONG).show()
        }
      },
    )
  }
}

@Composable
fun ImageSourceButtons(source: ImageSource, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Button(onClick = source.takePhoto, modifier = Modifier.weight(1f)) {
      Icon(AppIcons.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text("Take photo")
    }
    OutlinedButton(onClick = source.pickPhoto, modifier = Modifier.weight(1f)) {
      Icon(AppIcons.Image, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text("Choose photo")
    }
  }
}
