# Infinite Reminders

Offline Android app (Kotlin, Compose, Room, WorkManager). Every open reminder is its own persistent
notification, finished only by **DONE → CONFIRM DONE**.

> **Build status (honest):** this project was written in a sandbox with no Android SDK and no access to
> Google/Gradle servers, so it has **not been compiled, and no test has been run**. No APK is included.
> Build it with one of the two methods below. If the first build reports a compile error, send it to me and I'll fix it.

## Get the APK

### A. GitHub Actions (no Android Studio, no PC setup)
1. Create a new GitHub repo and upload this folder's contents (or `git push`).
2. Open **Actions → Build APK → Run workflow** (it also runs on every push).
3. When it is green, download artifact **InfiniteReminders-debug-apk** → unzip → `app-debug.apk`.
4. Copy it to your phone and install (allow "install unknown apps").

The same workflow runs the unit tests, and a second job runs the instrumented tests on an emulator.

### B. Android Studio
1. Install Android Studio (Koala or newer), **File → Open** this folder, let Gradle sync
   (it will download Gradle 8.7 / AGP 8.5.2 itself; JDK 17 is bundled).
2. **Build → Build APK(s)**. Output: `app/build/outputs/apk/debug/app-debug.apk`.
3. Or on a PC with Gradle 8.7 + Android SDK 34: `gradle :app:assembleDebug`.

### Release APK
Set `IR_KEYSTORE`, `IR_KEYSTORE_PASSWORD`, `IR_KEY_ALIAS`, `IR_KEY_PASSWORD` (env vars or `~/.gradle/gradle.properties`),
then `gradle :app:assembleRelease`. Without them the release APK is unsigned.

## Tests
- JVM: `gradle :app:testDebugUnitTest` (recurrence, draft normalisation).
- Instrumented (emulator/device): `gradle :app:connectedDebugAndroidTest` — covers 1/100/500 reminders, unique ids,
  DONE does not complete, CONFIRM completes once, rapid taps, restart & reboot recovery, denied permission,
  registration failure + retry, no duplicate posts, dismissal restore, overflow promotion, search over 1000 items,
  persistence, backup round-trip. They use a fake notification poster; real-device checks (below) are still needed.

## How it works
- **Database (Room) is the source of truth.** State changes are single guarded SQL updates
  (`WHERE state='ACTIVE'` / `'PENDING'`), so rapid taps and double deliveries cannot complete a task twice or skip a step.
- `NotificationSynchronizer.syncAll()` makes the shade match the DB (idempotent, signature-based, no duplicates).
  It runs after every change, on app open, on boot, from the 15-min WorkManager job, and from "Restore".
- Notification appears immediately on creation, regardless of due date. A **scheduled alert** is a separate, optional extra
  (one rolling alarm, so Android's ~500-alarm limit is irrelevant).
- Recurring tasks: confirming creates the next occurrence in the same transaction; the old one goes to history.

## Android limitations (read this)
1. **50-notification cap.** Android allows one app about 50 notifications; extra ones are dropped by the system.
   The app shows up to 45 individually (adjustable to 46), keeps all others safely in the DB, shows one "N more saved"
   row, and promotes the next reminder the moment a slot frees. Order: awaiting-confirmation first, then priority, due date, age.
   Nothing is ever deleted or merged. "Unlimited reminders" is true; "unlimited individual rows" is not possible on Android.
2. **Swipe protection is best-effort.** Notifications are `ongoing`, but Android 14+ lets users swipe them away.
   The app re-posts a dismissed one immediately (and the 15-min worker repairs anything else).
3. **Reboot:** Android clears notifications on reboot; a boot receiver re-posts everything (incl. CONFIRM DONE ones).
   Some OEMs need "auto-start"/"unrestricted battery" enabled for the app (Settings → Battery guidance).
4. **Force Stop:** if you force-stop the app, Android disables all its receivers and jobs until you open it once.
   Nothing can work around that; opening the app restores everything.
5. **Android 13+:** notification permission is required and requested in onboarding. If denied, reminders are still saved,
   a red banner explains it, and they appear as soon as you allow notifications.
6. **Exact alerts** need the "Alarms & reminders" permission on Android 12+; otherwise alerts may be a few minutes late.
7. Notification updates are rate-limited by Android, so large re-syncs post gradually.

## Permissions
`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM` (optional use). **No internet permission.**

## Layout
`data/` Room entity, DAO, repository, backup · `notify/` factory, synchronizer, alarms · `receiver/` actions, boot, alert ·
`work/` periodic reconcile · `ui/` Compose screens (home, editor, settings, onboarding).
