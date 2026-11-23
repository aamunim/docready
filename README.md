# DocReady

A phone app (Android) that makes documents **ready to submit** to online forms, all on the
phone, with no sign-in:

- **Scan document**: Google's document scanner (edges, straightening, shadows), or photos of
  papers → **Magic** by default (paper evened by an edge-aware estimate: hard shadows, gradients,
  fold tents and soft creases removed without halos; paper to white with a soft knee; deeper ink,
  colours and photos kept; light denoise and sharpening), pages kept at up to 3000 px (about
  250 dpi) unless a KB limit needs less; a scanning animation while each page is cleaned → corners adjustable by hand, quarter turns, a warning on blurry / dark pages → one
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
  straighten ±15°, turn 90°), Clean (remove shadows, Original / Magic / Magic B&W / Lighten / Auto color / Grey / B&W /
  Whiten, brightness, contrast), Erase (brush in paper colour or white); undo / redo, reset,
  apply the look to all pages. Edits are a per-page recipe: the preview is made at screen size,
  the full page from the untouched source only when files are made. New document pages start
  with shadows removed and Auto color; the photo editor offers crop / rotate / light / erase,
  the signature editor crop / rotate / erase / contrast.
- **✨ Beautify** (bad paper), a tab of the page editor and part of each page's recipe: one tap
  looks at the page (on a ≤ 1000 px copy) and turns on only what it needs — text-line
  straightening (offered only above 15°), upside-down pages, black-border trim, glare on
  laminated cards, stains, yellow paper to white (ink colours kept), folds, uneven light,
  denoise then sharpen, faded ink — with Light / Medium / Strong and a switch and slider per
  step. Fingers, punch holes and staples at the edges are outlined first and painted over
  only when turned on. Photos, logos and stamps are left out of the colour steps; face photos
  never get the clean-up; signatures get only faded ink and denoise. Hold to compare, a
  before / after loupe, "Beautify all pages"; each page tile shows what ran
  ("✨ shadow · yellow · fold"). A blurry page says "rescan for better quality".
- **Save**: to this phone (Downloads / DocReady / *folder*) or Share to any app. Files get
  readable names (`photo_300x300.jpg`) and are never overwritten (`name (2).jpg`…). The save
  sheet checks each file against its limit (green fits / amber within 10 % / red too big) with
  **Fix to fit**, and can save or share several files **as one ZIP**.
- **File names** (Accounts → File names): a pattern from `{name}` `{roll}` `{item}`
  `{preset}` `{date}` `{seq}`, a date format and a separator; Bangla names can be written in
  Latin letters ("রহিম উদ্দিন" → `Rahim_Uddin`). A preset can have its own pattern.
- **Students**: each applicant's name (any script), roll / registration, date of birth, notes
  and form preset. Their page is a checklist of the preset's items (done / missing / too big):
  tap an item to open the right tool for it, and "Add to <name>" puts the result in the
  student's files (kept in the app, and saved to Downloads / DocReady / *student*). Split by
  preset can save all its files to a student. Share all as ZIP / Save as ZIP; search, sort,
  delete (their Downloads files only if asked).
- **Drafts**: Scan, PDF tools, Split by preset and Photo keep unfinished work (sources and
  edits, in the app's files) a moment after each change; opening the tool again asks
  "Continue where you left off?". Home lists the drafts; a draft ends when its result is saved
  and is removed after 30 days untouched.
- **Check size** (Home): any photo or PDF → KB, pages or pixels; give a KB limit and Fix to fit
  (optionally keeping the pixel size), then save or share.
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
             SignatureTools, PdfTools (PdfRenderer, NID page), Scanner (ML Kit scanner),
             SizeFixer (size check, Fix to fit)
  data/      presets, local folders, students, drafts, settings (JSON); file naming (Naming.kt)
  save/      SaveTarget (PHONE, SHARE; GOOGLE_DRIVE and ITTC = "Coming soon"), Zipper
  intake/    "Share to DocReady"
  ui/        theme, components (save sheet, crop box, icons), screens, navigation
```

The image code is ported from the DocReady Expo module (lead repo,
`modules/doc-scan/android`), with the React Native parts removed.

## Later (not in this version)

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
