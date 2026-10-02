# AGENTS.md

Rules for coding agents (Claude Code, Codex, Copilot, etc.) working on Oncompanion.
Humans on the team follow the same rules. See [README.md](README.md) for what the app does.

## Project context

- Android app in Kotlin with Jetpack Compose (Material 3). `minSdk 28`, `targetSdk 34`, JVM target 17.
- Backend: Firebase Authentication (Google Sign-In) and Cloud Firestore. Firebase project: `oncompanion-c4144`.
- Package root: `com.github.se.oncompanion`.
- Users are cancer patients in active therapy. **The app is an organizational tool, not a medical device**: never add code that diagnoses, gives medical advice, or changes a treatment. Anything drafted automatically (e.g. from a scanned prescription) must be confirmed by the user before it is saved.

## Architecture

Follow MVVM, one feature per package. The layers, use cases and Firestore layout are described in [docs/architecture.md](docs/architecture.md); update its diagram in the same PR when you change the architecture.

```
com.github.se.oncompanion/
  model/<feature>/   data classes + repository interface + Firestore implementation
  model/device/      device service interfaces + implementations (ML Kit, speech, reminders, PDF)
  domain/<feature>/  use cases (optional layer)
  ui/<feature>/      @Composable screens + their ViewModel
  ui/navigation/     navigation graph and routes
  ui/theme/          colors, typography, theme
  resources/C.kt     test tags
```

- **Model**: plain data classes. Repositories hide Firebase behind an interface (e.g. `SymptomRepository` / `SymptomRepositoryFirestore`) so ViewModels can be tested with a fake.
- **Device services**: Android and ML Kit APIs (text recognition, speech, reminders, PDF export) are wrapped behind interfaces in the same way, so use cases and ViewModels never call them directly.
- **Use case** (optional): one class per use case with a single `operator fun invoke(...)`, taking repositories and device services in its constructor. Add one only when the logic combines several repositories or services, or is shared by several screens (e.g. `ManageMedicationSchedule` keeps reminders in sync with saved medications). Plain reads and writes go straight from the ViewModel to the repository.
- **ViewModel**: exposes UI state as `StateFlow`, takes its repositories and use cases in the constructor, no Android `Context` and no Compose imports.
- **View**: composables are stateless where possible, get state from the ViewModel and send events back. No Firebase calls from composables.
- Every element that a test needs to find gets a test tag declared in `resources/C.kt`.
- Offline mode is a requirement: rely on Firestore's local cache and never block the UI waiting for the network.

## Firebase and data

- Personal data lives under `/users/{uid}/...`. Every new collection needs an explicit `match` in [firestore.rules](firestore.rules); anything not matched is denied. Never loosen a rule to make something "just work".
- `app/google-services.json` is committed on purpose (client config, not a secret). Never commit service account keys, admin SDK credentials, or `local.properties`.
- Use the Firebase emulator for tests that touch Firestore or Auth, never the production project.

## Build, format, test

Run these before every commit and make sure they pass:

```bash
./gradlew ktfmtFormat      # format Kotlin sources (CI fails on ktfmtCheck otherwise)
./gradlew check            # unit + Robolectric tests, lint
./gradlew connectedCheck   # instrumented tests, needs an emulator/device
```

- Unit and Robolectric tests go in `app/src/test`, instrumented Kaspresso/Compose tests in `app/src/androidTest`.
- New code needs tests: SonarCloud's quality gate requires **≥ 80% coverage on new code**. Compose adds hidden branches, so test the different states of a screen (loading, empty, error, filled), not only that it renders.
- Dependency versions are locked (`app/gradle.lockfile`, `settings-gradle.lockfile`). After adding or updating a dependency in `gradle/libs.versions.toml`, run `./gradlew :app:dependencies --write-locks` and commit the updated lock files with your change.
- Don't add coverage exclusions to `app/build.gradle.kts` to make the gate pass. Ask the team first.

## Git workflow

- Never push to `main`. Work on a branch and open a pull request. Merging needs one approving review and a green CI (tests + SonarCloud).
- Branch names: `feature/<short-name>`, `fix/<short-name>`, `chore/<short-name>`, `ci/<short-name>`, `docs/<short-name>`.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `test:`, `refactor:`, `chore:`, `ci:`, `docs:`, followed by a short imperative summary (e.g. `feat: add one-tap fatigue logging`).
- Keep PRs small and focused on one task from the Scrum board, and link the related issue in the description.
