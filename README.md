# DocReady

A phone app (Android) that makes documents **ready to submit** to online forms, all on the
phone, with no sign-in:

- **Scan document**: Google's document scanner (edges, straightening, shadows), or photos of
  papers → corners adjustable by hand, quarter turns, a warning on blurry / dark pages → one
  PDF or JPG pictures, optionally under a size limit (KB).
- **Photo**: take or choose a photo → face found and centred (ML Kit face detection) →
  background White / Light blue / Grey / Keep (ML Kit subject segmentation) → exact W × H px
  and at most N KB JPG, with a clear "Meets the preset" card or a warning. The crop can be
  adjusted by hand (a box of the asked shape).
- **Signature**: take or choose → crop → only the ink kept, on white (JPG) or see-through
  (PNG) → exact W × H px, at most N KB. Optionally placed on a form's page (photo or PDF
  page): drag and size it, then save the page as JPG or PDF.
- **PDF tools**: merge PDFs and photos into one PDF, reorder / delete pages, compress a PDF
  under N KB, both sides of the NID card on one A4 page.
- **Split by preset** (its own tile on Home, also in PDF tools): one merged PDF (and / or photos) → each page given to a
  preset item (photo, signature, SSC certificate, NID…), photo / signature cut from their
  page, pages straightened if needed → "Make all" makes every item's file in its own size
  and KB limit (`ssc_certificate.pdf`, `photo_300x300.jpg`…), saved together in one folder
  or shared. Offered when a PDF or several files are shared / opened with the app. PDFs of up
  to 150 pages: only small page pictures are made when it opens ("Reading page X of N"); full
  pages are made from the PDF when the files are made.
- **Presets**: all made by the user (none built in). Each preset lists the papers a form asks
  for: + Document / + Photo / + Signature, each with a name in any script, W × H px, max KB,
  format, background, a pages hint and Required; reorder, duplicate, delete. Share a preset as
  a small `<name>.docready.json` file (WhatsApp, e-mail…) and import it from the file picker or
  by sharing / opening it with DocReady (checked strictly: size cap, format mark, sane numbers).
- **Page editor** (shared by Scan, PDF tools, Split, Photo, Signature and the signature's form
  page): pinch zoom, hold to compare; Crop (four corners with Find edges, free crop, fine
  straighten ±15°, turn 90°), Clean (remove shadows, Original / Auto color / Grey / B&W /
  Whiten, brightness, contrast), Erase (brush in paper colour or white); undo / redo, reset,
  apply the look to all pages. Edits are a per-page recipe: the preview is made at screen size,
  the full page from the untouched source only when files are made. New document pages start
  with shadows removed and Auto color; the photo editor offers crop / rotate / light / erase,
  the signature editor crop / rotate / erase / contrast.
- **Save**: to this phone (Downloads / DocReady / *folder*) or Share to any app. Files get
  readable names (`photo_300x300.jpg`) and are never overwritten (`-2`, `-3`…).
- **Share to / Open with DocReady**: photos and PDFs shared or opened from other apps open
  in PDF tools / Photo / Signature.

Phase 1 is offline only: the app has **no internet permission** and no analytics.

Colours: deep green (#1B6B3A; #8FD19E in dark mode) on warm neutral greys, amber for
warnings; no blue anywhere in the interface (the "Light blue" photo background is an output
colour for form photos, not UI).

The ML Kit
models are delivered by Google Play services (the app stays small, and the scanner needs no
camera permission).

## Build

- JDK 17, Android SDK 36. `./gradlew assembleDebug` (debug id `com.docready.aamunim.debug`).
- Release: `./gradlew assembleRelease bundleRelease` (R8 minify + resource shrinking).
- App id and namespace: `com.docready.aamunim` (one value, `appId` in `app/build.gradle.kts`).
- Kotlin 2.3.0, AGP 8.11.1, Gradle 8.13, Compose BOM 2024.12.01 + Material 3, Navigation
  Compose. Presets and folders are small JSON files in the app's private storage.

### CI (`.github/workflows/build.yml`)

Every push / pull request builds the APK and the Play bundle (artifacts `docready-apk`,
`docready-play` with the R8 mapping). Version code = 1000 + the run number; version name is
`VERSION_NAME` in `gradle.properties`.

To publish: Actions → Build → Run workflow on **main** → Google Play track
(internal / alpha / production) and status (draft / completed). The very first bundle of a new
app must be uploaded by hand in Play Console.

### Secrets (copy them from the ittc-phone repository)

Repository → Settings → Secrets and variables → Actions:

| Secret | What |
| --- | --- |
| `ITTC_KEYSTORE_BASE64` | the upload keystore, base64 |
| `ITTC_KEY_ALIAS` | key alias |
| `ITTC_KEY_PASSWORD` | key password |
| `ITTC_KEYSTORE_PASSWORD` | keystore password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Play service account JSON (only for uploads) |

Without the keystore secrets the build is unsigned (a warning, not a failure), except when
uploading to Play. The keystore is written to a temp file for the run and removed after.

## Code map

```
app/src/main/java/com/docready/aamunim/
  MainActivity.kt, DocReadyApplication.kt
  imaging/   DocTools (load, clean, warp, fit to KB, PDF writer), DocPhoto (face + background),
             SignatureTools, PdfTools (PdfRenderer, NID page), Scanner (ML Kit scanner)
  data/      presets + local folders (JSON), file naming
  save/      SaveTarget (PHONE, SHARE; GOOGLE_DRIVE and ITTC = "Coming soon")
  intake/    "Share to DocReady"
  ui/        theme, components (save sheet, crop box, icons), screens, navigation
```

The image code is ported from the DocReady Expo module (lead repo,
`modules/doc-scan/android`), with the React Native parts removed.

## Phase 2 plan (not in this version)

- **Google Drive (optional, personal)**: Google Identity Services `AuthorizationClient`
  (`Identity.getAuthorizationClient(activity).authorize(...)`) with only the
  `https://www.googleapis.com/auth/drive.file` scope (DocReady sees only files it creates),
  uploads to a `DocReady` folder, queued while offline. Needs an **Android OAuth client** in
  Google Cloud for package `com.docready.aamunim` with **both** SHA-1 fingerprints: the Play
  app-signing key (Play Console → App integrity) and the upload key. Adds the INTERNET
  permission. Implemented as a new `SaveTarget` (`GOOGLE_DRIVE`).
- **ITTC account (optional, staff)**: sign-in to the ITTC server (Supabase), reusing ITTC
  Phone's direct-mode sign-in code; the institute's presets, lead folders, uploads to the ITTC
  Drive (`SaveTarget` `ITTC`). Server URL / anon key from CI secrets, never in the repo.
- Lead folder screen (mockup "Folder"), upload queue with retry.
