<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Hospital search proxy

This is Oncompanion's explicit hospital search backend, implemented with second-generation
Firebase Functions and TypeScript. It is **not deployed** by this change. Profile's Search button
stays disabled. No Android files, Firebase rules or production resources are changed. Backend
tests run in CI; deployment stays manual. This endpoint is public at the network layer;
Firebase Auth and App Check are
mandatory in its handler.

## Development and tests

Use Node 22 (the deployed runtime), Java 21, and the Firebase CLI used by the Android project.
From the repository root:

```sh
npm --prefix functions ci
npm --prefix functions run format:check
npm --prefix functions run lint
npm --prefix functions test
firebase emulators:exec --config firebase.proxy-test.json --only firestore \
  --project demo-oncompanion-proxy 'npm --prefix functions run test:emulator'
```

If the standalone Firebase launcher fails inside its bundled npm, build first with the system
npm and invoke the system Node binary in `emulators:exec` instead:

```sh
firebase emulators:exec --config firebase.proxy-test.json --only firestore \
  --project demo-oncompanion-proxy \
  '/usr/local/bin/node --test --experimental-test-coverage functions/test/unit.test.mjs functions/test/firestore.test.mjs'
```

Adjust the Node path for your workstation. This combined command also reports backend coverage.

The isolated test configuration runs Firestore on 127.0.0.1:8185, avoiding the Android emulator
on 8080. Integration tests refuse non-loopback connections and use only `demo-oncompanion-proxy`.
They exercise actual transactions across independent Admin SDK clients, shared cache expiry,
contention, cooldowns, permanent locks, ownership and default-deny rules for anonymous and signed-in
clients. Unit tests inject HTTP responses; transport regression tests use a loopback HTTP server to
count wire attempts on 421, 408, redirects and socket failures. Timeout tests use virtual time.
No tests call Nominatim.
The TypeScript compiler provides strict static checks; Prettier provides formatting. The existing
CI workflow has an independent hospital-proxy job using Node 22, npm ci,
TypeScript, Prettier and demo Firestore integration tests with coverage. Failures fail the job.

To run Functions locally, build first and start the demo emulators:

```sh
npm --prefix functions run build
firebase emulators:start --only functions,auth,firestore --project demo-oncompanion-proxy
```

Default ports are Functions 5001, Auth 9099 and Firestore 8080. Stop conflicting processes first,
or use a local config with unused ports. The URL is
`http://127.0.0.1:5001/demo-oncompanion-proxy/europe-west1/hospitalSearch`.
When `FUNCTIONS_EMULATOR=true` (set by Firebase), the injected upstream returns `[]` and **cannot
contact Nominatim**. Authentication is still enforced. A GET without credentials returns 401;
POST returns 405. Firebase has no App Check emulator: exercise successful local handler calls
with injected verifiers in unit tests. End-to-end App Check verification requires a separately
approved staging project and registered debug token; do not disable enforcement for production.

## Contract and validation

```http
GET /hospitalSearch?q=H%C3%B4pital+Gen%C3%A8ve+%5Bhospital%5D&format=jsonv2&limit=10
Authorization: Bearer <Firebase ID token>
X-Firebase-AppCheck: <App Check token>
```

Only `q`, `format=jsonv2`, and `limit=10` are accepted, each once. The optional trailing
` [hospital]` is stripped before normalization and added once upstream. Search text is 2–200
UTF-16 code units after NFKC normalization, whitespace folding and lowercasing. Public names,
locations, Unicode letters/numbers and common name punctuation are supported; controls, special
search expressions, email/URL syntax and arbitrary upstream parameters are rejected. The URL is
limited to 2048 bytes; bodies are rejected. Only explicit user submissions are allowed: no
keystroke/autocomplete, background, systematic or nearest-hospital requests.

A successful response is a JSON array, including `[]`. Each hospital contains `osm_type`,
`osm_id`, `category`, `type`, `name`, `display_name`, `lat` and `lon`, exactly as consumed by
`HospitalRepositoryNominatim`. Only hospital classifications are returned. Object type and OSM ID
remain the identity. Response size is bounded to 256 KiB, results to ten, coordinates and required
fields are validated, including decimal coordinate syntax accepted by Kotlin. Android's parser
and generated request parameters remain compatible for supported hospital queries.
**Its current transport does not send the required headers**: add Auth/App Check support and
remote endpoint configuration in a later PR before enabling Search. Do not configure a public
Nominatim URL directly.

Errors have the shape `{"error":{"code":"invalid_query"}}`, with no upstream bodies,
queries, credentials, stack traces or internal exception details:

| Status | Meaning |
| --- | --- |
| 400 | Missing/malformed query or unsupported/repeated parameter |
| 401 / 403 | Invalid/missing Firebase Auth / App Check or unapproved app ID |
| 405 | Unsupported method; `Allow: GET` |
| 413 | Oversized URL or request body |
| 429 | User quota exceeded, shared capacity exhausted or upstream 429 |
| 502 | Network, upstream non-success/5xx, redirect or malformed response |
| 503 | Storage, limiter, configuration failure or upstream 503 |
| 504 | Five-second upstream timeout |

Responses disable HTTP caching. 429/503 include a `Retry-After` hint bounded to 60–180 seconds.
Valid upstream intervals (seconds or HTTP date) within that range extend the shared cooldown.
An upstream wait above 180 seconds (including numeric overflow) retains the existing fail-closed
owner lock instead of storing an unbounded cooldown or resuming before the requested wait.
The client hint remains 180 seconds; it is not a promise that upstream access will resume then.
Cached results remain available. Alert on `hospital_search_upstream_wait_exceeds_limit`.
Three minutes bounds routine backoff and client waits; longer waits require operator review.
Clients must not retry automatically. Platform rejections (for example oversized headers, infrastructure
queues or timeouts) can have platform-defined bodies/statuses.

## Shared cache

`hospitalSearchCache/{sha256("hospital-jsonv2-v1:" + normalizedQuery)}` stores only `results`
and `expiresAt`. Successful empty lists are cached too. Entries are fresh for seven days;
expiration is checked against Firestore server read time on every hit, with no stale serving.
Expired records are overwritten on the next successful refresh. The hash is not an anonymity
guarantee: dictionary guesses remain possible, so submit only public hospital/location text.
There are no stored raw queries, raw user IDs or IP addresses. No cache logs are emitted.
The separate quota store retains pseudonymous miss timestamps for at most one day of logical use.

This cache is independent of the Android repository's 50-query memory cache and Firestore's
Android offline cache. Admin SDK operations use the server, not an offline queue. Existing rules
deny all mobile reads/writes to all three backend collections; emulator tests confirm this without
changing rules. `firestore.indexes.json` declares TTL on `hospitalSearchCache.expiresAt` and
`hospitalSearchQuota.expiresAt`, with indexing disabled for those fields. Both are Firestore
Timestamps. Logical expiry remains mandatory: TTL deletion is asynchronous and the emulator
does not implement automatic TTL deletion. Never apply TTL to the control document.

Before a separately approved rollout, deploy the checked-in policy with
`firebase deploy --only firestore:indexes --project oncompanion-c4144`, then verify both TTL
policies are ACTIVE in Firestore before enabling traffic. The existing rules-only deployment
workflow does not deploy indexes/TTL. This PR does not execute that command.

## Per-user miss quota

After the first shared-cache lookup misses, and **before global limiter acquisition**, an atomic
Firestore transaction permits ten misses in a rolling hour and fifty in a rolling 24 hours.
`hospitalSearchQuota/{HMAC-SHA256(secret, "hospital-quota-v1:" + verifiedUid)}` stores at most
fifty server-read timestamps and a Timestamp `expiresAt`, one day after the latest admitted miss.
No query, token, profile or raw UID is stored. This is pseudonymization, not anonymity. All
instances/revisions must use the same secret and database. Cache hits do not inspect or spend
quota; quota rejections do not mutate the record or touch the global limiter. Initial cache misses
consume quota even if contention or upstream failure later rejects them (there is no retry queue).
Concurrent misses that later discover a filled cache were still misses at their initial lookup.
Storage failures fail closed. Logical rolling-window pruning works before TTL deletion.

Provision a random secret of at least 32 characters through Secret Manager as
`HOSPITAL_QUOTA_SECRET` (for example with `firebase functions:secrets:set HOSPITAL_QUOTA_SECRET`
during separately approved setup). Never commit it or put it in `.env.example`. For local
Functions emulation use an ignored `.secret.local`. Keep it stable: rotating it resets effective
quotas and old records expire separately. Do not rotate during live overlapping revisions;
coordinate migration with traffic disabled. No per-user counters can bypass the global lock.

## Global rate-limit guarantee and recovery

Every cache miss must acquire `hospitalSearchControl/nominatim` in a Firestore transaction.
A transaction reads this single document and writes a fresh random owner only if there is no
owner and any cooldown has elapsed. Transaction retries contain no HTTP side effects. Separate
instances and overlapping revisions use the same database/document, so only one can acquire it.

The owner keeps exclusive control through the HTTP request, response read/abort, and cache write.
Only that owner can release it. Release atomically replaces the owner with a Firestore server
`releasedAt` timestamp and cooldown duration. Acquisition compares a subsequent Firestore
snapshot's server `readTime` against `releasedAt + cooldownMs`. Thus the next outgoing request
starts **at least one second after the previous operation completed**, which is stricter than
one second between starts, without relying on instance clock synchronization. No future slots
are reserved and no sleep is needed to enforce them. Upstream errors extend the cooldown to ten
seconds, or 60–180 seconds for 429/503; longer upstream waits retain the owner lock.
The core `node:https` transport makes one wire attempt using a fresh connection. It does not
follow redirects or retry errors. Native `fetch` is deliberately avoided: the Fetch standard
allows an automatic retry after HTTP 421 even when redirects are disabled, which could otherwise
send two requests under one limiter grant.

There is **no automatic lock expiry**. If an owner pauses before its HTTP call, another instance
cannot steal the lock and race it. A crash, unknown transaction commit outcome, function timeout
or failed release may therefore leave misses blocked indefinitely. Cached results remain usable.
A failed acquisition never sends HTTP; cache-read failures also stop before acquisition; cache
write errors return 503 after releasing with a failure cooldown. Release is idempotent for the
recorded releasing owner and is retried at most three times.
A transient failure or lost acknowledgement can therefore recover without another upstream call.
A persistent release error leaves the lock held or cooldown committed, never silently bypassed.
Storage failures log only `hospital_search_storage_failure`, without exception contents, queries,
UIDs or credentials. Alert on this marker and investigate held control state before rollout.
Corrupt control state fails closed.

Recovery is an operator action, not a timer: stop/disable traffic to **all** revisions/services
that use this coordinator, terminate or otherwise prove all potential owners cannot resume,
then wait at least one second after the final possible upstream request has completed. Only then
replace the control document with a fresh server timestamp `releasedAt` and `cooldownMs: 1000`,
and restore traffic after cooldown. Never delete/reset a held lock while an owner could resume.
For `hospital_search_upstream_wait_exceeds_limit`, also verify the upstream waiting period
has elapsed with the provider before restoring access; if it cannot be verified (for example an
absurd numeric value), keep upstream traffic disabled until the provider clarifies or a reviewed
provider change is available. Do not reset the lock merely because 180 seconds elapsed.
This procedure is intentionally manual and cannot be inferred safely from lock age alone.

A time-based lease, heartbeat, or final ownership recheck alone cannot safely automate recovery:
a process can pause immediately after its last check and send after its lease expires. A fencing
token in Firestore does not stop that request because public Nominatim does not validate the token.
The Functions/Cloud Run request timeout is not proof of process termination; code can continue
running after the response times out. Automatic recovery would need verified termination of every
potential owner (including old revisions), or a separate final egress service that enforces fencing
and spacing itself. Neither exists in this deployment. Keep this fail-closed design for the
undeployed backend; an assigned recovery operator and monitoring for stuck control state are
required before rollout. Do not replace it with TTL-based unlocking to improve availability.

These guarantees assume all Oncompanion Nominatim calls use this same coordinator and operators
do not mutate it during live traffic. Do not deploy another region/project with a separate lock
or configure mobile clients to bypass it. Firestore is the shared coordination authority; database
unavailability stops misses. `maxInstances: 1`, `concurrency: 1`, a per-handler busy guard, three
transaction attempts, a 30-second function timeout and five-second upstream timeout add bounds.
Contended requests get 429 instead of entering an application queue. An identical in-flight
request may get 429; after completion it reuses the shared result. This avoids duplicate upstream
work without an unbounded waiting list. The hosting platform can still queue requests and token
verification/Firestore reads can still incur costs; this is not a spend cap or a full DDoS defense.

## Configuration and production prerequisites

Copy `.env.example` to an ignored `.env.<project-id>` in `functions/` and supply:

- `NOMINATIM_CONTACT_EMAIL`: a legitimate monitored team email. No real contact is invented here.
  Missing/invalid configuration prevents upstream calls. It appears in the descriptive User-Agent.
- `HOSPITAL_APP_IDS`: comma-separated Firebase Android App IDs permitted by the App Check verifier.
  Missing configuration denies all apps. Tokens are verified against the function's Firebase project.

Before a separately approved deployment:

1. Enable the Firebase **Blaze** billing plan and required Functions/Cloud Run, Cloud Build,
   Artifact Registry and Firestore APIs. Second-generation Functions, builds/images, network
   traffic and Firestore reads/writes/storage can incur charges. Set budgets/alerts; they are
   not hard spending limits. Keep minimum instances zero.
2. Register the Android app with App Check (Play Integrity for production), initialize its SDK,
   and implement Auth/App Check headers in the Android transport. Use the **Firebase Auth ID
   token** obtained from the signed-in Firebase user, not the Google ID token passed into
   `AuthRepository.signInWithGoogle`. The current auth interface has no ID-token accessor and
   the Android app has no App Check integration. Add an injectable token-provider boundary in
   the model/transport layer in that later PR, with lazy Firebase access; ViewModels and
   composables must not fetch tokens directly. Console enforcement alone
   does not protect a custom `onRequest` endpoint; this handler explicitly verifies the token
   and app ID. Debug tokens belong only in approved staging/local testing.
3. Select/review runtime service-account IAM, generated endpoint permissions and the single
   `europe-west1` deployment alongside the existing Standard Firestore `(default)` in `eur3`.
   Admin SDK access bypasses client rules; restrict IAM and do not grant mobile clients Admin access.
4. Provide the contact/app IDs, review storage TTL, abuse monitoring and operator recovery ownership.
   Provision the stable quota secret and deploy/verify both TTL policies as described above.
   Firebase Auth and App Check deter abuse; valid tokens can still be replayed until expiry.
   Quotas bound admitted misses per user, not token verification/cache-read spend. Auth verification
   does not perform revocation lookups; normal token expiry applies.
5. Review request logging before rollout: application code never logs query text, but Cloud Run
   platform request logs may include GET URLs. Configure Cloud Logging exclusions/retention and
   any load balancer/APM sinks to avoid retaining query strings. Do not log token headers or bodies.
   The GET contract cannot guarantee that provider infrastructure never processes a query URL.
6. Confirm that all users together remain a moderate, explicitly triggered workload under the
   [Nominatim policy](https://operations.osmfoundation.org/policies/nominatim/). Maximum one request
   per second is a ceiling, not permission for continuous heavy use. No autocomplete or confidential
   patient data. Switch the backend client/provider if requested by OSMF, without an app update.
7. Keep `© OpenStreetMap contributors` linked to
   [OpenStreetMap copyright/ODbL](https://www.openstreetmap.org/copyright) beside all results,
   including empty and cached results. The existing reusable field already provides this.

`firebase.json` adds only the Functions codebase and emulator port, retaining existing services.
The rule deployment workflow remains limited to `firestore:rules`; it does not deploy Functions.
No production deploy command has been run. Do not run an unrestricted `firebase deploy` by habit;
future Functions rollout must be separately authorized and scoped to the hospital-search codebase.

References: [Firebase scaling and runtime](https://firebase.google.com/docs/functions/manage-functions),
[custom-backend App Check verification](https://firebase.google.com/docs/app-check/custom-resource-backend),
[Functions billing/setup](https://firebase.google.com/docs/functions/get-started),
[Cloud Run timeout behavior](https://docs.cloud.google.com/run/docs/configuring/request-timeout),
[Fetch HTTP 421 retry algorithm](https://fetch.spec.whatwg.org/#http-network-or-cache-fetch).

## API decision: retain onRequest pending Android contract approval

[Callable functions](https://firebase.google.com/docs/functions/callable) automatically attach and
validate available SDK tokens; `enforceAppCheck: true` rejects invalid App Check, but the handler
must still require `request.auth` and restrict approved app IDs. Callable POST data avoids query
strings in request URLs (body/APM logging still needs review). This is the preferred candidate
for a coordinated Android integration if the team approves the Functions Android SDK dependency.

For this PR retain `onRequest`: the current `HospitalRepositoryNominatim` and provider generate
GET requests and parse a bare JSON array. Callable functions require a different transport,
request/response envelope and error mapping, plus the new Android dependency. The provider can
still expose `HospitalRepository` and preserve the existing explicit-search UI, manual entry,
attribution and memory-cache behavior, but its present `configure(endpoint)` cannot simply be
pointed at a callable endpoint. Neither API supplies offline network search; local cached results
and manual entry must remain available. Auth/App Check enforcement remains explicit and mandatory
in this HTTP handler. Do not enable Search until the team chooses the contract and implements
its authenticated transport. No dependency or Android activation is added here.

The GET endpoint may put public hospital names in infrastructure request logs. Application code
cannot fix that with a logging exclusion alone: review platform collection, sinks and retention
before rollout. A callable migration should update contract tests, diagrams and deployment notes
in the same change. This is a deployment blocker for team review, not an implicit API migration.

## Upstream configuration decision

Keep the fixed public `https://nominatim.openstreetmap.org/search` endpoint for this PR. Tests
already inject the transport, and emulation replaces the client entirely. An arbitrary environment
URL would expand server-side request access (including private destinations); a future provider
switch needs a reviewed HTTPS hostname/path allowlist, DNS/egress controls and response-contract
review, never request-controlled URLs. Parameters are deployment configuration, not a live reload:
Firebase environment parameter changes require a new revision. The backend can still change
provider without an Android update. Team-approved controlled routing can follow with deployment.

Request queuing remains deferred to a separate PR; this implementation rejects contention.
