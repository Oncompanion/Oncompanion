# AGENTS.md

Rules for coding agents (Claude Code, Codex, Copilot, etc.) working on Oncompanion.
Humans on the team follow the same rules. See [README.md](README.md) for what the app does and
[docs/architecture.md](docs/architecture.md) for the architecture and the Firestore layout.

## Project context

- Android app in Kotlin with Jetpack Compose (Material 3). `minSdk 28`, `targetSdk 34`, JVM target 17.
- Backend: Firebase Authentication (Google Sign-In), Cloud Firestore, and Cloud Functions in TypeScript (`functions/`, the hospital search proxy, checked with App Check). Firebase project: `oncompanion-c4144`.
- Package root: `com.github.se.oncompanion`. New user-visible text writes the app's name **oncompanion**, all lowercase (never "OnCompanion").
- Users are cancer patients in active therapy. **The app is an organizational tool, not a medical device**: never add code that diagnoses, gives medical advice, or changes a treatment. Anything drafted automatically (e.g. from a scanned prescription) must be confirmed by the user before it is saved.

## Before you start a task

1. Read the issue and its definition of done. If you will do something different, say so in the PR.
2. Read [docs/architecture.md](docs/architecture.md) before adding a feature, a collection or a use case.
3. Look for what already exists before writing something new: fakes in `app/src/test`, test helpers in `app/src/androidTest/.../utils`, shared composables in `ui/common`, repositories in `model/`. In particular:
   - rules tests: `allowed()` / `denied()` in `utils/SecurityRulesAssertions.kt`, and the care circle paths (`circle()`, `member()`, `joined()`) in `model/carecircle/CareCirclePaths.kt`;
   - the scan: what ML Kit read on the fictional test prescriptions (`ScannedSheets.sheet(n)`, `app/src/test`).
4. Only change files the task needs. No drive-by refactors or reformatting of unrelated code.
5. Ask the team before adding a dependency, changing the build or CI, or changing a convention in this file.

## Architecture

Follow MVVM, one feature per package:

```
com.github.se.oncompanion/
  model/<feature>/   data classes + repository interface + Firestore implementation + provider
  domain/<feature>/  use cases, only when the logic combines several repositories (see architecture.md)
  ui/<feature>/      @Composable screens + their ViewModel
  ui/common/         composables shared by several features
  ui/navigation/     navigation graph and routes
  ui/theme/          colors, typography, theme
  resources/C.kt     test tags
```

- **Model**: plain data classes. Repositories hide Firebase behind an interface (e.g. `SymptomRepository` / `SymptomRepositoryFirestore`) so ViewModels can be tested with a fake.
  - Repositories access Firebase **lazily** (`by lazy`), never in their constructor: each provider builds its repository once, the first time it is used (e.g. by a ViewModel default), and Firebase isn't initialized in Robolectric tests.
- **ViewModel**: exposes UI state as one `StateFlow<XUiState>`, takes its repositories in the constructor, no Android `Context` and no Compose imports.
- **View**: each screen is split in two:
  - `XScreen(viewModel, onSomething: () -> Unit, ...)` collects the state and passes events to the ViewModel.
  - `XContent(uiState, onEvent...)` is stateless. Tests use it to check every state without Firebase.
  - No Firebase calls from composables.
  - **Landscape is supported** on every screen: no portrait lock. Check the layout held sideways (the M1 APK test found date pickers, Profile, Planning and the camera broken in landscape).
  - A flow the user is pushed into (onboarding, a scan, a form) always offers a way back or out (M1: no way back to choose another Google account).
  - A temporary message (snackbar) never covers an action, and goes away on its own (M1: the scan error covered Retake).
  - The add, edit and detail views of the same item share their components and show the fields in the same order (M1 report).
  - **Reuse shared components** from `ui/common` rather than building one per screen, so the same element looks and behaves the same everywhere (M1 report: two different time pickers): `DayPickerDialog`, `SaveButton`, `DetailActions`, `DeleteConfirmationDialog`, `ClickableReadOnlyField`, `isCompactHeight()`.
  - **Keyboard**: `MainActivity` is `adjustResize`, so a screen with text fields puts `imePadding()` on its Scaffold, or the keyboard covers its bottom. If it also pins a top bar or a button, it lets them scroll on short windows (`isCompactHeight()` in `ui/common`), so the field being typed stays visible in landscape.
    - A screen with text fields that leaves out `imePadding()` on purpose says why in a comment on its Scaffold. `adjustResize` is set once for the whole app; a screen can't opt out of it.
- **Navigation**: each feature is a nested graph. Add its route to `Route`, its screens to `Screen` (`ui/navigation/NavigationActions.kt`), and a `navigation(...)` block in `ui/navigation/AppNavHost.kt`.
  - Screens receive callbacks (`onBack`, `onSaved`...) or a `NavigationActions`, never the `NavController`.
  - A screen opened from another screen has a back arrow in its top bar (`onBack = navigationActions::goBack`).
  - Use `navigateAndClearBackStack` when Back must not return to the previous flow (e.g. after sign-in or onboarding).
- **Dependency injection** is manual, through one provider object per repository:
  - Every repository comes with its provider, next to the interface: `object XRepositoryProvider { @Volatile var repository: XRepository = XRepositoryFirestore() }`. Add it in the PR that adds the repository. (If the default is built in `domain/`, the provider lives there.)
  - ViewModels and use cases take their repositories as plain constructor parameters with the provider's as default (`repo: XRepository = XRepositoryProvider.repository`). Only a provider names an implementation: never build `XRepositoryFirestore()` in a ViewModel, a use case or a composable.
  - `AppNavHost` takes no repositories: its ViewModels use their defaults.
  - Tests pass fakes to the constructor. A test that can't reach the constructor (e.g. one showing `AppNavHost`) sets the provider's `repository` and puts the original back in `@After`.
  - No Hilt, no lambdas.

## Code style

- **AI acknowledgment (required by the course)**: an AI that writes code is a contributor, so credit it at the top of the file, above `package`, with one `Co-authored-by` line per AI. This applies to every source file (Kotlin, Gradle scripts, `firestore.rules`, workflows; `#` instead of `//` in YAML). A missing line counts as plagiarism.
  - Template: `// Co-authored-by: <AI name> <contact email, if any>`
  - When you create a file, or change one with an AI that isn't credited yet, add its line. Examples:
    ```kotlin
    // Co-authored-by: Claude Code <noreply@anthropic.com>
    // Co-authored-by: ChatGPT <noreply@openai.com>
    // Co-authored-by: GitHub Copilot <copilot@github.com>

    package com.github.se.oncompanion
    ```
- KDoc on every public class and function: what it does and why, not how.
- A short one-line comment above each non-obvious block (what this part of the screen or function does), so reviewers can find their way quickly. No comments that repeat the code.
- All user-visible text goes in `res/values/strings.xml`, in English. Escape apostrophes: `Couldn\'t`.
- Every element that a test needs to find gets a test tag declared in `resources/C.kt`, named `<feature>_<element>`.
- **Logging**: never log secrets or personal data (invite codes, tokens, emails, health details): log what failed, not the value. An exception's message can contain the value (e.g. a failed write names its document path, `invites/<code>`, #288): log the exception's type, not its message, when the path or message can hold a secret. Uids and generated record IDs may be logged: they identify nothing outside the app.
- Dates: store calendar days at midnight `Europe/Zurich` so they never shift by a day; display dates and times in the phone's time zone (`ZoneId.systemDefault()`), never in UTC.

## Firebase and data

- **Sign-out**: a new Firestore repository or local cache goes through `SessionAccess` (`operation`, `snapshots`, the shared instance accessor), as described in the "Sign-out" section of [docs/architecture.md](docs/architecture.md): otherwise its listeners and writes survive sign-out, and the next account can see them.
- Personal data lives under `/users/{uid}/...`. Every collection needs its own `match` in [firestore.rules](firestore.rules) with validation that matches the Kotlin class (fields, types, length limits); anything not matched is denied. Never add a catch-all or loosen a rule to make something "just work".
- Rules changes come with rule tests on the Firestore emulator (allowed and denied cases).
- Offline mode is a requirement:
  - Writes return once saved on the device (don't wait for the server), and reads use Firestore's local cache. Never block the UI waiting for the network.
  - When offline, Firestore serves the cache instead of failing: a Firestore error is not a connection problem, so don't show "check your connection" for it.
- `app/google-services.json` is committed on purpose (client config, not a secret). Never commit service account keys, admin SDK credentials, or `local.properties`.
- Use the Firebase emulator for tests that touch Firestore or Auth, never the production project.

## Tests

- New code needs tests: SonarCloud's quality gate requires **≥ 90% coverage on new code** (M2 requires 90% overall). Don't add coverage exclusions to `app/build.gradle.kts` to make the gate pass; ask the team first.
- Write tests from the issue's definition of done and the expected behavior, not by reading the implementation.
- Test the different states of a screen (loading, empty, error, filled) on `XContent`, plus one test of `XScreen` with its ViewModel. Compose adds hidden branches, so "it renders" isn't enough.
- Where tests go:
  - Unit and Robolectric tests in `app/src/test`; instrumented Kaspresso/Compose and emulator tests in `app/src/androidTest`.
  - One fake per repository, shared by every test: `app/src/test/.../model/<feature>/Fake<Name>Repository.kt`. Reuse it rather than writing a local one.
- A screen with text fields gets a test with the keyboard open, in portrait and landscape (`showKeyboard`, `assertIsAboveKeyboard` and `LANDSCAPE_PHONE` in `app/src/test/.../ui/KeyboardInsets.kt`).
- Every screen with state gets a **rotation test**: what the user typed or chose survives the activity being recreated.
  - With a ViewModel: `createAndroidComposeRule<ComponentActivity>()`, then `compose.activityRule.scenario.recreate()` and **set the content again** with the same ViewModel (`compose.activity.setContent { ... }`), or the screen is blank (see `ScanScreenTest`).
  - For state kept in the composition (`rememberSaveable`) on `XContent`: `StateRestorationTester` (see `PlanningScreenTest`).
- Every screen and dialog gets a test at `LANDSCAPE_PHONE`: its main actions can be reached and are fully visible (`assertIsFullyVisible`). Dialogs are also tested at `SMALL_LANDSCAPE_PHONE` (both in `ui/KeyboardInsets.kt`). A rotation test checks state, not layout: it wouldn't catch overlapping buttons.
- Anything a user can add (a symptom, a member, a prescription...): the issue says whether a duplicate is rejected, merged, warned about or allowed, and a test checks that behavior.
- None of the bugs found in the M1 APK test was caught by a test (keyboard, landscape, duplicates, no way back, a message covering a button).
- **Fix the whole bug**: before fixing a bug, look for the same pattern on the other screens, fix them too, and add a regression test (#170 fixed the keyboard on the other forms but not Log symptom).
- Tests worth their lines: cases that differ only by their input go in one test that runs through a list of cases (`for ((input, expected) in cases)`), not one test each. A fake is never tested on its own: test the code that uses it (cleanup in #296).
- A feature that works with others extends `M1EndToEndTest` (`app/src/androidTest`): the graders assess it directly.
- Coroutines: use `StandardTestDispatcher` with `runTest` and virtual time (`advanceTimeBy`, `advanceUntilIdle`). Never `Thread.sleep` or real delays.
- While working, run only the tests you touch (`./gradlew testDebugUnitTest --tests '*Symptom*'`). The full checks below take about 10 minutes; run them before pushing.

## Build, format, test

Run these before every push and make sure they pass:

```bash
./gradlew ktfmtFormat      # format Kotlin sources (CI fails on ktfmtCheck otherwise)
./gradlew check            # unit + Robolectric tests, lint
./gradlew connectedCheck   # instrumented tests, needs an emulator/device and the Firebase emulators
```

- If you change `functions/`, also run what CI runs on it: `npm --prefix functions ci`, `npm --prefix functions run format:check`, `npm --prefix functions run lint`, `npm --prefix functions run build`, and its tests on the Firestore emulator (the last step of the `hospital-proxy` job in `ci.yml`).
- CI runs three jobs in parallel on every PR (`.github/workflows/ci.yml`):
  - **Hospital proxy**: the `functions/` checks above.
  - **Checks**: ktfmt, lint, unit + Robolectric tests, and the release build. A formatting, lint or unit test error shows up here in a few minutes.
  - **CI**: the connected tests on an emulator with the Firebase emulators, the coverage report and SonarCloud, which gets the lint report and the unit test coverage from Checks.
  - The signed release APK is uploaded on `main` only, and every run keeps its test results (with each test's time) for 7 days.
- `gradle/gradle-daemon-jvm.properties` pins the Gradle daemon to Java 25, so everyone's builds run on the same Java. The first build downloads it into `~/.gradle/jdks`; on CI it's already installed.
- Dependency versions are locked (`app/gradle.lockfile`, `settings-gradle.lockfile`). After adding or updating a dependency in `gradle/libs.versions.toml`, run `./gradlew :app:dependencies --write-locks` and commit the updated lock files with your change.

## Issues and the Scrum board

- **Titles**: a user story is `US-<n>: <what the user can do>` (e.g. `US-39: Be warned before adding a prescription I already have`); a bug is `fix: <what to fix>`; a technical task is an imperative sentence (e.g. `Make CI faster`).
- Use the issue templates. A task belongs to a user story: link it as a **sub-issue** of the story and write `Part of US-<n>`. A technical task writes "Parent user story: None (technical task)".
- **Labels**: `enhancement` for stories and tasks, `bug` for bugs, `documentation` for docs, plus the feature's `epic: <feature>` label.
- **Every item on the board, stories included,** has its board fields: `Nature` (User Story or Sprint Task), `Epic`, `Priority`, and for a task `Types`, an estimate and an assignee. Its `Sprint` matches its column: empty in the Product Backlog, the current sprint in Sprint Backlog, In Development and In review, the sprint it was done in for Done. Fill in **Actual Time** when you close a task, so we can check our estimates.
- **The status follows the work**, and each person moves their own items: Product Backlog → Sprint Backlog → In Development (when you start) → In review (when the PR opens) → Done in S<n> (when it's merged).
- User stories are on the board next to their tasks. The Product Backlog is ordered by priority, P0 first.
- **The Sprint Backlog is ready before the sprint starts**, holds only the current sprint (later work stays in the Product Backlog), and every member has a task in it: it is a milestone deliverable, and being late costs 2% of the team's grade per hour.

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
- Keep PRs small and focused on one task from the Scrum board: about **400 lines of production code** at most, and about **1,000 changed lines in total**, tests included. Generated files (lock files, diagram SVGs) and test data (fixtures) don't count. Split the data layer (model, repository, rules) from the UI when a task needs both.
  - A PR over these limits says why in its description: a mechanical refactor, a rename, code moved without change, a dependency update.
- Keep at most 2 to 3 of your own PRs waiting for review; get them merged before opening more.
- Before opening a PR: merge the latest `main`, run the checks above, tick the definition of done.
- If your PR adds or changes a screen, use case or repository, or builds something drawn as planned, update its feature diagram and the overview in `docs/` in the same PR (see "To edit a diagram" in [docs/architecture.md](docs/architecture.md)).
  - The overview diagram shows the core flows with arrows, so someone new understands the app from it alone; details stay in the feature diagrams, and a box appears once per diagram (M1 report).
  - Agents update the diagrams themselves by editing the `.excalidraw` JSON:
    - Copy an existing box, label or arrow that already has the style you need (see the legend in architecture.md), give the copy a new unique `id`, then change its position and text. Keep the layout: screens on top, then use cases, repositories and the backend, and no crossing arrows.
    - A box's label is a separate `text` element whose `containerId` is the box: `x` = box `x` + 10, `width` = box width − 20, `height` = number of lines × 25, centered vertically in the box. Set both `text` and `originalText`.
    - Arrows point to boxes with `startBinding` / `endBinding`, and their `points` are relative to the arrow's `x`, `y`. Every box lists its label and arrows in `boundElements`; keep both sides in sync when you add or remove one.
    - Regenerate the SVGs with `npm ci && npm run export` in `scripts/diagrams`, render the changed ones to an image and look at them (text inside its box, nothing overlapping) before committing.
    - Say in the PR which diagrams changed, so the reviewer checks them.

### Avoiding merge conflicts

The same files change in almost every PR: `strings.xml`, `C.kt`, `AppNavHost.kt`, `AppNavHostTest.kt`, `firestore.rules`.

- Keep one commented section per feature in `strings.xml` and `C.kt`, and add lines only inside your feature's section (at its end).
- In `AppNavHost.kt` and `firestore.rules`, only touch your feature's block.
- When these files conflict, the fix is almost always to keep both sides. Run the tests after resolving.

## Reviews

- Use **Request changes** for bugs, missing tests, and convention problems (naming, commits, architecture); **Comment** for suggestions.
- Check the PR's size: above the limits in "Pull requests", without a reason in its description, ask for a split.
- Read SonarCloud's list of new issues, not only the quality gate: a PR should add **0 new issues** (bugs, code smells, security hotspots). Ask for each one to be fixed, with its file and line.
- A new commit pushed after a review makes that review no longer count: the author re-requests it.
- When you address a comment, reply on its thread with the commit that fixes it (e.g. "Done in 3f6531b"). Let the reviewer resolve the thread.
- If you disagree, explain why in the thread instead of ignoring the comment.
