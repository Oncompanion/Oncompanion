<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Hospital proxy deployment

Deployment task: [#217](https://github.com/Oncompanion/Oncompanion/issues/217).
Deployment evidence: [#435](https://github.com/Oncompanion/Oncompanion/pull/435).
Android integration: [#266](https://github.com/Oncompanion/Oncompanion/issues/266) and
[release activation #446](https://github.com/Oncompanion/Oncompanion/pull/446).

## Current configuration

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

The runtime has `roles/datastore.user` on the project and Secret Accessor on only the quota
secret, no Editor grant or user-managed keys. Auth and approved App Check are mandatory;
no emulator bypass is configured. Re-read live metadata before a redeploy; this table is not a
continuous audit. Artifact hashes, historical test results and deployment events are recorded in #435.

| Supporting resource | Configuration |
| --- | --- |
| Firestore `(default)` | `eur3` |
| Cache/quota TTL | ACTIVE on `expiresAt`; single-field indexes disabled |
| Coordinator | No TTL; never reset based on age |
| Email channel | `projects/oncompanion-c4144/notificationChannels/4564218546212057707`; delivery unverified |
| Project budget | CHF 5/month, actual 50/90/100%, forecast 100%; alerts only, not a spend cap |
| Proxy alert policies | Proposed only; see [MONITORING.md](MONITORING.md) |
| Recovery operators | Primary: project owner; backup: TBD |

## Request-log privacy

The `_Default` sink exclusion `hospital_proxy_request_urls` must match the actual deployed service:

```text
resource.type="cloud_run_revision"
resource.labels.project_id="oncompanion-c4144"
resource.labels.location="europe-west1"
resource.labels.service_name="hospitalsearch"
logName="projects/oncompanion-c4144/logs/run.googleapis.com%2Frequests"
```

Application stdout/stderr remain available. Verify all project sinks and preserve unrelated exclusions
before testing. GET URLs are still processed by infrastructure: an exclusion prevents matching log
storage in that sink, not URL processing or removal of old logs. Sanitized application logs contain
fixed events and validated codes only. Negative-test evidence in #435 is bounded, not a universal
non-retention guarantee. Native request metrics remain available.

## Deployment procedure

1. Obtain team agreement and separate authorization for the exact deployment/live-resource changes.
   Record the agreed scope in the deployment issue; historical discussion is summarized in #435.
2. Use Node 22 and the reviewed Firebase CLI (15.30.2 for #435). Run the backend CI checks in
   [README.md](README.md), then inspect the compiled exports and codebase selector. The
   `hospital-search` codebase must contain only `hospitalSearch`.
3. Verify the project number, APIs, runtime identity, secret reference and current IAM. Firebase CLI
   non-interactive mode can still enable APIs; review managed-agent side effects before proceeding.
   Stop for unapproved API, IAM, deletion or cleanup-policy changes.
4. Set contact, approved Android app ID and `HOSPITAL_RUNTIME_SERVICE_ACCOUNT` in ignored
   `functions/.env.oncompanion-c4144`. Never put a secret value there. Keep the existing quota
   secret/database/coordinator contract; do not rotate or recreate them during redeploy.
5. Deploy only the intended codebase:

   ```sh
   firebase deploy --only functions:hospital-search --project oncompanion-c4144
   ```

6. Verify the function state, actual Run service/endpoint, scaling, runtime account, secret version,
   mandatory Auth/App Check and exact logging filter. Preserve unrelated resources and bindings.
7. Follow [LIVE_VERIFICATION.md](LIVE_VERIFICATION.md) only with separately authorized live-search
   scope and credentials/enrollment. Do not claim authenticated success from
   malformed-request rejections or emulator tokens.

## Android configuration and rollback

Android main resources supply one shared `hospital_proxy_endpoint` for debug and release. Release
uses Play Integrity; debug uses separately authorized debug enrollment. Missing or rejected tokens
produce the existing search error without preventing manual hospital entry. Completed and outstanding
checks are recorded only in [LIVE_VERIFICATION.md](LIVE_VERIFICATION.md).

To disable Android search in a replacement APK, clear the shared endpoint and update the configuration
test accordingly. This makes both builds manual-only; it does not undeploy the backend or change
Auth/App Check enforcement. Already installed APKs retain their packaged configuration.
No request queue, automatic retries, load tests or production fault injection are authorized here.

Keep the previous reviewed source, parameters and secret version before deploying. Roll back by
redeploying compatible source under separate authorization, never by rotating the secret, bypassing
Auth/App Check or deleting cache/quota/control records. A held coordinator survives rollback:
follow [OPERATIONS.md](OPERATIONS.md), proving all potential owners cannot resume before repair.

Unexpected Google-managed IAM/API dependencies are a separate
[security investigation](../docs/hospital-proxy-iam-follow-up.md); this deployment does not justify
or clean up those bindings.

Build/image storage, Functions/Run, Firestore/TTL, Secret Manager and logs can incur charges.
Recheck [Firebase pricing](https://firebase.google.com/pricing) and
[Observability pricing](https://cloud.google.com/products/observability/pricing) before live changes.
