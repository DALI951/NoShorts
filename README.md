# NoShorts

A Kotlin **Android app** that hides **YouTube Shorts** on Dali's Samsung Galaxy S23 Ultra (Android 16). It covers the Shorts tab so it can't be tapped, and tries to remove Shorts from the feed.

> Kotlin · Android · Accessibility services · Material 3 · CI-built APK · automated releases

---

## What it does

YouTube's Shorts are designed to keep you scrolling. NoShorts gets them out of the way, two ways at once:

1. **Hides the Shorts tab** — a service draws a solid box over the Shorts icon in YouTube's bottom nav, so it can't be seen or tapped.
2. **Cleans the feed** — an accessibility service auto-clicks YouTube's **"Show fewer Shorts"** control in the homepage feed.

It's built for Dali's own S23 Ultra (dimensions/margins tuned for that device), so it's a practical, personal tool rather than a polished store app.

## How it works

**`OverlayService`** — draws the blocking box:
- The box's position **and visibility** are driven by the *real* "Shorts" tab bounds read from YouTube's accessibility tree (centered on the actual tab, both axes).
- It appears only while that icon is visible, tracks it as it moves, and hides when the bottom bar hides, a video goes fullscreen, the Shorts player opens, YouTube closes, or another app takes the foreground.
- A **preview mode** shows the box anywhere for tuning, and always stops when told.

**`ShortsWatchAccessibilityService`** — monitors the feed and the player:
- Reads YouTube's accessibility tree to detect whether the (vertical-scrolling) Shorts player is open.
- Heuristically finds and taps "Show fewer Shorts" in the feed (with a cooldown so it doesn't fight you).
- Exposes a live status the app's UI can read for easy troubleshooting without USB.

**`UpdateChecker` / `UpdateManager`** — self-update:
- Checks GitHub releases for a newer APK, downloads it, and triggers install via the system package installer (a task-tracker-style update flow).
- **CI** (GitHub Actions) builds the APK on every push and attaches it to a release on version tags.

**`Prefs` / `MainActivity` —** simple toggle UI (Material 3) to enable/disable and preview the overlay.

## Build

Requires **Android Studio** (Kotlin, Gradle). Open the project, sync, and build the `app` module — or grab the latest APK from the **Releases** tab (built by CI).

## Release flow

Bump `versionName` / `versionCode` in `app/build.gradle.kts` → push → CI builds and creates a release.

Current version: **v1.3.6** (`versionCode 11`).

## Notes / limitations

- **Heuristic-based**: detecting "Shorts" content relies on accessibility-tree text and layout heuristics tuned for Dali's device + YouTube layout. It may need tuning if YouTube's UI changes.
- You must enable the app's **overlay** and **accessibility** permissions for it to work.

## Repo layout

```
app/src/main/java/com/dali951/noshorts/
  MainActivity.kt
  OverlayService.kt
  ShortsWatchAccessibilityService.kt
  UpdateChecker.kt
  UpdateManager.kt
  Prefs.kt
.github/workflows/build.yml
```
