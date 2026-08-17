# Publishing Kobe PDF Reader to Google Play

Owner: **Nickland Sales and Services**. This is the end-to-end checklist to take
the app from source to a live Google Play listing. Work top to bottom.

---

## Phase 0 — Get the app building and signed (on your machine)

The source is complete but has not been compiled in this environment. Do this
first, because nothing else matters until you have a working release build.

1. **Install Android Studio** (latest stable) on a Mac, Windows, or Linux
   machine. During setup let it install the Android SDK.
2. **Open the project** (the repository folder) and let Gradle sync.
3. In the SDK Manager, **install "Android SDK Platform 37"**. The build targets
   API 37 and sync fails without it.
4. Fix any dependency versions Android Studio flags as unresolved. The two most
   likely are `play-services-ads` and `billing-ktx` in
   `gradle/libs.versions.toml`.
5. Confirm it builds and tests pass:
   - `Build > Make Project`
   - `./gradlew testDebugUnitTest`
6. Run it on a phone or emulator and click through: open a PDF, run Merge,
   Compress, Images→PDF. Fix anything that misbehaves.

**Decide the applicationId now — it is permanent after first publish.**
It is currently `com.nicklandsales.kobepdfreader` (in `app/build.gradle.kts`).
If Nickland owns a domain, use its reverse form instead (e.g.
`com.<yourdomain>.kobepdfreader`). You can never change this for the listing
once it goes live.

---

## Phase 1 — Create the Google Play Developer account

1. Go to <https://play.google.com/console> and sign in with the Google account
   that should own the app (use a business Google account for Nickland, not a
   personal one you might lose access to).
2. Choose an **Organization / business** account type (not "personal") so the
   developer name shown on the listing is the business.
3. Pay the **one-time US$25 registration fee**.
4. Complete **identity and (for organizations) D-U-N-S verification**. Google now
   requires a verified legal business name and address for org accounts — this
   can take a few days, so start it early.
5. Set the **public developer name** to `Nickland Sales and Services`. This is
   the "developer" shown under the app on the store.

---

## Phase 2 — Create the app in Play Console

1. Play Console → **Create app**.
2. App name: **Kobe PDF Reader**.
3. Default language, App (not Game), and **Free** (the app is free with in-app
   purchases and ads).
4. Accept the developer program policies and US export declarations.

---

## Phase 3 — Set up Play App Signing and upload key

Google holds the real signing key; you sign uploads with an "upload key".

1. In Android Studio: **Build > Generate Signed App Bundle**.
2. Create a new **keystore** (`.jks`) — this is your upload key. **Back it up
   somewhere safe and record the passwords.** Losing it is recoverable with
   Google's help; losing it *and* not enrolling in Play App Signing is not.
3. In `app/build.gradle.kts`, replace the placeholder release signing (it
   currently reuses the debug config) with a real `signingConfig` that points at
   this keystore. Keep the passwords out of source — use
   `keystore.properties` + `gradle.properties`, not hard-coded strings.
4. Build a **release Android App Bundle (`.aab`)**, not an APK — Play requires
   AAB for new apps.
5. When you first upload, **enrol in Play App Signing** (the default).

---

## Phase 4 — Replace the placeholder monetization IDs

The app ships with Google's public *test* IDs so a fresh build runs. **These
must be replaced before a production release** or the app violates AdMob policy
and the Pro purchase will not work.

### AdMob (ads)
1. Create an **AdMob account** at <https://admob.google.com> under the Nickland
   Google account and link it to this app.
2. Create ad units: one **Banner**, one **Interstitial** (and optionally a
   Rewarded unit).
3. In `app/src/main/res/values/monetization.xml`, replace:
   - `admob_application_id`
   - `admob_unit_banner`
   - `admob_unit_interstitial`
   - `admob_unit_rewarded`
   with your real values.

### Play Billing (Kobe PDF Reader Pro)
1. In Play Console → **Monetize → Products → In-app products** (or Subscriptions),
   create the products whose IDs match `monetization.xml`:
   - `kobe_pro_lifetime` (one-time product), and/or
   - `kobe_pro_yearly` (subscription).
2. Set prices and activate them. Test purchases with a **license tester** account
   before going live.

---

## Phase 5 — Privacy policy (required)

Because the app shows ads and requests INTERNET, Google **requires a privacy
policy URL**. The app itself does no tracking and uploads no documents, which
makes this short and honest.

1. Host a privacy policy page (a simple web page or a Google Site is fine). It
   should state, truthfully:
   - Documents are processed entirely on the device and never uploaded.
   - No account is required and no personal data is collected by the app.
   - The advertising SDK (Google AdMob) may collect an advertising identifier to
     serve ads; link to Google's advertising policies.
2. Put the URL in Play Console → **Policy → App content → Privacy policy**.

---

## Phase 6 — Complete the required "App content" declarations

In Play Console → **Policy → App content**, complete every item:

1. **Data safety form.** Honest answers for this app:
   - Data collected/shared by the app itself: **none**.
   - Data processed on-device only: the documents (not collected).
   - If ads are enabled: declare that **AdMob collects an Advertising ID** for
     advertising, not shared for tracking beyond ad serving. Google's Data
     safety guidance for AdMob lists exactly what to check.
2. **Content rating questionnaire.** A PDF utility rates **Everyone**. Answer no
   to violence, etc.
3. **Target audience & children.** Target adults/general audience (not designed
   for children) to avoid Families policy obligations.
4. **Ads declaration.** **Yes, this app contains ads.**
5. **App access.** All functionality is available without a login — state that
   no credentials are needed for review.
6. **Government apps / financial / health:** none apply.

---

## Phase 7 — Build the store listing

Play Console → **Grow → Store presence → Main store listing**:

1. **App name:** Kobe PDF Reader
2. **Short description** (≤80 chars): e.g. "Fast, private PDF reader and toolkit
   — merge, split, compress, all offline."
3. **Full description:** lead with the offline/privacy promise and list the
   tools.
4. **Graphics assets** (all required):
   - **App icon** 512×512 PNG (reuse the red document mark).
   - **Feature graphic** 1024×500.
   - **Phone screenshots** — at least 2 (aim for 4–8): Home, Reader, a tool
     flow, the result screen.
5. **Developer contact:** a Nickland email and (optionally) website.

---

## Phase 8 — Test before production

Do not go straight to production. Use the testing tracks:

1. **Internal testing** — upload the signed AAB, add your own email as a tester,
   install from the opt-in link, and verify a real purchase and real ads work
   with the production IDs.
2. **Closed testing** — a small group. Google now generally expects new personal
   developer accounts to run a closed test with **~12 testers for 14 days**
   before production; organization accounts have lighter requirements, but plan
   for a testing period.
3. Fix anything that surfaces, bump `versionCode` (and usually `versionName`),
   rebuild, re-upload.

---

## Phase 9 — Release to production

1. Play Console → **Production → Create new release**.
2. Upload the final signed AAB.
3. Write release notes.
4. Choose countries (worldwide unless you have a reason not to).
5. **Roll out** — start with a staged rollout (e.g. 20%) so you can halt if crash
   reports spike.
6. Submit for review. First reviews commonly take a few days.

---

## Per-release checklist (every future update)

- [ ] Increment `versionCode` (must always go up) and update `versionName`.
- [ ] Build a signed release AAB with the same upload key.
- [ ] Confirm ads and Pro purchase still work.
- [ ] Update release notes.
- [ ] Roll out staged, watch Play Console → Vitals for crashes/ANRs.

---

## App-specific reminders (things unique to this project)

- **applicationId is permanent** — lock it in before the first upload
  (Phase 0).
- **Replace all four AdMob IDs and both product IDs** — the repo ships test
  values (Phase 4).
- **Replace the release signing config** — it currently reuses debug signing
  (Phase 3).
- **16 KB page size:** the app has no native code of its own, but PdfBox pulls in
  none either — no action expected. If a future dependency adds `.so` files,
  Google's 16 KB requirement applies; verify with the APK Analyzer.
- **"Open source licenses" screen:** add a Settings entry listing the OSS
  libraries (PdfBox-Android, BouncyCastle, AndroidX) to satisfy their licences.
  This credits the *libraries*, not the app's authorship.
