# Architecture

Oncompanion follows MVVM with three layers. Arrows point from the caller to what it depends on: the UI calls the domain layer (or the data layer directly when there is no logic to share), and nothing below ever calls up.

The overview shows every part of the app in its layer. Each feature then has its own diagram below, read from its screens (top) down to the backend (bottom).

![Oncompanion architecture overview](architecture-overview.svg)

Solid green = in the code, dashed green = partly built, grey dotted = a fake or empty stand-in for now, dashed white = planned. In the feature diagrams, solid arrows are calls the code makes today and dotted arrows are planned ones.

To edit a diagram, open its `.excalidraw` file on [excalidraw.com](https://excalidraw.com) (Menu → Open), make your changes, then save it back to the same file and export it with *Export image → SVG* (keep "Embed scene" off) to overwrite its `.svg`. Coding agents edit the `.excalidraw` JSON directly instead (see [AGENTS.md](../AGENTS.md)) and regenerate the `.svg` files with `npm ci && npm run export` in `scripts/diagrams` (Node 20 or later), which gives the same result as excalidraw.com. Commit both files in the same PR as the code change, and update the markings when something planned gets built.

## Layers

**UI layer** (`ui/<feature>/`). Each screen is a stateless composable plus a ViewModel that exposes a `StateFlow<UiState>`. ViewModels take their repositories and use cases in the constructor, so tests pass fakes. No Firebase or Android `Context` here. Overview, Planning and Events are the bottom-bar tabs. Overview shows what comes next (the next appointment, medication intake or Ligue event) by taking the first upcoming item from `ObservePlanning`. It is read-only: symptoms are logged from the symptom tracker, not from Overview.

**Domain layer** (`domain/<feature>/`, optional). One class per use case with a single `operator fun invoke(...)`. Only add one when the logic combines several repositories or device services, or is worth unit-testing on its own. Plain reads and writes (directory, events, appointments list) go straight from the ViewModel to the repository.

- `LogSymptom` turns a one-tap entry or a voice transcript into a symptom entry for the right patient, so one-tap entries and the voice journal in the symptom tracker share the same logic.
- `ManageMedicationSchedule` saves a confirmed prescription with its medications and keeps their reminders in sync. All medication writes go through it, so reminders never drift from the stored schedule.
- `DraftMedicationFromScan` returns a *draft* with low-confidence fields flagged. Nothing is saved until the patient confirms it on the review screen.
- `GenerateAppointmentSummary` gathers the symptoms and medications for an upcoming appointment into a summary the patient can show on screen or export as a PDF (through `PdfExporter`). The patient chooses what goes in it, and it only reports what they logged.
- `ObservePlanning` is the planned integration that will merge appointments, medications and Ligue events for Planning. `AppointmentPlanningSource` implements the `PlanningRepository` read contract for the signed-in patient: it adapts cached appointment observations to the requested date range and clears entries when the uid changes. Production Planning uses this source; its ViewModel separately observes authentication and restarts the agenda on uid changes, so read failures cannot leave the previous patient's entries visible after an account change. Live aggregation with other features is not implemented yet. Planning has no store of its own: each `PlanningSource` references the feature that owns the data and handles editing.
  - `PlanningTiming.Timed` holds an instant for an appointment or event. `DateOnly` holds a calendar date for an active medication; it is not a scheduled intake, and its day does not shift with the display zone. Medication frequency is recorded free text and is never interpreted into times or doses.
  - `PlanningRange` specifies inclusive/exclusive calendar dates and a display zone, with derived instant boundaries for timed queries. Future adapters emit only occurrences in that range. Medication expansion must be bounded to it, using `startDate` and `durationDays`; a row describes the recorded active period, not a claim that a dose is due on that day. Non-daily free-text frequency remains visible without interpreting it.
  - The selected-day agenda shows date-only medications first under “No set time”, then appointments/events under “Scheduled” in time order. Keys include source IDs and occurrence date/time, independent of editable text. Frequency is separate from appointment/event subtitle text.
  - Future aggregation reads `MedicationRepository.observeMedications(uid)`, never Firestore directly. It must cancel/clear patient-specific observations on account changes and use local-cache flows. Events need a defined source time zone and a range-capable repository for past-week browsing; the current upcoming-only event contract does not provide history. Overview's current time-required model must also support date-only entries before sharing this data. No fake medication time or taken/overdue status is introduced.
  - Repositories are injected through the existing screen/ViewModel boundary. Tests and previews can supply mixed entries; production supplies appointments through `AppointmentPlanningSource`. No new collection, layer or dependency is introduced by the mixed-entry contract.
- `ResolveCareCircleAccess` decides whose data the current user is looking at (their own, or a patient who shared with them) and what they are allowed to see. Every screen that shows patient data asks it for the target `uid`, so caregiver mode is not a separate code path.

**Data layer** (`model/<feature>/`). Data classes, a repository interface, and its Firestore implementation (`SymptomRepository` / `SymptomRepositoryFirestore`). Ligue events have their own `EventRepository`, separate from the directory of contacts and programs. Device services (`TextRecognizer`, `SpeechRecognizer`, `ReminderScheduler`, `PdfExporter`) follow the same interface + implementation pattern, so domain code never touches ML Kit or Android APIs directly.

## External hospital search (issue #142)

`model/hospital/HospitalRepository` accepts explicitly submitted search text and returns
`HospitalSearchResult`: a list of hospitals (possibly empty), or a typed network, service,
rate-limit or invalid-response error. Future ViewModels inject this interface; screens never
access HTTP. `HospitalRepositoryNominatim` uses platform `HttpURLConnection` on the IO
dispatcher and Android JSON parsing, with no new dependency. `FakeHospitalRepository` in
`app/src/test/.../model/hospital` supplies configurable outcomes and records submitted queries.
Hospital identities use OSM object type and ID rather than provider-specific place IDs.

The [official Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/)
prohibits autocomplete. The future field (#143) must use an explicit Search button, with no
keystroke, background, periodic or automatic nearest-hospital requests. Only public hospital
names and locations may be submitted; never include patient details or confidential data.
Every results view must display `© OpenStreetMap contributors`, linked to
[the copyright and ODbL information](https://www.openstreetmap.org/copyright), including cached
results. The repository exposes attribution constants for consumers; #142 adds no UI.

The HTTP implementation identifies itself as `Oncompanion/1.0` with the project URL, sets
10-second connection/read timeouts, disables redirects and automatic retries, and bounds
response size. All repository instances share a process-wide mutex with a conservative
one-second gap after a request completes (including failures). Each instance caches the last
50 successful distinct queries, including empty results, for its lifetime. Failures are not
cached and cancellation propagates. Cache misses require connectivity; this is not a Firestore
store and does not change essential offline features.

**Deployment prerequisite:** the public service's maximum of one request per second applies
to aggregate traffic across all app users, not each device. A local throttle cannot enforce
that limit. The constructor therefore requires an HTTPS search endpoint instead of defaulting
to the public service. Before wiring this into a released UI, supply a deployment-configured
proxy that enforces the aggregate limit, caches requests and can switch its upstream provider
without an app update, as the policy requires. Proxy deployment/configuration is outside #142;
the repository is not yet wired into production. No profile fields, Firestore rules, hospital
field UI (#143), or profile integration (#144) are added.
## Feature diagrams

`ResolveCareCircleAccess` is drawn in the Care circle diagram. The other diagrams show it as one planned box, linked to every screen that shows patient data.

### Account

![Account: sign-in, onboarding and profile](architecture-account.svg)

### Care circle

![Care circle](architecture-care-circle.svg)

### Symptoms

![Symptoms](architecture-symptoms.svg)

### Medications

![Medications](architecture-medications.svg)

### Planning and Overview

![Planning and Overview](architecture-planning.svg)

### Appointments and summary

![Appointments and summary](architecture-appointments.svg)

### Ligue events and directory

![Ligue events and directory](architecture-ligue.svg)

## Offline mode

Firestore's local cache is the offline store: writes are applied locally right away and synced when the connection returns, and repositories expose `Flow`s from snapshot listeners so the UI updates either way. No separate Room database is needed. Text recognition, reminders and on-device speech work without a network.

## Appointment storage

Appointments are stored under `/users/{uid}/appointments/{id}` and observed through Firestore's local cache. `scheduledAt` represents an actual instant rather than a calendar-only date; callers must cancel or replace patient-specific observations when the account changes.

## Sign-out

Profile asks for confirmation before reusing `AuthRepository.signOut()`. Its ViewModel drops profile details and cancels the profile listener as soon as Firebase sign-out returns, then asks the existing Google credential provider to clear the account-picker state. Credential cleanup is best effort, as in sign-in; an immediate authentication error keeps the user on Profile with retry available.

The host opens the Auth graph with `navigateAndClearBackStack`. That helper also clears saved bottom-tab stacks, so both active and saved authenticated ViewModels are destroyed and cannot be restored by Back or a later session. Profile observation is keyed to the currently authenticated uid; with no signed-in user it never requests a cached profile.

Firestore disk persistence is retained, including pending offline writes. Signing out makes cached data inaccessible through the app's navigation and Profile state; it does not securely erase the SDK's disk cache. `clearPersistence()` requires a stopped Firestore instance and also deletes pending writes, so physical cache clearing from US-19 subtask #35 needs a separate decision compatible with offline mode. No Firebase rules or persistence settings are changed by sign-out.

## Firestore layout

```
/users/{uid}                          profile (owner only)
/users/{uid}/symptoms/{id}
/users/{uid}/prescriptions/{id}       doctor, date and the list of its medications
/users/{uid}/appointments/{id}
/users/{uid}/circle/{memberUid}       permissions granted to a care-circle member
/invites/{code}                       pending care-circle invites
/events/{id}                          Ligue events, read: signed in, write: nobody from the app
/directory/{contacts|programs}/items/{id}   read: signed in, write: staff
```

Care-circle members read a patient's data through rules that check `/users/{uid}/circle/{request.auth.uid}`. Staff status comes from a custom claim set by the team, never from a field users can write.

A prescription is one document: the prescribing doctor and the date, stored once, and the list of its medications, each with its own ID, start date and duration. A prescription and its medications are always written together (one form, one use case), so keeping them in one document makes saving, replacing or deleting a prescription a single write. It can't be half saved, and its medications can't be partly known by a device that is offline, mixed when two devices edit at once, or left behind after a deletion. Planning, reminders and the appointment summary still work on single medications: the repository reads them out of the prescriptions, so they never deal with a prescription. Anything recorded per intake later (e.g. a medication marked as taken) would go in its own collection, not in the prescription.
