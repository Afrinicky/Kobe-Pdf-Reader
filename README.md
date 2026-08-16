# Kobe PDF Reader

An offline-first Android PDF reader and toolkit. Every document operation runs
on the device — no account, no server, no upload.

© 2026 Nickland Sales and Services. All rights reserved. Kobe PDF Reader is the
property of Nickland Sales and Services.

> **Status:** V1 source is complete. It has **not been compiled**, because the
> environment it was written in cannot reach `dl.google.com` (Google's Maven
> repository and the Android SDK download host are both blocked by the network
> policy). See [Before your first build](#before-your-first-build).

## What it does

**Reader** — open PDFs from anywhere on the device, continuous vertical
scrolling or horizontal paging, pinch zoom that re-renders rather than
upscaling, full-document text search, and it remembers where you stopped.

**Library** — All / Recent / Favorites, search, sort by name, date or size,
rename, delete, duplicate and share.

**Tools** — merge, split, extract pages, delete pages, reorder, rotate,
compress, images → PDF, PDF → images, password protect.

## Stack

| Layer | Choice |
|---|---|
| Language / UI | Kotlin 2.3, Jetpack Compose, Material 3 |
| Architecture | MVVM with a domain-shaped `PdfToolkit` boundary |
| PDF rendering | `android.graphics.pdf.PdfRenderer` (platform PDFium) |
| PDF manipulation | PdfBox-Android 2.0.27 |
| Storage | Storage Access Framework + app-private output |
| Database | Room 3 — metadata only |
| DI | Hilt |
| Async | Coroutines; WorkManager wired for future long jobs |
| Navigation | Navigation Compose with type-safe routes |
| Monetization | AdMob + Play Billing, both behind interfaces |

Build: AGP 9.3.1, Gradle 9.5.0, JDK 17+, `compileSdk`/`targetSdk` 37,
`minSdk` 24 (core library desugaring supplies `java.time`).

### Why two PDF engines

They do different jobs and never share a handle.

`PdfRenderer` is the platform's native PDFium binding. It rasterises a page
roughly an order of magnitude faster than PdfBox does on Android and keeps the
page tree out of the Java heap, which is what makes a 400-page document usable
on a 2 GB phone. It cannot, however, change a document's structure.

PdfBox-Android can restructure anything — merge, split, rotate, encrypt — but
rasterising through it would make scrolling unusable. So: PdfBox for structure,
`PdfRenderer` for pixels.

## Project layout

```
app/src/main/java/com/kobe/reader/
├─ core/
│  ├─ common/     Outcome, Progress, dispatcher qualifiers, formatters
│  ├─ error/      KobeError — every failure as plain-language copy
│  ├─ file/       DocumentStore (all filesystem access), FileNaming, DocumentRef
│  └─ pdf/        PageSelection — the "1-3, 5, 8-" parser
├─ pdf/
│  ├─ PdfToolkit.kt        the interface every tool depends on
│  ├─ PdfModels.kt         options and results
│  ├─ internal/            PdfBox implementation, compressor, rasteriser
│  └─ render/              PdfPageRenderer, PageBitmapCache
├─ data/
│  ├─ database/   Room 3 documents table
│  ├─ prefs/      DataStore settings
│  └─ repository/ LibraryRepository — the storage error boundary
├─ monetization/  ProFeature, PremiumManager, billing/, ads/
├─ ui/            theme, navigation, shared components
├─ feature/       home, files, reader, tools, organize, result, paywall, settings
└─ di/            Hilt modules
```

### Boundaries worth knowing

**Errors stop at the repository.** Below `LibraryRepository` and `PdfToolkit`,
code throws. Above them, everything is an `Outcome<T>` carrying a `KobeError`
that maps to one user-facing sentence. No exception message ever reaches the UI.

**All filesystem access goes through `DocumentStore`.** It owns SAF grants,
MediaStore discovery, output naming, free-space checks and working copies.
Nothing else opens a stream.

**Tools depend on `PdfToolkit`, not on PdfBox.** Swapping the engine — for the
platform PDF editing APIs added in API 36.1, say — is one binding in
`AppModule`.

**Premium is one enum.** `ProFeature` carries each feature's free daily limit;
`PremiumManager.checkAccess` is the only gate. Moving a feature between tiers is
a one-line edit.

## Storage and permissions

The app requests **no** storage permission on modern Android, and deliberately
does not request `MANAGE_EXTERNAL_STORAGE`. That permission is Play
policy-restricted, and asking for whole-filesystem access flatly contradicts
"your documents stay on your device".

Instead:

- Opening a file uses `ACTION_OPEN_DOCUMENT`, and the grant is persisted so the
  file still opens after a reboot.
- Browsing a library uses `ACTION_OPEN_DOCUMENT_TREE` — the user picks which
  folders the app may look in, and can revoke them.
- `READ_EXTERNAL_STORAGE` is declared with `maxSdkVersion="32"` only, for the
  MediaStore sweep on older devices. From API 33 there is no runtime permission
  covering non-media documents, so that path simply isn't used.
- `INTERNET` exists solely for the ad SDK in the free tier. Delete the
  monetization module and those three lines go with it.

Tool output is written to app-private storage first. That write cannot fail for
permission reasons, so a 200-page merge is never lost at the last step; the user
then exports it wherever they like with `ACTION_CREATE_DOCUMENT`.

## Before your first build

Open the project in Android Studio and let it sync. Two things may need
attention:

1. **Install SDK Platform 37.** `compileSdk 37` fails to sync without it.
2. **Version pins.** Every dependency version in `gradle/libs.versions.toml` was
   chosen without being able to resolve it (Google's Maven host is unreachable
   from the environment this was written in). Android Studio will flag anything
   that has moved on; the two most likely are `play-services-ads` and
   `billing-ktx`, which nothing outside `com.kobe.reader.monetization` touches.
   If a Google Maven artifact 404s, drop to the highest published version that
   still supports `compileSdk` 37.

Then:

```bash
./gradlew help              # resolves the toolchain; run this first
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
```

The unit tests cover the pure logic — page-range parsing, file naming and
collision handling, entitlement and daily-limit rules, compression percentages.
Anything touching PdfBox or `PdfRenderer` needs a device.

## Before you publish

- **Application ID.** `com.kobe.reader` is a placeholder. Change it in
  `app/build.gradle.kts` if you don't own that namespace.
- **Signing.** The release build type currently uses the debug signing config so
  it assembles out of the box. Replace it with a real upload key.
- **AdMob.** `res/values/monetization.xml` ships Google's public *test* IDs, so
  a fresh clone runs and shows test ads without an AdMob account. Shipping those
  to production violates the AdMob programme policy — replace all four values.
- **Billing.** Replace `sku_pro_lifetime` and `sku_pro_yearly` with the product
  IDs you create in the Play Console.
- **Data safety form.** The honest answers: no data collected, no data shared,
  no data transmitted off device. The only SDK that touches the network is
  AdMob, which collects an advertising ID — declare that if you ship ads.

## What V1 leaves out

Intentionally, per the spec: document scanning, OCR, annotations, signing, form
filling, watermarks, PDF → Word/Excel, cloud backup and sync.

Two smaller deferrals worth naming:

- **Long operations run in a ViewModel coroutine**, not a WorkManager job. They
  survive rotation and report cancellable progress, but not process death.
  WorkManager and Hilt's worker factory are already wired up; moving the tool
  execution into a `@HiltWorker` is the upgrade path when someone reports losing
  a merge to a background kill.
- **Compression estimates drawn size from page size.** PdfBox can report an
  image's pixel dimensions but not the size it is actually drawn at without
  interpreting the content stream, so `PdfImageCompressor` assumes an image
  roughly covers its page. That is exact for full-page scans — the dominant case
  — and under-compresses small logos, which is the safe direction to be wrong in.

## Ownership and licence

Kobe PDF Reader is proprietary software owned by **Nickland Sales and Services**
— see [LICENSE](LICENSE). All rights reserved.

**Third-party open-source libraries** used at runtime carry their own licences,
which legally require a short acknowledgement *of the library* (not of the app's
authorship) somewhere in the shipped app — an "Open source licences" entry in
Settings is the usual place. This is licence compliance for the libraries, not a
credit to any co-author of Kobe PDF Reader. The libraries are: PdfBox-Android
(Apache 2.0), which pulls in BouncyCastle (MIT/Bouncy Castle Licence), and the
AndroidX / Jetpack Compose stack (Apache 2.0). Removing these acknowledgements
would breach those licences; the only way to drop them entirely is to stop using
those libraries.
