<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Hospital proxy deployment record

Related to #217 / #266. Snapshot: 10 November 2026. This records verified deployment configuration;
**authenticated live success, server-cache verification, release App Check and Android activation
are deferred/outstanding for the draft PR**. See
[LIVE_VERIFICATION.md](LIVE_VERIFICATION.md), [monitoring](MONITORING.md) and
[OPERATIONS.md](OPERATIONS.md) for verification, alerts and recovery.

## Verified deployment

| Setting | Observed value |
| --- | --- |
| Project / number | `oncompanion-c4144` / `782199763007` |
| Function / state | `hospitalSearch` / ACTIVE |
| Region / runtime | `europe-west1` / Node 22 |
| HTTPS endpoint | `https://europe-west1-oncompanion-c4144.cloudfunctions.net/hospitalSearch` |
| Cloud Run service | `hospitalsearch` |
| Cloud Run URI | `https://hospitalsearch-zvpoxjkzxq-ew.a.run.app` |
| Runtime account | `hospital-search@oncompanion-c4144.iam.gserviceaccount.com` |
| Memory / timeout | 256 MiB / 30 seconds |
| Minimum / maximum instances | 0 / 1 |
| Concurrency | 1 |
| Secret reference | `HOSPITAL_QUOTA_SECRET`, version 1 |
| Approved Android app | `1:782199763007:android:48269a68de9ec7c134888a` |
| Network invocation | `roles/run.invoker: allUsers` on this service only |
| Application access | Firebase ID token AND verified App Check token for the approved app |

The runtime's direct project role is `roles/datastore.user`; Secret Accessor is bound on only the
quota secret. It has no Editor grant or user-managed keys. Public invocation was separately approved
and applied with the read policy's etag; existing service bindings and project IAM were preserved.
No emulator bypass is set in deployed environment variables.

The deployed archive was compared byte-for-byte with the tested local compiled entry point,
authorization, handler, service, upstream client and dependency manifests. Archive SHA-256:
`2f44f8f067f12dea73c9544a879e5c3b9f3f60db91deb27c4ac658c33993972f`.
Deployment source base/patch digests and cloud metadata were recorded privately before the PR
commit. The PR commit records the reviewed repository changes; the deployed archive hash above
identifies what was actually deployed. No source archive, credentials or secret values are in Git.

## Approved setup already completed

- Billing enabled. Cloud Functions, Run, Build, Artifact Registry, Secret Manager, Eventarc and
  Billing Budget APIs enabled with explicit approval. Standard Eventarc/Pub/Sub identities ensured.
- Quota secret created with automatic replication and one ENABLED version. Its value was generated
  cryptographically in memory, sent directly to Secret Manager, never printed or stored locally.
- Firestore `(default)` is in `eur3`. TTL is ACTIVE and single-field indexing disabled on only
  `hospitalSearchCache.expiresAt` and `hospitalSearchQuota.expiresAt`, matching the checked-in file.
  Two field-specific PATCH operations avoided touching unrelated composite indexes. Both operations
  completed; unrelated field settings, composite indexes, rules releases and coordinator field
  configuration matched their before-state. Proxy reads use document IDs/transactions, not expiry
  queries. TTL can delete expired cache/quota records; it never unlocks the coordinator.
- Email notification channel `projects/oncompanion-c4144/notificationChannels/4564218546212057707`
  created/enabled for the owner-approved address. Delivery has not been tested.
- CHF 5/month alerts-only budget created for this project, with 50%, 90%, 100% actual-spend and
  100% forecast thresholds through that channel. Existing budgets preserved. It is not a spend cap.
- Project owner is the primary recovery operator; backup is TBD by the owner's course-project choice.
  The two minimal proxy alert policies are prepared but **not created or activated**.

## Request-log privacy

The owner approved retaining HTTP GET with its infrastructure-processing limitation. Logging
exclusion `hospital_proxy_request_urls` is enabled with this exact filter:

```text
resource.type="cloud_run_revision"
resource.labels.project_id="oncompanion-c4144"
resource.labels.location="europe-west1"
resource.labels.service_name="hospitalsearch"
logName="projects/oncompanion-c4144/logs/run.googleapis.com%2Frequests"
```

The service name was read from the actual deployed function and matches the filter. Existing
exclusions, unrelated sink settings, notification channels and alert policies were compared and
preserved. The exclusion is on the `_Default` sink; it does not match application stdout/stderr.
The audited project has only the standard `_Default` and `_Required` sinks.

Five authorized negative endpoint checks passed without service/upstream access. After an ingestion
window exceeding four minutes, their random non-sensitive marker and fake credentials were absent
from retained logs and no matching platform request entries were returned. This bounded evidence
supports the filter check; it is not a guarantee about future inputs or independent infrastructure.
The deployed logger emits fixed events/validated codes only; successful/rejected normal calls stay
quiet. No real credential or patient data was used in those checks. Google still processes GET URLs;
exclusion neither redacts URLs nor removes old logs. Native Cloud Run request metrics remain usable.

## Reproducible deployment and separate security follow-up

Use Node 22 and Firebase CLI 15.30.2, after fresh tests and explicit deployment approval:

```sh
firebase deploy --only functions:hospital-search --project oncompanion-c4144
```

The actual CLI selector helper and compiled exports confirmed this selects the `hospital-search`
codebase, whose only function is `hospitalSearch`. Never use an unscoped deploy or `--force`.
Parameters live in ignored `functions/.env.oncompanion-c4144`: monitored contact, approved app ID,
and `HOSPITAL_RUNTIME_SERVICE_ACCOUNT`. No secret value belongs in that file. Reference the existing
stable quota secret and dedicated runtime identity; do not rotate or re-create them during redeploy.

**CLI non-interactive mode does not prevent API activation.** Preflight must inspect required APIs,
managed-agent side effects and proposed IAM, not rely on a prompt. The first scoped deployment
unexpectedly enabled Firebase Extensions API during dynamic-extension discovery. The client was
stopped, but the submitted function create finished remotely. No extension instance, trigger or queue
was created. Public invocation was completed later under explicit service-only approval.

The unexpected Google-managed roles/API dependencies are documented in the separate
[IAM security follow-up](../docs/hospital-proxy-iam-follow-up.md). They are not asserted necessary
for the HTTP proxy. **No IAM cleanup, role narrowing, API disablement or key creation is in PR 4.**
Future deployment must stop for unapproved API/grant/deletion/cleanup-policy changes.

## Tests, rollout gates and rollback

- Fresh backend run: 126 unit/demo Firestore tests pass on Node 22; coverage 99.14% lines,
  97.82% branches, 92.68% functions. Locked dependencies install, build/types/format checks pass;
  the compatible transitive lockfile corrections report zero audit findings, direct pins unchanged.
- Latest-main Android check: 2,627 unit/Robolectric tests pass; lint, `ktfmtFormat` and
  `ktfmtCheck` pass. The earlier focused run passed 100 hospital client/provider/field/Profile tests.
- Full `connectedCheck`: all 500 device tests pass on the read-only Pixel_10a API 37 AVD,
  with local Auth/Firestore emulators. `check`, `ktfmtCheck` and `assembleRelease` pass. An initial
  run was interrupted after two M1 tests lost focus to a Nexus launcher ANR dialog; the clean
  rerun used CI-style disabled animations/error dialogs and passed without skipped tests.
  Isolated local ports avoided an existing emulator; the temporary test-helper port changes
  were restored and are not committed. Earlier targeted `HospitalProfileTest`: both tests pass,
  covering saved profile text/offline manual entry. None of these is a deployed-proxy Auth test.
- Account, Appointments and overview diagrams regenerated, XML parsed and visually checked.
- Pending: dedicated live test identity/bootstrap and approved temporary debug App Check enrollment;
  authenticated success/shared-cache checks; Android endpoint activation; real end-to-end suggestions;
  proxy alert creation/notification delivery. Play Integrity release verification is outside the
  owner-requested debug-emulator path and remains unverified. See the bounded live plan.

`hospital_proxy_endpoint` remains empty. `MainActivity.configureAtStartup` reuses an installed adapter
across recreation; no default URL, UI redesign or direct Nominatim fallback is added. Do not activate
it before the agreed verification gates. No request queue, retries, load tests or production faults.

Build/image storage, Functions/Run, Firestore/TTL, Secret Manager and log usage can incur costs;
consult [Firebase pricing](https://firebase.google.com/pricing) and [Observability pricing](https://cloud.google.com/products/observability/pricing).
For rollback, retain the last reviewed source/config/secret version and redeploy only compatible
source under approval. A held coordinator survives rollback: follow OPERATIONS.md's proof-of-owner
termination and provider-wait procedure. Never reset on age, delete quota/cache records or rotate the
secret to regain access. No rollback or live recovery was performed.
