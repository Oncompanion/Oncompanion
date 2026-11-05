<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Independent Android hospital proxy client

`HospitalProxyClient(endpoint, tokens)` is a transport client, not a `HospitalRepository`.
The existing repository/provider and UI are unchanged; Search stays disabled. Deployment configuration
must supply a trusted HTTPS URL without credentials, query or fragment. Never take it from search
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

App Check is not installed globally at startup in this step. Its variant-specific factory is lazily
installed before this client's first App Check token request, on the existing default app. This keeps
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
- Choose HTTP versus callable before later repository/UI integration. This PR implements the current
  HTTP contract requested for PR 2; it does not resolve the prior callable recommendation or the
  [GET URL logging rollout blocker](../functions/OPERATIONS.md). It does not deploy resources,
  configure production enforcement, set an endpoint or make live proxy/Nominatim requests.
