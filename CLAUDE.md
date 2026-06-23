# VibeTrainingAssistant — Project Conventions

Android app (Kotlin + Jetpack Compose) supporting Antoine's Berlin Marathon
2026 training. Stack: Gradle 8.7, JDK 17.

## Build & release pipeline

- `.github/workflows/build.yml` builds a debug APK on every push to `main` and
  publishes it to the `latest` GitHub release as `VibeTraining.apk`.
- Download URL:
  https://github.com/antoinedt/VibeTrainingAssistant/releases/download/latest/VibeTraining.apk
- The workflow does **not** run on branches or PRs — only on `main` — so a
  change must land on `main` before its APK is produced.

## Working agreements (standing instructions from Antoine)

1. **Always open a PR into `main` without asking for confirmation.** Branch,
   commit, push, and open the PR as a matter of course.
2. **Always notify Antoine when a new `latest` APK is available** after a build
   completes, including the download link above.
