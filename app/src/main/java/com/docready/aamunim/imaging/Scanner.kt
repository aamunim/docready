package com.docready.aamunim.imaging

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Google's document scanner (ML Kit, run by Google Play services): edges found, pages
 * straightened, shadows cleaned. Its own screen takes the pictures, so the app needs no
 * camera permission.
 */
object Scanner {
  /** The scanner's screen, to be launched with StartIntentSenderForResult */
  fun startIntent(activity: Activity, pageLimit: Int, gallery: Boolean = true): Task<IntentSender> {
    val options = GmsDocumentScannerOptions.Builder()
      .setGalleryImportAllowed(gallery)
      .setPageLimit(pageLimit.coerceIn(1, 30))
      .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
      .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
      .build()
    return GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
  }

  /** The scanned pages' pictures from the scanner's result (empty when nothing came back) */
  fun pageUris(data: Intent?): List<Uri> {
    val result = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return emptyList()
    return result.getPages()?.map { it.getImageUri() } ?: emptyList()
  }
}
