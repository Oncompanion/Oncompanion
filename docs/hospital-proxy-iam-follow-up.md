<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Follow-up security review: deployment service agents

Separate from the hospital-proxy deployment PR. **Investigation only; no IAM or API cleanup is
included in PR 4.** This is a follow-up task specification, not an approved permission change.

## Observed evidence

During Firebase CLI 15.30.2 source discovery, `firebaseextensions.googleapis.com` was enabled
unexpectedly. The client was stopped; the already-submitted function creation completed remotely.
No extension instance, event trigger or request queue was created. Public invocation was completed
later with explicit approval, using a service-only etag-preserving IAM update.

IAM audit entries name `service-agent-manager@system.gserviceaccount.com` as the actor for:

| Principal | Automatically assigned project role |
| --- | --- |
| `service-782199763007@gcp-sa-firebasemods.iam.gserviceaccount.com` | `roles/firebasemods.serviceAgent` |
| `782199763007@cloudservices.gserviceaccount.com` | `roles/editor` |

Google documents the first as its Firebase Extensions agent. The second is the Google APIs service
agent, which runs internal platform processes. The project was created on 28 September 2026;
Deployment Manager API is enabled. Google's service-account documentation describes automatic
Editor assignment when that API is enabled. This is consistent with the observed system grant,
not evidence that the hospital proxy itself needs Editor or Extensions.

The function actually runs as `hospital-search@oncompanion-c4144.iam.gserviceaccount.com`:
`roles/datastore.user` on the project and `roles/secretmanager.secretAccessor` on only
`HOSPITAL_QUOTA_SECRET`. It has no Editor grant or user-managed keys. These runtime grants,
secret value and coordinator contract must remain separate from this follow-up.

## Review and definition of done

- [ ] Inventory services/resources relying on the Google APIs agent and Deployment Manager;
      distinguish runtime needs from Firebase CLI's extension-discovery side effects.
- [ ] Review Google's Role Recommender and use Policy Simulator before proposing narrower roles.
      Absence of recent usage is not proof that removing a platform role is safe.
- [ ] Determine whether Extensions/Deployment Manager can be disabled without affecting other work,
      and how subsequent Firebase CLI deployments would avoid silently enabling them again.
- [ ] Present exact proposed role/API changes, dependencies, verification and rollback for approval.
- [ ] Make no changes until separately approved. Preserve unrelated bindings, the proxy invoker
      policy and the dedicated runtime account. Do not rotate the quota secret or reset the lock.
- [ ] Verify a separately approved cleanup using the deployed function's configuration and bounded
      tests; record residual permissions and reasoning. Do not run Nominatim load tests.

Standard Google-managed identities are not automatically necessary dependencies of this proxy.
PR 4 leaves their existing state unchanged and does not claim their broad permissions are justified.

Sources: [service-agent reference](https://docs.cloud.google.com/iam/docs/service-agents),
[service-account types and automatic role grants](https://docs.cloud.google.com/iam/docs/service-account-types),
[Cloud Run functions IAM](https://docs.cloud.google.com/functions/docs/concepts/iam).
