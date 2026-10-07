# AGENTS.md

Rules for coding agents (Claude Code, Codex, Copilot, etc.) working on Oncompanion.
Humans on the team follow the same rules. See [README.md](README.md) for what the app does and
[docs/architecture.md](docs/architecture.md) for the architecture and the Firestore layout.

## Project context

- Android app in Kotlin with Jetpack Compose (Material 3). `minSdk 28`, `targetSdk 34`, JVM target 17.
- Backend: Firebase Authentication (Google Sign-In) and Cloud Firestore. Firebase project: `oncompanion-c4144`.
- Package root: `com.github.se.oncompanion`. The app is named **Oncompanion** (never "OnCompanion").
- Users are cancer patients in active therapy. **The app is an organizational tool, not a medical device**: never add code that diagnoses, gives medical advice, or changes a treatment. Anything drafted automatically (e.g. from a scanned prescription) must be confirmed by the user before it is saved.

## Before you start a task

1. Read the issue and its definition of done. If you will do something different, say so in the PR.
2. Read [docs/architecture.md](docs/architecture.md) before adding a feature, a collection or a use case.
3. Look for what already exists before writing something new: fakes in `app/src/test`, test helpers in `app/src/androidTest/.../utils`, shared composables in `ui/common`, repositories in `model/`.
4. Only change files the task needs. No drive-by refactors or reformatting of unrelated code.
5. Ask the team before adding a dependency, changing the build or CI, or changing a convention in this file.

## Architecture

Follow MVVM, one feature per package:

```
com.github.se.oncompanion/
  model/<feature>/   data classes + repository interface + Firestore implementation
  domain/<feature>/  use cases, only when the logic combines several repositories (see architecture.md)
  ui/<feature>/      @Composable screens + their ViewModel
  ui/common/         composables shared by several features
  ui/navigation/     navigation graph and routes
  ui/theme/          colors, typography, theme
  resources/C.kt     test tags
```

- **Model**: plain data classes. Repositories hide Firebase behind an interface (e.g. `SymptomRepository` / `SymptomRepositoryFirestore`) so ViewModels can be tested with a fake.
  - Repositories access Firebase **lazily** (`by lazy`), never in their constructor: ViewModels create them as default parameters, and Firebase isn't initialized in Robolectric tests.
- **ViewModel**: exposes UI state as one `StateFlow<XUiState>`, takes its repositories in the constructor, no Android `Context` and no Compose imports.
- **View**: each screen is split in two:
  - `XScreen(viewModel, onSomething: () -> Unit, ...)` collects the state and passes events to the ViewModel.
  - `XContent(uiState, onEvent...)` is stateless. Tests use it to check every state without Firebase.
  - No Firebase calls from composables.
- **Navigation**: each feature is a nested graph. Add its route to `Route`, its screens to `Screen` (`ui/navigation/NavigationActions.kt`), and a `navigation(...)` block in `ui/navigation/AppNavHost.kt`.
  - Screens receive callbacks (`onBack`, `onSaved`...) or a `NavigationActions`, never the `NavController`.
  - A screen opened from another screen has a back arrow in its top bar (`onBack = navigationActions::goBack`).
  - Use `navigateAndClearBackStack` when Back must not return to the previous flow (e.g. after sign-in or onboarding).
- **Dependency injection** is manual: ViewModels take their repositories as plain constructor parameters with the Firebase implementation as default (`repo: XRepository = XRepositoryFirestore()`), so tests can pass fakes. No Hilt, no lambdas.

## Code style

- KDoc on every public class and function: what it does and why, not how.
- A short one-line comment above each non-obvious block (what this part of the screen or function does), so reviewers can find their way quickly. No comments that repeat the code.
- All user-visible text goes in `res/values/strings.xml`, in English. Escape apostrophes: `Couldn\'t`.
- Every element that a test needs to find gets a test tag declared in `resources/C.kt`, named `<feature>_<element>`.
- Dates: store calendar days at midnight `Europe/Zurich` so they never shift by a day; display dates and times in the phone's time zone (`ZoneId.systemDefault()`), never in UTC.

## Firebase and data

- Personal data lives under `/users/{uid}/...`. Every collection needs its own `match` in [firestore.rules](firestore.rules) with validation that matches the Kotlin class (fields, types, length limits); anything not matched is denied. Never add a catch-all or loosen a rule to make something "just work".
- Rules changes come with rule tests on the Firestore emulator (allowed and denied cases).
- Offline mode is a requirement:
  - Writes return once saved on the device (don't wait for the server), and reads use Firestore's local cache. Never block the UI waiting for the network.
  - When offline, Firestore serves the cache instead of failing: a Firestore error is not a connection problem, so don't show "check your connection" for it.
- `app/google-services.json` is committed on purpose (client config, not a secret). Never commit service account keys, admin SDK credentials, or `local.properties`.
- Use the Firebase emulator for tests that touch Firestore or Auth, never the production project.

## Tests

- New code needs tests: SonarCloud's quality gate requires **≥ 80% coverage on new code**. Don't add coverage exclusions to `app/build.gradle.kts` to make the gate pass; ask the team first.
- Write tests from the issue's definition of done and the expected behavior, not by reading the implementation.
- Test the different states of a screen (loading, empty, error, filled) on `XContent`, plus one test of `XScreen` with its ViewModel. Compose adds hidden branches, so "it renders" isn't enough.
- Where tests go:
  - Unit and Robolectric tests in `app/src/test`; instrumented Kaspresso/Compose and emulator tests in `app/src/androidTest`.
  - One fake per repository, shared by every test: `app/src/test/.../model/<feature>/Fake<Name>Repository.kt`. Reuse it rather than writing a local one.
- Coroutines: use `StandardTestDispatcher` with `runTest` and virtual time (`advanceTimeBy`, `advanceUntilIdle`). Never `Thread.sleep` or real delays.
- While working, run only the tests you touch (`./gradlew testDebugUnitTest --tests '*Symptom*'`). The full checks below take about 10 minutes; run them before pushing.

## Build, format, test

Run these before every push and make sure they pass:

```bash
./gradlew ktfmtFormat      # format Kotlin sources (CI fails on ktfmtCheck otherwise)
./gradlew check            # unit + Robolectric tests, lint
./gradlew connectedCheck   # instrumented tests, needs an emulator/device and the Firebase emulators
```

- Dependency versions are locked (`app/gradle.lockfile`, `settings-gradle.lockfile`). After adding or updating a dependency in `gradle/libs.versions.toml`, run `./gradlew :app:dependencies --write-locks` and commit the updated lock files with your change.

## Commits

- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `test:`, `refactor:`, `chore:`, `ci:`, `docs:`, followed by a short imperative summary (e.g. `feat: add one-tap fatigue logging`).
- Titles: **50 characters or less** (72 at most, GitHub cuts longer ones), imperative, no final period. If needed, a body after a blank line, wrapped at 72 characters, explaining **why**.
- **One logical change per commit, with its tests**: something you could revert on its own, that builds and passes its tests by itself. Not one commit per file, and not `feat: add X` then `test: add tests for X`.
  - If the title needs "and" (`feat: add X and fix Y`), it's two commits.
  - Use `test:` only when tests are the change itself: fakes, test helpers, or tests for existing code.
  - A typical PR has 1 to 3 commits, e.g. `feat: add symptom repository` then `feat: add symptom journal screen`.
- Before the first review, you may rewrite your branch (amend, squash, rebase on `main`) and `git push --force-with-lease`.
- **Once a review has started, only add commits** (e.g. `fix: keep the given name after sign-in`), and merge `main` into your branch instead of rebasing, so reviewers see what changed and nobody has to force-push.

## Pull requests

- Never push to `main`. Work on a branch and open a pull request. Merging needs one approving review and a green CI (tests + SonarCloud).
- Branch names: `feature/<short-name>`, `fix/<short-name>`, `chore/<short-name>`, `ci/<short-name>`, `docs/<short-name>`. Never rename the branch of an open PR: GitHub closes the PR.
- PR titles follow the same convention as commit titles.
- Fill in the [PR template](.github/PULL_REQUEST_TEMPLATE.md). Put `Closes #<issue>` in the description so the issue closes on merge.
- Keep PRs small and focused on one task from the Scrum board: about **400 lines of production code** at most, tests not counted. Split the data layer (model, repository, rules) from the UI when a task needs both.
- Keep at most 2 to 3 of your own PRs waiting for review; get them merged before opening more.
- Before opening a PR: merge the latest `main`, run the checks above, tick the definition of done.

### Avoiding merge conflicts

The same files change in almost every PR: `strings.xml`, `C.kt`, `AppNavHost.kt`, `AppNavHostTest.kt`, `firestore.rules`.

- Keep one commented section per feature in `strings.xml` and `C.kt`, and add lines only inside your feature's section (at its end).
- In `AppNavHost.kt` and `firestore.rules`, only touch your feature's block.
- When these files conflict, the fix is almost always to keep both sides. Run the tests after resolving.

## Reviews

- Use **Request changes** for bugs, missing tests, and convention problems (naming, commits, architecture); **Comment** for suggestions.
- When you address a comment, reply on its thread with the commit that fixes it (e.g. "Done in 3f6531b"). Let the reviewer resolve the thread.
- If you disagree, explain why in the thread instead of ignoring the comment.
