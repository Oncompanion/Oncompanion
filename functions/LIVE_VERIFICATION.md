<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Hospital proxy verification record and procedure

This is the single record of completed live checks and outstanding verification. Deployment evidence
and negative checks are also linked from [#435](https://github.com/Oncompanion/Oncompanion/pull/435).
Configuration is described in [DEPLOYMENT.md](DEPLOYMENT.md) and the
[Android setup guide](../docs/hospital-proxy-client.md). A procedure is not proof that it ran.

## Verification status

| Check | Evidence / status |
| --- | --- |
| Invalid/missing credentials and malformed requests | Five deployed negative checks recorded in #435; see limitations below. |
| Authenticated debug search | Completed on the emulator on 12 November 2026, using Google sign-in and privately registered debug App Check. |
| Successful hospital suggestion and attribution | Completed for the public HUG query below. A different query returned a valid empty result. |
| Server-cache repeat | Same query after app process restart returned the same suggestion; cache and coordinator timestamps remained unchanged. Consistent with a server-cache hit without another upstream request. |
| Physical-phone debug search | LimeeFox reports success in the [PR #446 review](https://github.com/Oncompanion/Oncompanion/pull/446#pullrequestreview-5882588343), using a registered debug token. This is not release attestation. |
| Signed-release Play Integrity and authenticated search | **Outstanding.** Check the next signed weekly APK containing release activation; record the APK version, phone and result. |
| Valid Auth with missing/invalid App Check | **Outstanding.** Invalid-Auth rejections do not establish this combination. |
| Successful-request log retention/privacy | **Outstanding.** Scoped negative-test evidence is not proof of universal non-retention. |
| Live quota exhaustion, proxy alerts and notification delivery | **Outstanding.** Use automated quota/error tests rather than exhausting live capacity. |
| Temporary debug enrollment/test-session cleanup | **Not verified.** Revoke/remove only under the separately approved cleanup scope. |

## Completed debug-emulator checks: 12 November 2026

The project owner separately authorized the bounded diagnostic attempts and the same-query cache
repeat. These records do not authorize further searches. No patient information was submitted.
Token values, enrollment secrets, user identities, profile contents and full Logcat are excluded.

1. **`Hôpital Genève`:** authenticated request returned a valid empty result and the UI displayed
   "No hospitals found. You can keep your text." with OpenStreetMap attribution. Cache creation
   was observed at `2026-11-12T14:24:21.033773Z`, with zero projected results. Empty results are
   successful responses, not evidence of an upstream error.
2. **`Hôpitaux universitaires de Genève`:** separately approved public-hospital query returned one
   suggestion with OpenStreetMap attribution. Cache update time was `2026-11-12T14:28:52.242333Z`,
   expiry `2026-11-19T14:28:51.804Z`; coordinator update time was
   `2026-11-12T14:28:52.444092Z`. The request completed and released the coordinator.
3. **Identical HUG repeat:** the app was restarted into a new process to clear Android's memory
   cache. After checking the server entry was fresh, one identical search returned the same
   suggestion. Cache and coordinator update times above remained unchanged. This supports the
   server-cache path; merely seeing the same UI result without those checks would not establish it.

These checks establish the debug Auth/App Check path, suggestions, attribution and the bounded
server-cache repeat. They do not establish signed-release attestation, live profile saving,
quota-exhaustion handling or monitoring delivery. Metadata timestamps above are UTC.

### Earlier failed attempt: 11 November 2026

The earlier UI error was "Search is unavailable. Try again later or enter the hospital manually."
Sanitized logs recorded `hospital_search_upstream_failure` with `upstream_timeout` at
`2026-11-11T18:49:19.951798Z` on the previous deployed revision. That request passed the handler's
credential checks and reached the upstream path. The root cause remains unknown; later success
is not proof of a fix. Diagnostic backend changes are separate from the Android activation diff.

## Negative endpoint procedure

Use non-sensitive synthetic markers and invalid credential strings only. Check GET with no Auth
(401), invalid Auth/App Check (401), missing App Check with a bearer string (403), missing query
(400), and POST (405). These must stop before service/quota/upstream access; compare coordinator
metadata and inspect retained scoped logs after ingestion. No marker, URL or credential string should
be retained. Missing App Check with an invalid bearer does not test App Check validation with valid Auth.
Never substitute real patient information. An absent entry is bounded evidence, not universal privacy.

## Bounded live-search procedure

Each new live attempt needs its own approved public query, request budget and credential scope.
The completed attempts above do not grant an ongoing test budget. Do not retry automatically,
try additional names, inject faults, exhaust quotas or change Auth/App Check to obtain a result.
The usual bounded plan is one public query and one identical repeat, with at most one upstream
request. If cache freshness, tokens or the request budget are uncertain, stop and report.

### Android Studio debug setup

1. Use the normal debug app on an emulator or phone and a dedicated Google test account with only
   fictional profile data. The existing sign-in UI is Google-only; creating a Firebase UID alone
   does not establish a sign-in session. Do not add a provider, login method, signing key or IAM
   permission to make a test possible. Custom Firebase authentication requires separately approved
   trusted signing/bootstrap tooling and is not the path used in the completed checks.
2. Both builds already inherit the deployed URL from main resources. Do not add another endpoint
   overlay. Typing does not search; only tap Search after credentials and the live budget are ready.
3. To initialize debug attestation without searching, stop at a breakpoint in `MainActivity.onCreate`
   after `super.onCreate`, open **Evaluate Expression**, evaluate this single-line expression, then
   close the dialog and resume. It does not print or return a credential:

   ```kotlin
   run { com.github.se.oncompanion.model.hospital.HospitalProxyAppCheck.instance.getAppCheckToken(false); kotlin.Unit }
   ```

4. The SDK may emit its debug enrollment secret locally. Under separate owner approval, register it
   privately in Firebase Console → App Check → the Android app → Manage debug tokens. It is a
   credential, not an App Check request token. Never paste it into an issue/chat, commit it, upload
   broad Logcat or expand/copy token values from the debugger. Clear the local Logcat buffer after
   enrollment/testing; the SDK may emit the secret again on restart.
5. If issuance needs verification, evaluate a single fresh-token request using `getAppCheckToken(true)`
   with a completion listener that logs only a fixed success/failure marker, then resume so the
   asynchronous callback can complete. A returned Task/Unit is not proof of token issuance. Stop
   if sign-in or attestation fails; do not bypass verification.
6. Open Profile → Edit profile and submit the exact approved public query once. Record success,
   empty/error feedback, result count and attribution. Do not save the profile unless the approved
   test-fixture scope includes it. A valid empty result is not a service failure.
7. Before an approved identical repeat, inspect only safe server cache/coordinator metadata and
   confirm the cache entry is still fresh. Restart the app process to clear its in-memory cache;
   do not clear app data or delete backend cache/quota/control records. Submit the same query once
   and compare the response and metadata. An unexpected miss/error means stop, not retry.
8. Use existing fake-transport/emulator tests for offline/manual entry and 429/503/network/error
   states. Do not manufacture production faults. Under approved cleanup, revoke the temporary
   enrollment/test sessions and remove only the dedicated test fixtures. Do not rotate the quota
   secret or reset the coordinator.

The existing instrumentation runner wires Auth/Firestore to emulators; do not run it against the
live signed-in app. Automated emulator tokens cannot authenticate to the deployed proxy. Use a
separate test device/emulator for connected tests rather than replacing a live-verification session.

### Signed weekly APK check

The [PR #446 reviewer](https://github.com/Oncompanion/Oncompanion/pull/446#pullrequestreview-5882588343)
volunteered a physical phone for this check. The existing signed release process publishes from
main; there is no signed branch-build result in this record. Install the next signed weekly APK
that includes activation over version 0.2.1, retaining the normal signing identity. No debug token
is required or accepted as evidence of release Play Integrity.

With an approved bounded public-hospital test and a dedicated sign-in, check suggestions and
attribution in Profile → Edit profile. Record the release version/commit, phone/OS, attestation/search
outcome and manual fallback. If it fails, preserve manual entry, report the failure and use the
Android rollback procedure in [DEPLOYMENT.md](DEPLOYMENT.md) if needed; do not weaken verification.
The shared endpoint being present, matching signing SHA-256, debug-phone success and an unsigned
release build are prerequisites/evidence, not proof of a successful release App Check token.

No release-key distribution, build-workflow changes, new enrollment or live search is authorized
merely by following this document. The existing Auth/App Check requirements remain mandatory.

## Reporting results

Add dated results to this file, distinguishing automated tests, deployed rejection checks, debug
success and signed-release verification. Record incomplete checks explicitly. Never include passwords,
Firebase/App Check tokens, enrollment secrets, request URLs or full Logcat. A debug-provider request
does not verify release attestation. The server-cache repeat must bypass Android's in-memory cache
and must not create a second upstream request. Monitoring delivery remains a separate check.

Sources: [Firebase custom tokens](https://firebase.google.com/docs/auth/admin/create-custom-tokens),
[Android custom sign-in](https://firebase.google.com/docs/auth/android/custom-auth),
[debug App Check](https://firebase.google.com/docs/app-check/android/debug-provider),
[Play Integrity](https://firebase.google.com/docs/app-check/android/play-integrity-provider).
