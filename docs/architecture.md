<!-- Co-authored-by: Claude Code <noreply@anthropic.com> -->
<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->
<!-- Co-authored-by: Codex <noreply@openai.com> -->

# Architecture

Oncompanion follows MVVM with three layers. Arrows point from the caller to what it depends on: the UI calls the domain layer (or the data layer directly when there is no logic to share), and nothing below ever calls up.

The overview shows every part of the app in its layer. Each feature then has its own diagram below, read from its screens (top) down to the backend (bottom).

![Oncompanion architecture overview](architecture-overview.svg)

Solid green = in the code, dashed green = partly built, grey dotted = a fake or empty stand-in for now, dashed white = planned. In the feature diagrams, solid arrows are calls the code makes today and dotted arrows are planned ones.

To edit a diagram, open its `.excalidraw` file on [excalidraw.com](https://excalidraw.com) (Menu → Open), make your changes, then save it back to the same file and export it with *Export image → SVG* (keep "Embed scene" off) to overwrite its `.svg`. Coding agents edit the `.excalidraw` JSON directly instead (see [AGENTS.md](../AGENTS.md)) and regenerate the `.svg` files with `npm ci && npm run export` in `scripts/diagrams` (Node 20 or later), which gives the same result as excalidraw.com. Commit both files in the same PR as the code change, and update the markings when something planned gets built.

## Layers

**UI layer** (`ui/<feature>/`). Each screen is a stateless composable plus a ViewModel that exposes a `StateFlow<UiState>`. ViewModels take their repositories and use cases in the constructor, so tests pass fakes; the defaults come from one provider object per repository (`XRepositoryProvider.repository`), the only place that names an implementation. No Firebase or Android `Context` here. Overview, Planning and Events are the bottom-bar tabs. Overview shows what comes next (the next appointment, medication intake or Ligue event) by taking the first upcoming item from `ObservePlanning`. It is read-only: symptoms are logged from the symptom tracker, not from Overview.

**Domain layer** (`domain/<feature>/`, optional). One class per use case, usually with a single `operator fun invoke(...)`. Only add one when the logic combines several repositories or device services, or is worth unit-testing on its own. Plain reads and writes (directory, events, appointments list) go straight from the ViewModel to the repository.

- `LogSymptom` is planned with the voice journal. The add symptom form saves through `SymptomRepository` directly for now: it only uses that repository, so there is no logic to share yet. `LogSymptom` will turn a voice transcript into a draft of the same form, which the patient confirms before it is saved, and log it for the right patient once caregivers can log on a patient's behalf. The form's rule for when a symptom was felt (the pure function `occurredAt` in `AddSymptomViewModel.kt`) then moves to `domain/symptom` with it, so both use the same one.
- `ManageMedicationSchedule` saves a confirmed prescription with its medications and keeps their reminders in sync. All medication writes go through it, so reminders never drift from the stored schedule; that is why it exposes three named operations (`add`, `update`, `delete`) instead of a single `invoke`.
- `DraftPrescriptionFromScan` reads the photo of a scan with the `TextRecognizer`, then returns a *draft* with the details to check flagged (`readPrescription`), or nothing when nothing could be read or reading failed. Nothing is saved until the patient confirms the draft in the form. It is in `domain/` because it builds a `PrescriptionDraft`, while the reading steps it relies on (below, "Prescription scan") are in `model/`.
- `GenerateAppointmentSummary` gathers the symptoms and medications for an upcoming appointment into a summary the patient can show on screen or export as a PDF (through `PdfExporter`). The patient chooses what goes in it, and it only reports what they logged.
- `ObservePlanning` merges the Planning sources into one list sorted by day and time: appointments (`AppointmentPlanningSource`) and Ligue events (`EventPlanningSource`, every upcoming event until US-30 keeps only the confirmed ones). Medications are planned. Ligue events are optional: `PlanningRepositoryProvider` wraps them in `OptionalPlanningSource`, so if they fail to load they are left out and Planning still shows the rest. Appointments, and medications once added, are essential: if one of them fails, the whole list fails and Planning offers a retry, rather than a calendar that silently misses a medical appointment. `AppointmentPlanningSource` implements the `PlanningRepository` read contract for the signed-in patient: it adapts cached appointment observations to the requested date range and clears entries when the uid changes. Production Planning uses `ObservePlanning` over both sources through `PlanningRepositoryProvider`. `PlanningViewModel` defaults to the planning and auth providers, while navigation supplies only its saved state; its clock and display zone default to the system settings. Its ViewModel separately observes authentication and restarts the agenda on uid changes, so read failures cannot leave the previous patient's entries visible after an account change. Medications are not aggregated yet. Planning has no store of its own: each `PlanningSource` references the feature that owns the data and handles editing.
  - `PlanningTiming.Timed` holds an instant for an appointment or event. `DateOnly` holds a calendar date for an active medication; it is not a scheduled intake, and its day does not shift with the display zone. Medication frequency is recorded free text and is never interpreted into times or doses.
  - `PlanningRange` specifies inclusive/exclusive calendar dates and a display zone, with derived instant boundaries for timed queries. Future adapters emit only occurrences in that range. Medication expansion must be bounded to it, using `startDate` and `durationDays`; a row describes the recorded active period, not a claim that a dose is due on that day. Non-daily free-text frequency remains visible without interpreting it.
  - The selected-day agenda shows date-only medications first under “No set time”, then appointments/events under “Scheduled” in time order. Keys include source IDs and occurrence date/time, independent of editable text. Frequency is separate from appointment/event subtitle text.
  - Future aggregation reads `MedicationRepository.observeMedications(uid)`, never Firestore directly. It must cancel/clear patient-specific observations on account changes and use local-cache flows. Events need a defined source time zone and a range-capable repository for past-week browsing; the current upcoming-only event contract does not provide history. The Overview reads the same planning data: the next appointment and today's entries, date-only ones without a time or a status. No fake medication time or taken/overdue status is introduced.
  - Repositories are injected through the existing screen/ViewModel boundary. Tests and previews can supply mixed entries; production supplies appointments through `AppointmentPlanningSource`. No new collection, layer or dependency is introduced by the mixed-entry contract.
- `ResolveCareCircleAccess` decides whose data the current user is looking at (their own, or a patient who shared with them) and what they are allowed to see. Every screen that shows patient data asks it for the target `uid`, so caregiver mode is not a separate code path.

**Data layer** (`model/<feature>/`). Data classes, a repository interface, and its Firestore implementation (`SymptomRepository` / `SymptomRepositoryFirestore`). Ligue events have their own `EventRepository`, separate from the directory of contacts and programs. The patient's confirmations that they registered for an event with its organizer (US-25) are in `EventRegistrationRepository`: one document per event, keyed by the event's id, so confirming twice never creates a duplicate. Oncompanion only records what the patient says; registering stays on the organizer's website, linked from the event's `websiteUrl`. Device services (`TextRecognizer`, `SpeechRecognizer`, `ReminderScheduler`, `PdfExporter`) follow the same interface + implementation pattern, so domain code never touches ML Kit or Android APIs directly.

## External hospital search (issue #142)

`model/hospital/HospitalRepository` accepts explicitly submitted search text and returns
`HospitalSearchResult`: a list of hospitals (possibly empty), or a typed network, service,
rate-limit or invalid-response error. Future ViewModels inject this interface; screens never
access HTTP. `HospitalRepositoryNominatim` uses platform `HttpURLConnection` on the IO
dispatcher and Android JSON parsing, with no new dependency. `FakeHospitalRepository` in
`app/src/test/.../model/hospital` supplies configurable outcomes and records submitted queries.
Hospital identities use OSM object type and ID rather than provider-specific place IDs.

`HospitalRepositoryProvider` follows #177's replaceable `@Volatile repository` convention.
Deployment wiring can call `configure(searchEndpoint)` before creating field ViewModels to install
one shared `HospitalRepositoryNominatim`, preserving its cache across consumers. There is no public
service URL or placeholder URL by default: until configured, blank searches return an empty success
and other searches return the existing service error without HTTP. Tests replace `repository` with
`FakeHospitalRepository` and restore the original in `@After`. Configuring the provider does not
implement or deploy the required proxy.

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

## Reusable hospital field (issue #143)

`ui/common/HospitalField` is an outlined free-text location field with an explicit Search button.
The form owns its text and structured selection. `onValueChange(String)` supports unrestricted
manual entry. Selecting a result sends its name through `onValueChange`, then the complete existing
`Hospital` through `onHospitalSelected(Hospital?)`. Manual edits send `null` before the text callback
so the parent clears any previous structured selection, including for empty or unmatched text.
Parents also clear their selection when changing text externally and decide which hospital details
to persist. Search outcomes never replace text or emit a selection. `HospitalFieldContent` renders
idle, loading, results, empty and error states without a service. `HospitalFieldViewModel` exposes one state flow and
receives a `HospitalRepository`, defaulting to `HospitalRepositoryProvider.repository`. Tests can
still inject a fake directly. Each field needs its own lifecycle-scoped ViewModel, which retains the
repository captured at construction so later provider replacements do not discard its cache. It never
checks connectivity before searching, so repository cache hits remain available offline. Cache misses report a short error while manual entry stays usable.

Editing, external text changes, disabling or removing the field cancels obsolete requests and
clears feedback. A generation check discards late responses. Successful lookups show linked
OpenStreetMap attribution, including empty and cached results. The field has no screen-specific
navigation, patient data or persistence. Profile (#144) uses its stateless content for manual entry;
Add Appointment integration remains a separate task. The deployment prerequisite for a configurable, aggregate-rate-limited proxy
from #142 still applies.

The overview and Account/Appointment diagrams show the reusable HospitalField →
HospitalFieldViewModel → HospitalRepository path. Edit Profile now uses the manual-only content; its search ViewModel connection and
Add Appointment integration remain planned.

## Optional profile hospital (issue #144)

`UserProfile.hospital` is nullable text, limited to `MAX_HOSPITAL_LENGTH = 100`. Firestore's
`hospital` field is optional: missing, null and empty strings are accepted, with non-string and
oversized values denied by `isValidProfile`. Repository validation uses the same model limit.
Missing fields deserialize to null. As with family name and cancer type, new and updated profiles
write a null field when no hospital is supplied. Edit Profile trims text and saves blank input as
null; updating uses `update()` and retains role, creation time and unrelated document fields.

Profile displays only a nonblank saved hospital. Patient Edit Profile drafts initialize from the
saved hospital, track hospital edits separately from incoming snapshots, and allow adding, editing
or clearing it. Empty hospital text never makes Save unavailable. Input is bounded by the shared
model limit like the existing fields. Caregivers gain no hospital editor.

For M1, Edit Profile reuses `HospitalFieldContent` with `searchEnabled = false`: the Search button
is visible and disabled while text remains editable. It supplies idle feedback and no search action,
so no hospital repository, endpoint or search ViewModel is constructed for this screen. The shared
field's `searchEnabled` parameter defaults to true for other consumers; disabling it also clears
obsolete results in `HospitalField`. Existing Nominatim behavior is unchanged. This integration does
not depend on the pending hospital-provider PR, deploy a proxy or integrate Add Appointment.

The Account diagram shows the built manual-entry connection and planned search connection. The
overview identifies Profile hospital text and the reusable content alongside the search ViewModel.

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

## Appointment form (issues #147 and #206)

Planning's Add action opens `AppointmentFormScreen` on the selected day. The Screen collects one
`AppointmentFormUiState` and delegates to stateless `AppointmentFormContent`. Title and explicit
time are required; date is prefilled. Type, optional location and notes are recorded without
interpreting treatment. The form reuses shared date/Save components and keyboard handling.

Location is temporarily an ordinary optional text field. The team deferred hospital search until
the proxy/service configuration is available; the form makes no hospital requests. Blank location
is saved as null; entered text is trimmed and respects the existing 200-character limit. The
reusable HospitalField remains available for later integration, with no endpoint placeholder or
search dependency added to this form.

AppointmentFormViewModel takes appointment/auth repositories from their providers by default;
tests can still inject fakes directly. PlanningViewModel now follows the same provider convention,
uses system clock/zone defaults and always observes a non-null authentication repository.
AppNavHost passes only saved state or the initial date, never repositories.

A local save returns a saved-day result to the existing Planning entry, selects that day (including
another week), consumes the result and removes the form. The live appointment source supplies the
row; no duplicate Planning write is made. Cancel preserves the original calendar selection.
Immediate failures retain the draft; completion means enqueue, not server acknowledgement.

Local times are resolved in the display zone: spring gaps require correction, autumn repeated
times require an offset choice. Account changes clear private input and suppress stale completion.
Drafts survive rotation via their ViewModel, but process-death restoration is not implemented.

The same form edits an appointment when given its ID. It first reads the signed-in owner's cached
appointment, then fills every field, including the original repeated-hour offset and time precision.
Untouched drafts follow saved changes; edited drafts survive later snapshots and read retries.
Unavailable appointments cannot be saved. Account changes clear private input and cancel old reads;
stale saves cannot navigate. Save updates the existing ID and retains creation metadata, without
waiting for server acknowledgement. Unchanged times preserve the exact original instant.

Editing returns to the existing detail destination; its observation refreshes it. Back then returns
to the originating screen/day. Only creation sends the saved-day result to Planning. Detail uses
shared `DetailWithActions` and `DetailActions`: supported actions stay visible in normal windows
and scroll after the content in compact windows. Edit mode reuses the form's keyboard handling.

## Appointment details (issue #205)

`AppointmentDetailScreen` collects one `AppointmentDetailUiState`; its stateless Content follows
Figma's entry-detail layout using the recorded type, title, start date/time and optional location
and notes. No end time or reminder is inferred. `AppointmentDetailViewModel` reads the appointment
repository directly, cancels old observations and clears private content on account changes.
An unavailable cached snapshot does not confirm server-side deletion; read errors offer retry.
Retry also restarts authentication observation after an auth failure. Both entry points share
a resumed-only navigation guard in AppNavHost. Dates use English, matching Overview, while
times respect the phone clock preference and display zone.
Planning appointment rows and Overview's next-appointment card open the same detail destination.
Overview preserves the source appointment ID rather than deriving it from display text. Back pops
the destination to its origin, retaining Planning's selected day and saved agenda scroll position.
Unsupported Planning rows do not expose a clickable action. Edit opens the shared form; Delete asks for confirmation.

### Appointment edit recovery

The edit form offers Retry only for a failed load. An unavailable appointment keeps its
message and Back without Retry. When authentication is lost, the resumed form opens sign-in
and clears active and saved navigation stacks, matching Join and Whose care. A covered form
waits until it resumes before redirecting.

Appointment deletion (#207) reuses `DeleteState`, `DeleteConfirmation`, `DeleteConfirmationDialog`
and the shared adaptive detail actions. Cancel changes nothing. Confirmation queues repository deletion
for the displayed owner, stops the detail listener to avoid an unavailable-state flash, and returns to
its originating screen. Immediate failure retains the detail/dialog with retry and resumes its listener.
Account/session guards suppress stale success and failure. Completion is delivered on resume only while
this detail is current; Planning keeps its selected day and both calendars update through observations.
A successful enqueue, also offline, does not confirm server acceptance; deferred rejection feedback is
outside this flow (#248).

## Appointment storage

Appointments are stored under `/users/{uid}/appointments/{id}` and observed through Firestore's local cache. `scheduledAt` represents an actual instant rather than a calendar-only date; callers must cancel or replace patient-specific observations when the account changes.

`AppointmentRepository.updateAppointment` updates editable fields on an existing document without
changing its ID or stored `createdAt`. It uses Firestore `update`, never an upsert: a missing document
is not recreated. Writes are queued locally, including offline, without waiting for server acknowledgement.
A later server rejection is logged and may roll back local changes. Owner
updates validate the resulting document and preserve creation metadata. `deleteAppointment` enqueues
owner-only deletion, also offline; repeated deletion is harmless. Both operations flow through existing
list/detail observations, with no second Planning write. Detail, editing and confirmed deletion are built.

## Prescription scan

The scan screen (`ui/prescription/scan/`) uses the camera through CameraX. The camera stays in the UI layer, behind a small `ScanCamera` interface so the screen can be tested with a fake: its preview has to be drawn inside the screen, so it can't sit behind a device service like `TextRecognizer`. Only its result, the photo file, leaves the screen.

The photo of a prescription is a medical document, so it is never uploaded and never kept: `ScanPhotoStore` (`model/medication/scan/`) writes it to a `scans/` folder of the app's private cache, which is never backed up. There is at most one photo at a time. It is first shown on the same screen for the user to check (Retake deletes it and shows the camera again), and it is deleted when the scan screen is closed or destroyed, including when the screen is left while the photo is still being written. Photos left behind by an interrupted scan (e.g. the app was killed) are deleted the next time the scan screen opens. "Use this photo" reads it with `DraftPrescriptionFromScan`. When a prescription is read, the form opens with it in place of the scan screen, as for manual entry: screens only pass text routes to each other, so `ScanHandoffViewModel`, shared by the prescriptions screens, holds the scanned prescription in memory and owns the photo from then on. The photo is deleted when the form is saved or closed, or when the user leaves the prescriptions screens, so a later version can keep it on the phone instead of deleting it. When nothing can be read, the photo stays on the scan screen until the user retakes it or enters the prescription manually. Closing the scan screen while it is read deletes it too.

The text on the photo is read by a `TextRecognizer` (`model/medication/scan/`), a device service behind an interface like the repositories, with its `TextRecognizerProvider` and a `FakeTextRecognizer` for tests. Its implementation, `MlKitTextRecognizer`, uses ML Kit's Latin text recognition (French and English) with the model bundled in the app, so it works offline from the first scan. The photo and its text never leave the phone; ML Kit only sends usage metrics to Google. It returns a `RecognizedText`: plain Kotlin lines, each with its text, its box on the upright photo and ML Kit's confidence, so the prescription parser can be tested without Android or ML Kit. ML Kit doesn't know what a prescription is: it only reads lines, in no guaranteed order, and turning them into a doctor, a date and medications is the parser's job. The photo is read at full size (up to 4096 px), because scaling a page down makes ML Kit misread small print. The recognizer never deletes the photo; whoever owns it does.

Reading a prescription from those lines is plain Kotlin in `model/medication/scan/` (it uses no repository, so it isn't a domain use case), in steps that are tested on their own with what ML Kit read on phone photos of six fictional prescriptions (`src/test/resources/scan/`):
1. `readingOrder` puts the lines back in reading order: ML Kit returns them in no guaranteed order and splits a table row into cells. Each line joins the row of its left neighbor at about the same height, which follows a slightly tilted photo.
2. `cleanedOcr` undoes the mistakes ML Kit predictably makes (O for 0 inside a number, a bar for 1 before a dose form, spaces inside a date) without touching words; `comparable` gives the lowercase, accent-free form text is matched in.
3. `PrescriptionLexicon` holds the French and English words and patterns: dates read day first (05/11/2026 is 5 November), birth-date clues, doctor titles, strengths, dose quantities, frequencies, durations, headings, labels and contact details. A new format means a new word there, not new logic.
4. `classify` says what each line is: the prescribing doctor, another doctor, the patient, the date, a medication, its dosing, a heading, a label or noise. A label ("PATIENT", "Médecin traitant") applies to the lines just below it in its column, so the family doctor isn't taken for the prescriber and the birth date isn't taken for the prescription's. Unsure lines touching the photo's edge are scraps of other sheets in the background. Doctor and patient lines are recognized before contact details, since they often end with an address.
5. `readPrescription` (`domain/medication/scan/`) builds the `PrescriptionDraft` and the set of details to check (`ScannedPrescription`). Each medication starts at a name with its strength and takes the dosing below it or beside it in a table row; name, dosage and frequency are kept as written and never guessed ("1-0-0" alone doesn't become "1 comprimé"), and every medication starts on the prescription's date. Weeks and months are counted as 7 and 30 days. A detail is to check when ML Kit was unsure of its line (below 0.8), when it is missing, converted, a maximum or cut to fit, when the doctor or the date is missing, duplicated or improbable, or when ML Kit seems to have split a name ("Métoclopram ide"). No medication read means nothing to review: the patient enters the prescription by hand. A prescription holds 20 medications at most: those read beyond are counted (`leftOut`) so the review can tell the patient, never dropped silently.

The rule the tests hold it to, on the six sheets: **every detail is right or to check**. A wrong detail that isn't flagged is the one the patient would trust. The sheets read right have nothing to check, so flagging everything doesn't pass either, and shuffled lines, unsure readings and digits read as letters must keep the rule.

## Care circle invitations

A patient adds someone to their circle before that person has an account, but member documents are keyed by the member's uid (`/users/{uid}/circle/{memberUid}`), which only exists once they sign in. So **a pending member is an invitation, and a member is someone who accepted one**:

- `/invites/{inviteCode}` holds what the patient decided about the person (first name, family name, relationship, permissions) and the email to send the invitation to. The patient creates it, offline if needed.
- `/users/{patientUid}/circle/{caregiverUid}` is created **only when the caregiver redeems the code**, as a copy of the invite, and the invite is deleted in the same write. `memberUid` keeps meaning the member's uid, so rules can still check `circle/{request.auth.uid}` with a single `get()`.
- `/users/{caregiverUid}/patients/{patientUid}` is the caregiver's own list of the circles they joined, created in the same write.

We didn't store pending members in `circle/` and move them on join (the document ID would change, and a caregiver would have to create and delete docs in the patient's circle), or give members a generated ID plus a separate uid link (every collection's rules would need two lookups).

**What each side sees.** Each user can only read their own profile, so what the other side needs is copied:

| When | What is copied | From → to | Why |
|---|---|---|---|
| Invite created | the patient's first name | profile → `invites/{inviteCode}.ownerFirstName` | The caregiver must know whose circle they are joining, and can't read the patient's profile |
| Code redeemed | name, relationship and permissions | invite → `circle/{caregiverUid}` | The patient keeps seeing the name they typed, like a phone contact |
| Code redeemed | the caregiver's account email | `request.auth.token.email` → `circle/{caregiverUid}.email` | A reliable contact that the rules check, so it can't be faked |
| Code redeemed | `ownerFirstName` | invite → `patients/{patientUid}.patientFirstName` | The caregiver lists their patients without reading their profiles |

There are two emails: `invites/{inviteCode}.email` is where the patient sends the invitation (typed, optional, editable while pending), and `circle/{memberUid}.email` is the caregiver's Google account email, set when they join and read-only for the patient. Before joining there is no account to read it from, and after joining the account email is the one to trust. The caregiver's join screen tells them that the patient will see their email.

**Invite codes.** The code is the document ID: 10 characters from an alphabet without 0, O, 1, I and L (they are read aloud and typed), generated on the phone with `SecureRandom`, and shown as `ABCDE-FGHJK`. Anyone signed in may `get` an invite by its code while it is valid; once it has expired, only the patient can, so they can still renew or cancel it. This means that while an invite is pending, anyone holding the code can read its details. Only the patient may `list` their invites, so codes can't be enumerated. A code is valid for 7 days from the **server** `createdAt`. Redeeming deletes the invite, so a code works once, and a removed caregiver can't rejoin with their old one. Invalid, used and expired codes look the same to a caregiver: the app shows one error, and Firestore refuses to read all three (a used invite is deleted, an expired one is readable only by the patient). Joining requires a profile with role `CAREGIVER`.

**Redeeming is the one write that needs the network.** Only the server knows whether a code is valid, unused and unexpired, and a local "you joined" that the server later rejects would show someone's data and then take it away. So redeeming is one `runTransaction`, not a batch: a batch is applied to the local cache first, so a dropped connection could show the circle and then take it away. A transaction needs the network and is never queued offline: if it fails, nothing is written to the phone's local storage, and the join screen says why (no connection, or the code can't be used). It reads the invite and the caregiver's `circle/{caregiverUid}` doc (to detect an existing member), then creates the `circle/{caregiverUid}` and `patients/{patientUid}` docs and deletes the invite. The rules check the invite as it was before the transaction (`get`) and the new docs after it (`existsAfter`). Everything else (creating, editing or cancelling an invite, editing or removing a member) works offline. An invite created offline only exists on the patient's phone until it syncs, so the app says the code will work once the phone reconnects (`hasPendingWrites()`).

**Removing a member** is one batch written by the patient: it deletes `circle/{memberUid}` and the caregiver's `patients/{patientUid}` doc, so no stale link is left behind (the invite was already deleted when they joined). This is the only write a patient makes into another user's data, allowed because a patient must always be able to take themselves off a caregiver's list: the rules let a patient delete exactly the `patients/{patientUid}` doc about themselves, nothing else. A caregiver leaves a circle the same way: one batch deleting their `circle/{caregiverUid}` doc in the patient's circle and their own `patients/{patientUid}` doc. Cancelling a pending invite deletes it; renewing an expired one creates a new invite with the same details and deletes the old one.

Known limitations:
- Revoking access while the patient is offline only takes effect once their phone syncs.
- If the patient changes their first name, caregivers keep the old one: a patient makes no other writes into caregivers' data, so the new name isn't pushed into each caregiver's `patients` list.
- Someone with a patient account can't join a circle; they would need to sign in with another Google account.
- An expired invite stays in the patient's list until they cancel or renew it, since there is no server code to clean it up.

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
/users/{uid}/patients/{patientUid}    circles this caregiver joined
/users/{uid}/eventRegistrations/{eventId}   events the user says they registered for (owner only)
/invites/{inviteCode}                 pending care-circle members (invitations), deleted when redeemed
/events/{id}                          Ligue events, read: signed in, write: nobody from the app
/directory/{contacts|programs}/items/{id}   read: signed in, write: staff
```

Care-circle members read a patient's data through rules that check `/users/{uid}/circle/{request.auth.uid}`. Staff status comes from a custom claim set by the team, never from a field users can write.

A prescription is one document: the prescribing doctor and the date, stored once, and the list of its medications, each with its own ID, start date and duration. A prescription and its medications are always written together (one form, one use case), so keeping them in one document makes saving, replacing or deleting a prescription a single write. It can't be half saved, and its medications can't be partly known by a device that is offline, mixed when two devices edit at once, or left behind after a deletion. Planning, reminders and the appointment summary still work on single medications: the repository reads them out of the prescriptions, so they never deal with a prescription. Anything recorded per intake later (e.g. a medication marked as taken) would go in its own collection, not in the prescription.
