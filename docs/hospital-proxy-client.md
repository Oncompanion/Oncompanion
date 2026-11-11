<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Android hospital proxy client and integration

`HospitalProxyClient(endpoint, tokens)` remains a transport client. `HospitalRepositoryProxy`
adapts it to `HospitalRepository`; the provider selects this adapter and Edit Profile uses its
existing explicit search ViewModel when configured. Deployment configuration must supply a trusted
HTTPS URL without credentials, query or fragment. Never take it from search
text or point it at public Nominatim: credentials are sent to this configured endpoint.

The implementation follows the existing [backend HTTP contract](../functions/README.md):
`onRequest` GET with UTF-8 encoded `q`, `format=jsonv2`, `limit=10`, no body, Firebase ID token
in `Authorization: Bearer …` and App Check token in `X-Firebase-AppCheck`. The backend adds the
hospital qualifier itself. Results are a bare JSON array, projected into existing `Hospital` values.
Only status 200 is a success; a missing/malformed body, invalid fields or more than ten results fails.
The platform transport uses 10-second connect/read timeouts, disables redirects and HTTP caching,
caps decoded response bytes at 256 KiB, and closes streams/connections. It has no application retries,
queue, cache or response-body logging. Tests inject responses/connections without external networking.

Failures distinguish Auth, App Check, endpoint 404, other client errors, 429, 503, other server errors,
unexpected statuses, network, timeout and malformed responses; HTTP failures retain their status.
Untrusted error bodies are discarded, including backend error codes: quota/global/upstream 429 share
the rate-limited category. Platform errors need not have the backend's JSON error envelope.
`Retry-After` is exposed only for 429/503, as positive seconds or an RFC 1123 date rounded up against
an injectable clock. Missing/invalid/nonpositive/past values give no hint. Delays beyond one day
(including numeric overflow) yield `BeyondLimit`, never a truncated delay authorizing an early retry.
No value triggers a retry. Backend hints of 60–180 seconds do **not** promise recovery of a held lock.

## Firebase and build variants

`FirebaseHospitalProxyTokens` reuses the existing default Firebase app and signed-in Firebase user.
It requests `getIdToken(false)` and `getAppCheckToken(false)` on each explicit call; the SDKs cache
valid tokens and refresh expired ones. No separate token cache, Google sign-in token substitution,
forced-refresh loop or retry after 401/403 exists. After an authentication error, a future caller must
resolve session/attestation health and require another explicit submission. Missing/failed credentials
return typed failures before HTTP. Coroutine cancellation propagates, including session cleanup.
Cancellation disconnects an active platform connection immediately. Blocking reads run on IO workers;
session cleanup does not wait for a late response, and cancelled callers cannot receive it. Streams
and connections are also closed when the worker exits.

App Check is installed lazily, as introduced in
[HTTP client #303](https://github.com/Oncompanion/Oncompanion/pull/303): the variant-specific factory
is installed before this client's first App Check token request, on the existing default app. This keeps
an unused client inert and does not activate UI networking. The SDK installation affects that app's
App Check instance; coordinate any future global initializer rather than installing competing factories.
The existing Firebase BoM is retained; only App Check core/debug/Play Integrity dependencies are added,
with debug and Play Integrity provider artifacts restricted to debug and release respectively.

Before approved staging/release use:

- Register the Android Firebase app for [Play Integrity](https://firebase.google.com/docs/app-check/android/play-integrity-provider),
  configure its SHA-256 signing certificate and review attestation settings/distribution requirements.
  The production backend must allow that Firebase app ID in `HOSPITAL_APP_IDS`.
- For a separately approved staging app, follow the [debug provider setup](https://firebase.google.com/docs/app-check/android/debug-provider).
  The SDK generates a local debug secret and emits it to local Logcat on first token use for enrollment.
  Treat that log as a credential: never upload/share it or commit the value, screenshots or collected
  Logcat; register it privately in staging, revoke when no longer needed, and do not enroll debug
  secrets in production. This PR does not fetch, register or store any debug secret.
- Unit tests inject `HospitalProxyTokens` and transport/connection fakes and never need real credentials.
  Firebase emulators have no App Check emulator; real end-to-end verification requires authorized
  staging registration. Do not disable backend verification to make tests pass.
- The implemented repository/UI integration uses the HTTP contract. Any callable migration remains
  a separate reviewed change; this does not resolve the prior callable recommendation or the
  [GET URL privacy constraints](../functions/OPERATIONS.md). The backend is deployed with a scoped
  request-log exclusion; Android activation and authenticated live verification remain deferred.

## Opt-in repository and Profile integration

After authorized deployment, App Check registration and rollout review, application setup may call
`HospitalRepositoryProvider.configure(trustedProxyUrl)` **before** creating hospital field ViewModels.
`MainActivity` calls `configureAtStartup` before creating field consumers, using the non-translatable
`hospital_proxy_endpoint` string resource. It is empty by default, so shipped builds remain manual-only.
After deployment and rollout approval, set that resource to the actual verified HTTPS endpoint;
the URL is public configuration, not a credential. Do not guess it from the project name.
Startup configuration preserves any installed repository, including test overrides, across activity
recreation. Blank or invalid configuration leaves the repository untouched and manual entry usable;
validation failures log only their type. Explicit `configure()` throws on invalid endpoints without
changing the repository. Changing a configured endpoint requires a new app build/process, rather than replacing live consumers or bypassing a wait.
`disable()` restores the unconfigured provider for new consumers. Configuration is a startup boundary,
not a reactive remote flag: recreate existing field consumers after endpoint/configuration changes.
The provider installs the shared proxy adapter, never the legacy Nominatim HTTP repository, and
rejects the public Nominatim host. Invalid configuration leaves the current repository untouched.

The adapter preserves the 50-query successful-result memory cache, including empty results and
trimmed query keys, so cached public results remain available offline. A mutex shares completed
identical work. New cache misses use the existing client's Auth/App Check, status mapping and parser.
There is no second HTTP implementation or Retry-After parser and no fallback to direct Nominatim.
Missing auth/App Check and other service failures retain generic search feedback and manual entry.
Network/timeouts, invalid responses and 429 retain the existing repository error distinctions.

Parsed retry hints block every uncached query in the shared adapter until a monotonic deadline;
cache hits are still served. BeyondLimit blocks that adapter indefinitely; do not recreate adapters
to evade a provider wait. No countdown or automatic retry is scheduled. The server remains the global
rate-limit authority across devices, process restarts and revisions. Persistent locks require the
operator recovery procedure, never an assumption that a client hint guarantees recovery.

Edit Profile preserves free-text validation/100-character limits, explicit Search, result selection,
OpenStreetMap attribution and navigation. Editing/saving/disposal cancels old searches; the existing
ViewModel generation check prevents late responses replacing newer results. Onboarding and Add
Appointment remain unchanged. New unit tests use the real adapter/client with fake tokens/HTTP and fake user
repositories; they do not require or mutate shared Firebase emulator state.

## Deployment verification and debug-emulator handoff

The backend endpoint is verified as
`https://europe-west1-oncompanion-c4144.cloudfunctions.net/hospitalSearch`; the resource remains empty
until the authenticated live plan is approved and ready. No release-signing change is needed for the
owner-requested debug-emulator path. A temporary debug enrollment in the existing project requires
explicit approval and private handling/revocation; it does not prove release Play Integrity works.
See [LIVE_VERIFICATION.md](../functions/LIVE_VERIFICATION.md) for the five completed rejection checks,
current custom-auth/bootstrap limitations, Android Studio steps and the one-upstream-request plan.
IAM concerns are a [separate security follow-up](hospital-proxy-iam-follow-up.md), not changes to make
in this deployment PR.
