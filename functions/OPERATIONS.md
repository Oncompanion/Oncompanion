<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Hospital proxy deployment and operations

Status: repository configuration only. No deployment, TTL activation, IAM changes, metrics,
alert policies or notification channels have been applied or verified by this PR.
Obtain separate authorization before executing any cloud mutation below.
See [README](README.md) for the contract, privacy constraints and coordinator proof.

## Before deployment

- Assign a primary recovery operator and backup, a monitored team contact and notification
  channel. Record them and the service/revision inventory in the team's restricted operations
  record; do not store tokens or secret values there.
- Use project `oncompanion-c4144`, Standard Firestore `(default)` in `eur3`, Functions Gen 2
  in `europe-west1`, Node 22 and Blaze billing. Enable Functions, Cloud Run, Cloud Build,
  Artifact Registry, Secret Manager, Firestore, Logging and Monitoring APIs. Review budgets
  and alerts (not spend caps). Do not create another coordinator in another project/database.
- Review deployment identity separately from runtime identity. The deployer needs function
  deployment/build permissions and `iam.serviceAccounts.actAs` on the runtime identity;
  IAM changes require an authorized administrator. Preserve Google-managed service agents.
- No runtime service account is pinned in `src/index.ts`; Gen 2 defaults to the Compute
  Engine default service account. Inspect its effective permissions before rollout. It needs
  Firestore data access (for example `roles/datastore.user` on the project) and
  `roles/secretmanager.secretAccessor` on **only** `HOSPITAL_QUOTA_SECRET`. Avoid broad Editor
  grants. A dedicated account is preferred but must be selected/reviewed before adding its
  project-specific identity to function configuration. No service-account keys are needed.
- The endpoint uses `invoker: "public"`: network access is public so Firebase mobile tokens
  can reach the handler. Confirm organization policy permits this. Cloud Run IAM tokens are
  not Firebase ID tokens. Do not remove handler Auth/App Check verification to resolve IAM errors.
- Register production Android App Check with Play Integrity and approved Firebase app IDs.
  Android Auth/App Check transport and activation remain a separate PR. A valid Auth ID token
  **and** a valid App Check token from an allowed app are required, including in staging.
- Copy `.env.example` to ignored `functions/.env.oncompanion-c4144`; set a real monitored
  `NOMINATIM_CONTACT_EMAIL` and comma-separated `HOSPITAL_APP_IDS`. These are existing
  `defineString` parameters; changes require deployment of a new revision.
- Provision `HOSPITAL_QUOTA_SECRET` using the interactive command below with at least 32
  random characters. Never pass its value in a command argument, commit it, print it or log it.
  All revisions share the same stable secret. Rotation resets effective quotas and requires
  quiesced traffic and a separately reviewed migration; rollback must retain secret access.
- Review GET URL privacy with the team before allowing traffic: Cloud Run request logs can
  contain query strings even though application logs do not. Review all Logging sinks/buckets,
  exclusions, retention and APM/load-balancer logs. Exclusions do not prevent initial processing
  by infrastructure or remove already retained data. Do not exclude sanitized application
  events needed below. A callable migration remains a separate contract decision.

## Local validation and authorized rollout

From the repository root, with Node 22, Java 21+ and Firebase CLI 15.30.2:

```sh
npm --prefix functions ci
npm --prefix functions run format:check
npm --prefix functions run lint
npm --prefix functions run build
firebase emulators:exec --config firebase.proxy-test.json --only firestore \
  --project demo-oncompanion-proxy \
  'node --test --experimental-test-coverage --test-coverage-include="functions/lib/*.js" --test-coverage-lines=80 --test-coverage-branches=80 --test-coverage-functions=80 functions/test/unit.test.mjs functions/test/firestore.test.mjs'
```

Only after authorization, configure the secret and deploy the existing TTL declarations:

```sh
firebase functions:secrets:set HOSPITAL_QUOTA_SECRET --project oncompanion-c4144
firebase deploy --only firestore:indexes --project oncompanion-c4144
gcloud firestore fields ttls list --database='(default)' --project=oncompanion-c4144
```

Verify **both** `hospitalSearchCache.expiresAt` and `hospitalSearchQuota.expiresAt` are ACTIVE,
with single-field indexing disabled as declared in `firestore.indexes.json`. TTL deletion is
asynchronous; logical expiry is enforced in code. Never apply TTL to `hospitalSearchControl`.
The rules workflow deploys rules only; it does not activate TTL or deploy Functions.
Do not loosen client rules: emulator tests verify all three backend collections are denied.

Complete the monitoring setup below and resolve the GET logging review before releasing traffic.
For an existing deployment, inventory and quiesce all potential owners before changing revisions
when recovery is in progress. A normal compatible revision still uses the same coordinator.

```sh
firebase deploy --only functions:hospital-search --project oncompanion-c4144
firebase functions:list --project oncompanion-c4144
gcloud functions describe hospitalSearch --gen2 --region=europe-west1 \
  --project=oncompanion-c4144 --format='yaml(state,serviceConfig)'
```

Record the generated HTTPS URL, underlying Cloud Run service name, revision, runtime account,
secret version and commit SHA. Verify maxInstances 1, concurrency 1, minimum 0, timeout 30 seconds,
256 MiB, region and public invocation; these bounds supplement the Firestore lock.
Do not run unscoped `firebase deploy`. Review the CLI plan; do not authorize unrelated deletions.

Verify first in separately approved staging with debug App Check registration:
unauthenticated GET → 401, POST → 405, missing/unapproved App Check → 403, valid explicit
public hospital search → JSON array, repeated search → shared cache, no raw query/token in
application events. Keep tokens out of shell history and test artifacts. Do not run automated
load or periodic cache-miss probes against public Nominatim. Production smoke requests require
approval, valid production tokens, attribution and compliance with the upstream usage policy.

## Native monitoring (manual setup, not active)

No monitoring infrastructure manager exists in this repository. Runtime events, Firebase function
bounds and TTL declarations are version controlled. Project-specific metrics, alert policies,
channels, IAM and Logging routing/retention are **manual Google Cloud setup** in this PR.
Record their resource IDs, owners, verification date and policy export in the operations record.
Do not describe this service as monitored until a test incident reaches the assigned responders.

In Logs Explorer select the project and scope **all revisions** of the actual Cloud Run service:

```text
resource.type="cloud_run_revision"
resource.labels.project_id="oncompanion-c4144"
resource.labels.location="europe-west1"
resource.labels.service_name="REPLACE_WITH_GENERATED_SERVICE_NAME"
jsonPayload.event="hospital_search_release_failed"
```

Use the same scope with the following event filters. Events use Firebase's structured logger
with `jsonPayload.event` and `message`. Capacity exhaustion uses WARNING because it can reflect
normal contention/cooldown; all other listed events use ERROR. Optional `jsonPayload.code` contains
only an allowlisted public `SearchError` code (for example `contact_not_configured`) or, at storage
failure sites, a numeric gRPC error status from 1 to 16 (for example 14 = UNAVAILABLE). Unknown,
uncoded or invalid values are omitted. Messages, stacks, details and metadata are never included.
A code narrows the cause; it does not identify the storage operation or prove the lock is held.
Alert filters continue to match event names regardless of severity/code. There are no request-derived labels.
Build user-defined **counter** log-based metrics with no custom labels, then metric threshold
alerts. For counts, use a five-minute alignment window with SUM and SUM across revision/instance
series; compare the total to the threshold with no additional retest duration. Do not average
per-instance rates. Proposed initial thresholds require staging validation and later tuning:

| Filter / metric | Initial alert and response |
| --- | --- |
| `jsonPayload.event="hospital_search_release_failed"` | Log-based alert on any match; all three release attempts failed, inspect coordinator immediately. Commit may already have succeeded. |
| `jsonPayload.event="hospital_search_upstream_wait_exceeds_limit"` | Log-based alert on any match; intentional held lock, provider wait must be resolved before recovery. |
| `jsonPayload.event="hospital_search_storage_failure"` | Counter total >= 3 / 5 min; inspect Firestore availability, IAM, contention, invalid state and possible ambiguous acquisition. |
| `jsonPayload.event="hospital_search_upstream_failure"` | Counter total >= 3 / 5 min; inspect upstream/network health, configuration and retained-lock events. Includes invalid contact configuration; inspect deployment parameters securely. |
| `jsonPayload.event="hospital_search_unavailable"` | Counter total >= 3 / 5 min; handler returned 503. |
| `jsonPayload.event="hospital_search_unexpected_failure"` | Log-based alert on any match; unexpected handler/auth-configuration error, investigate without adding exception dumps. |
| `jsonPayload.event="hospital_search_capacity_exhausted"` | Counter total >= 5 / 5 min; possible stuck lock, normal cooldown, busy handler or excessive demand. Inspect state; not proof of a stuck lock. |

Attach verified team notification channels to every policy; include this recovery runbook and
service/project identifiers in alert documentation. Set repeat notifications for unresolved
incidents and assign an escalation owner. Individual storage events count attempts, not unique
requests; do not sum storage and 503 counts as if they were distinct failures.

Also chart/alert on native `run.googleapis.com/request_count` on `cloud_run_revision`, filtered
to the same service/location and `response_code="503"`, SUM >= 3 / 5 min across all revisions.
This covers platform 503s that never reach the handler and works without retaining GET request
logs. Chart other 5xx and latency alongside it; platform timeouts/crashes can bypass app logging.
Verify available metric labels in Metrics Explorer before saving. Keep missing data from being
interpreted as healthy traffic: absence of events is not proof of availability.

**Stuck-lock detection limits:** an owner in `hospitalSearchControl/nominatim` never expires.
Release-exhaustion/oversized-wait alerts are immediate evidence to investigate. A process crash
can emit neither event; subsequent cache misses emit capacity events (429, not 503).
Cache hits and idle traffic can hide that condition indefinitely. During rollout and incidents,
an authorized operator must inspect the control document and its Firestore update time directly,
repeat the read after ordinary in-flight work/cooldown could complete, and correlate crash/timeout
and revision metrics. Sustained capacity alerts plus an unchanged owner suggest a stuck lock;
lock age alone never authorizes reset. There is no unattended idle-lock detector here. If that
is required by the team's availability target, keep rollout blocked pending an approved native
Firestore audit-log/monitoring design validated against real writes; no polling service or
scheduled Nominatim requests are introduced by this PR.

Validate alerts using controlled staging faults and synthetic **fixed event only** logs, not
production failures, real queries or secrets. Confirm event matching, counter alignment, native
503 metric, notification delivery, incident closure and re-notification. A synthetic event tests
the policy, not the full runtime path; exercise runtime error paths with injected faults in tests.

## Investigation, recovery and rollback

1. Declare the incident and assign one recovery operator. Read the control document without
   modifying it. An `owner` means held; `releasedAt` plus `cooldownMs` with no owner means
   cooldown. Invalid state fails closed. Failed release acknowledgement may have committed;
   do not assume the document is held from the log alone. Avoid copying owner IDs into logs.
2. Stop new upstream work from **every** service, revision, region and caller using this
   coordinator, including direct function and Cloud Run URLs. Disabling the app button or
   changing traffic percentages alone cannot stop existing invocations. Preserve evidence and
   confirm no bypass callers exist. Do not create a replacement lock/database to regain service.
3. Terminate or otherwise prove that **all potential owners cannot resume**. Request timeout,
   no active HTTP response, maxInstances 1, idle metrics or an old lock timestamp are not proof
   of process termination. Use the authorized Cloud Run/Functions incident procedure to remove
   potential sender instances/revisions and verify shutdown. If termination cannot be proven,
   leave misses blocked and escalate; never improvise a timed unlock.
4. After the final possible upstream operation has completed, wait at least one second. If
   `hospital_search_upstream_wait_exceeds_limit` occurred, also establish with the provider that
   its requested wait has elapsed; the bounded 180-second client hint is insufficient. If the
   wait cannot be established, keep upstream disabled pending provider clarification.
5. With traffic still disabled and exclusive operator control, replace only
   `hospitalSearchControl/nominatim` with `releasedAt` set to a **Firestore server timestamp**
   and integer `cooldownMs: 1000`, removing `owner` and other stale fields. Use an authorized
   Admin SDK transaction with `FieldValue.serverTimestamp()`, verify the resulting Timestamp
   and observe the cooldown before restoring traffic. Do not use a client-clock timestamp or
   delete/reset the document while any sender could resume. This is manual repair, not a TTL.
6. Restore one compatible deployment using the same database/secret. Perform one approved
   explicit smoke search, inspect release/cooldown and monitoring, then restore intended access.
   Record the repair, cause, proof of termination and follow-up without identities or queries.

For rollback, retain the previous reviewed commit, parameter values and Secret Manager version
before deployment. Redeploy that source with `--only functions:hospital-search` after reviewing
compatibility; Firebase source redeployment creates a revision, not a lock reset. Never rollback
by bypassing Auth/App Check, changing the coordinator, rotating the secret or deleting cache/quota
collections. A stuck lock survives rollback and requires the procedure above. If old code cannot
share the lock/quota contract, stop traffic and obtain a migration review before rollback.

References: [log-based alerts](https://docs.cloud.google.com/logging/docs/alerting/log-based-alerts),
[counter metric alerts](https://docs.cloud.google.com/logging/docs/logs-based-metrics/charts-and-alerts),
[Cloud Run metrics](https://docs.cloud.google.com/monitoring/api/metrics_gcp_p_z),
[runtime identity](https://firebase.google.com/docs/functions/manage-functions),
[IAM](https://docs.cloud.google.com/functions/docs/concepts/iam).
