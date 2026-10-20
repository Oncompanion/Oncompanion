<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Hospital search proxy

This is Oncompanion's explicit hospital search backend, implemented with second-generation
Firebase Functions and TypeScript. It is **not deployed** by this change. Profile's Search button
stays disabled. No Android files, Firebase rules, production resources or deployment workflows
are changed. This endpoint is public at the network layer; Firebase Auth and App Check are
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
clients. Unit tests inject HTTP responses; timeout tests use virtual time. No tests call Nominatim.
The TypeScript compiler provides strict static checks; Prettier provides formatting. There is no
new CI workflow; run these commands manually until the team approves backend CI integration.

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
fields are validated. Android's parser and generated request parameters remain compatible.
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
| 429 | Shared capacity exhausted or upstream 429 |
| 502 | Network, upstream non-success/5xx, redirect or malformed response |
| 503 | Storage, limiter or configuration failure |
| 504 | Five-second upstream timeout |

Responses disable HTTP caching. 429/503 include a conservative `Retry-After: 60` hint; clients
must not retry automatically. Platform rejections (for example oversized headers, infrastructure
queues or timeouts) can have platform-defined bodies/statuses.

## Shared cache

`hospitalSearchCache/{sha256("hospital-jsonv2-v1:" + normalizedQuery)}` stores only `results`
and `expiresAt`. Successful empty lists are cached too. Entries are fresh for seven days;
expiration is checked against Firestore server read time on every hit, with no stale serving.
Expired records are overwritten on the next successful refresh. The hash is not an anonymity
guarantee: dictionary guesses remain possible, so submit only public hospital/location text.
There are no stored raw queries, user IDs, IP addresses or request history. No cache logs are emitted.

This cache is independent of the Android repository's 50-query memory cache and Firestore's
Android offline cache. Admin SDK operations use the server, not an offline queue. Existing rules
deny all mobile reads/writes to both backend collections; emulator tests confirm this without
changing rules. Configure a Firestore TTL policy on **cache `expiresAt` only** before production
if automatic storage reclamation is desired. Logical expiry does not depend on TTL; without TTL,
expired documents remain stored and cost storage. Never apply TTL to the control document.

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
seconds, or sixty seconds for 429. There are no redirects or automatic upstream retries.

There is **no automatic lock expiry**. If an owner pauses before its HTTP call, another instance
cannot steal the lock and race it. A crash, unknown transaction commit outcome, function timeout
or failed release may therefore leave misses blocked indefinitely. Cached results remain usable.
A failed acquisition never sends HTTP; cache-read failures also stop before acquisition; cache
write errors return 503 after releasing with a failure cooldown. A release error leaves the lock
held or cooldown committed, never silently bypassed. Corrupt control state fails closed.

Recovery is an operator action, not a timer: stop/disable traffic to **all** revisions/services
that use this coordinator, terminate or otherwise prove all potential owners cannot resume,
then wait at least one second after the final possible upstream request has completed. Only then
replace the control document with a fresh server timestamp `releasedAt` and `cooldownMs: 1000`,
and restore traffic after cooldown. Never delete/reset a held lock while an owner could resume.
This procedure is intentionally manual and cannot be inferred safely from lock age alone.

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
   and implement Auth/App Check headers in the Android transport. Console enforcement alone
   does not protect a custom `onRequest` endpoint; this handler explicitly verifies the token
   and app ID. Debug tokens belong only in approved staging/local testing.
3. Select/review runtime service-account IAM, generated endpoint permissions and the single
   `europe-west1` deployment alongside the existing Standard Firestore `(default)` in `eur3`.
   Admin SDK access bypasses client rules; restrict IAM and do not grant mobile clients Admin access.
4. Provide the contact/app IDs, review storage TTL, abuse monitoring and operator recovery ownership.
   Firebase Auth and App Check deter abuse but are not per-user quotas; valid tokens can be replayed
   until expiry. Consider additional edge/per-user controls if traffic warrants it. Auth verification
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
[Functions billing/setup](https://firebase.google.com/docs/functions/get-started).
