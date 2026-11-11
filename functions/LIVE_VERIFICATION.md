<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Hospital proxy verification procedure

Deployment evidence and completed negative checks are recorded in
[#435](https://github.com/Oncompanion/Oncompanion/pull/435). Current configuration:
[DEPLOYMENT.md](DEPLOYMENT.md). This runbook describes verification steps, not proof that they ran.

## Negative endpoint verification

Use non-sensitive synthetic markers and invalid credential strings only. Check GET with no Auth
(401), invalid Auth/App Check (401), missing App Check with a bearer string (403), missing query
(400), and POST (405). These must stop before service/quota/upstream access; compare coordinator
metadata and inspect retained scoped logs after ingestion. No marker, URL or credential string should
be retained. Missing App Check with an invalid bearer does not test App Check validation with valid Auth.
Never substitute real patient information. An absent entry is bounded evidence, not universal privacy.

## Deferred live-search plan; prerequisites still pending

Authenticated live-search and server-cache verification remain deferred in
[deployment #435](https://github.com/Oncompanion/Oncompanion/pull/435).
The procedure below is retained for a future explicitly resumed test, limited to one public query
and one identical repeat with at most one upstream request. Do not execute it while deferred.
No IAM, Auth-provider or security-rule changes are authorized. No authenticated search has been
executed; test-user/enrollment setup requires its own owner-approved scope.
The owner requested a debug emulator workflow rather than adding release signing for this PR.
Use the existing debug App Check provider, with a temporary owner-approved enrollment in the existing
project; this tests the debug-token path, not release Play Integrity attestation.

1. Have an authorized administrator create one dedicated Firebase test UID and a short-lived custom
   sign-in token using existing trusted administration tooling. No separate Google account is
   required by Firebase custom authentication. Verify that the existing administration tooling can
   sign tokens; do not add signing permissions or create keys to make this test possible.
   Email/password and anonymous providers are disabled and will not be enabled for this test.
2. The production UI remains Google sign-in only. A trusted local test bootstrap must call the
   existing Firebase SDK's `signInWithCustomToken` for that dedicated UID; the Auth repository's
   current-user listener is provider-independent. There is no such bootstrap in the current app.
   Do not add a production sign-in method, reuse a real patient's account, paste credentials into
   commands/debugger history, or log a token. If trusted signing/bootstrap tooling is unavailable,
   record the authenticated live flow as blocked rather than bypassing Auth.
3. After separate approval, register the debug provider's enrollment secret privately in Firebase
   App Check for Android app `1:782199763007:android:48269a68de9ec7c134888a`. The SDK's enrollment
   secret is not an App Check request token. Let the SDK exchange it for the real token; keep
   Auth/App Check verification enabled. Never collect or upload broad Logcat containing that secret.
4. With valid tokens, use only the non-sensitive marker for rejection checks: valid Auth + missing
   App Check => 403; valid Auth + invalid App Check => 403; valid App Check + missing/invalid Auth
   => 401. These must stop before service/quota/upstream access.
5. After explicit live-search approval, submit **one** public hospital/location query (proposed:
   `Hôpital Genève`) once. At most **one real upstream Nominatim request** is allowed. Observe
   suggestions and attribution, or record an empty response without trying another query.
6. Submit exactly the same query through a fresh client/process once, so Android's memory cache
   cannot hide the server behavior. Inspect cache/control metadata before/after: this should be a
   shared-cache hit with no second upstream call. Stop on an unexpected miss/failure; do not retry.
7. Test offline cache/manual entry and error states with existing fake-transport/emulator tests;
   do not manufacture 429/503, exhaust live quotas, run concurrent misses or inject production faults.
   Live profile saving, if approved, uses only this UID's fictional test profile/hospital text.
8. Under the same separately approved cleanup scope, revoke the temporary debug enrollment and
   test sessions, and remove only the dedicated Auth user/test profile. Do not delete backend cache,
   quota or coordinator documents or rotate the secret; normal expiry/TTL handles cache/quota data.

Approval must cover the dedicated Auth/profile fixtures, temporary enrollment/revocation, the one
upstream request and same-query repeat. Normal proxy handling writes its cache/quota/control stores.
Tokens stay private and out of Git, logs, arguments and result artifacts. No new IAM grant is proposed.
If an existing authorized administrator cannot supply custom authentication, this live path remains
unverified; a different authentication/test-setup decision requires separate review.

## Android Studio: local procedure with the existing sign-in UI

**Current blocker:** no dedicated signed-in test account or enrolled debug App Check secret is
available here. The existing UI accepts Google sign-in only. A plain Firebase UID cannot sign in
through that UI. The procedure below uses a dedicated Google test account (no patient information),
not a real patient's existing account. If no dedicated Google identity is available, use the custom
Firebase route described above only with already-authorized signing/bootstrap tooling; that route
is not available unless those prerequisites are provided. No new Auth provider, IAM grant or login UI is proposed.

1. Open the project in Android Studio, select the **debug** build variant, and start an existing
   Google APIs emulator in Device Manager (API 34+). Run/debug the normal `app` configuration,
   not an instrumented test. Keep the main endpoint resource empty during credential setup.
2. Set a breakpoint in `MainActivity.onCreate` after `super.onCreate`. In **Evaluate Expression**,
   run this existing debug-only provider call, then resume. It requests attestation only; it does
   not call the hospital proxy or Nominatim and does not display token contents:

   ```kotlin
   com.github.se.oncompanion.model.hospital.HospitalProxyAppCheck.instance.getAppCheckToken(false)
   kotlin.Unit
   ```

3. In this dedicated emulator's local Logcat, filter for `DebugAppCheckProvider`. The SDK emits
   an enrollment secret. In Firebase Console select **oncompanion-c4144 → App Check → the Android
   app → Manage debug tokens**, and register that secret privately with a temporary test label.
   This owner-controlled registration is live App Check configuration, not an Auth-provider change.
   Do not paste it here, commit it, inspect/copy actual request tokens, or upload Logcat. Clear the
   local Logcat buffer after enrollment and after the test; the SDK can emit the secret on restart.
4. Using the same breakpoint, request a fresh token once with the same expression but
   `getAppCheckToken(true)`, then resume. Let the SDK cache it. Keep attestation verification enabled;
   if issuance fails, stop. Do not expand/copy token values in the debugger.
5. Sign in normally with the dedicated Google test account. Use only fictional onboarding details
   if a test profile is needed. Do not use a personal/patient account. Creating a Firebase test UID
   alone is insufficient; the app needs a genuine Firebase sign-in session.
6. Once credentials are ready, create this **temporary local debug resource overlay** at
   `app/src/debug/res/values/hospital_proxy_verification.xml`. It uses the existing resource setting,
   enables only the debug build, needs no build/CI dependency change, and must not be committed:

   ```xml
   <!-- Co-authored-by: ChatGPT <noreply@openai.com> -->
   <resources>
       <string name="hospital_proxy_endpoint" translatable="false">https://europe-west1-oncompanion-c4144.cloudfunctions.net/hospitalSearch</string>
   </resources>
   ```

7. Run the normal debug app again. Open **Profile → Edit profile**, enter exactly **`Hôpital Genève`**
   and tap **Search once**. Check suggestions (or a valid empty response) and OpenStreetMap attribution.
   Do not try another name or automatically retry an error. If it fails or returns empty, record that
   outcome; do not claim successful suggestions. Selecting a result is enough; saving is optional
   and only permitted for the fictional dedicated test profile under the approved fixture scope.
8. Before the repeat, use the owner's Firestore Console to confirm that
   `hospitalSearchCache/a04ad894456fad447aef1ea7e9a0928d8e2a41c499dc8a77a53de8425b63db18`
   exists and has a future `expiresAt`. Record only its update time/expiry and coordinator metadata,
   not credentials or record contents. If cache freshness is uncertain, **do not repeat**. Never
   delete cache/quota/control documents or read the quota secret to force a test outcome.
9. Force-stop/reopen the debug app to clear the Android memory cache, then submit the **identical
   query once**. Its shared-cache response should match, with unchanged server cache/control metadata.
   This checks the server path rather than merely the in-memory adapter. Stop after this repeat.
   Any unexpected error/miss means stop and report; it does not authorize another attempt.
10. With the result now in Android's memory cache, turn the emulator offline and verify cached
    display/manual typing without another online request. Use the existing automated fake-transport
    tests for 429/503/network/error handling; do not create live faults or exhaust quota.
11. Remove the temporary overlay; keep the main endpoint empty until final activation is reviewed.
    Revoke the temporary debug registration and clear local Logcat. Clean up/revoke only the
    dedicated test account/profile if that cleanup was approved. Do not delete shared backend data.

For safe automated tests, start local Auth/Firestore emulators on 9099/8080 and run
`HospitalProfileTest` from `app/src/androidTest`. The existing runner wires both SDKs to emulators;
those unsigned emulator tokens cannot authenticate to the deployed proxy. The normal live debug app
must not be wired to those emulators. No release keystore or production sign-in UI change is needed.

## How to report results

Record status, result count, attribution and safe cache/coordinator metadata in the verification issue.
Distinguish emulator evidence, deployed negative cases and actual authenticated success. Record
unverified cases explicitly: authenticated success/server cache, valid-Auth App Check rejection,
successful-request logging, release Play Integrity and Android activation. Live quota exhaustion is
not covered by the two-request budget; use automated tests instead. Monitoring delivery is separate.

Never include passwords, Firebase/App Check tokens, enrollment secrets, request URLs or full Logcat.
A debug-provider request does not verify release attestation. The server-cache repeat must bypass
Android's in-memory cache and must not create a second upstream request. Stop if unsure.

Sources: [Firebase custom tokens](https://firebase.google.com/docs/auth/admin/create-custom-tokens),
[Android custom sign-in](https://firebase.google.com/docs/auth/android/custom-auth),
[debug App Check](https://firebase.google.com/docs/app-check/android/debug-provider).
