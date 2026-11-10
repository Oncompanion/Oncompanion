<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Minimal course-project proxy monitoring proposal

**Proxy alert policies are prepared only; channel and budget setup are approved and completed.** Related to #217.
The email channel was separately approved, created and enabled:
`projects/oncompanion-c4144/notificationChannels/4564218546212057707`.
Its metadata matches the approved recipient; Google reports `VERIFICATION_STATUS_UNSPECIFIED`.
No test notification was sent and delivery is not verified. No alert policies were created or changed.
The CHF 5/month project budget was separately approved, created and verified. Its actual-spend
thresholds are 50%, 90%, 100%, plus 100% forecast spending, using this email channel.
The Budget API was enabled in `oncompanion-c4144` only, using it as the explicit quota project;
the Firebase CLI's Google-owned OAuth client project was not changed. Existing budgets were preserved.
Primary operator: project owner. Backup: **TBD**. The owner selected the previously supplied
contact email for alerts; keep the actual address in the private workstation proposal.
A missing backup is a documented course-project limitation, not a claim of continuous coverage.

## Proposed resources

- One email notification channel.
- One log-match policy for unexpected failures, exhausted release retries, retained oversized waits,
  and storage/upstream failures. It uses only fixed sanitized event names, with notifications limited
  to once per hour. No request-derived labels or message/exception parsing.
- One metric-threshold policy with two OR conditions: at least **5 HTTP 429** responses or
  **3 HTTP 503** responses in five minutes. Sum across every revision/instance of only the verified
  proxy service; use no per-instance average. Native request counts also cover platform responses
  that do not reach the handler. Normal contention can cause 429; this is not proof of a stuck lock.
- Approved and created **CHF 5/month alerts-only budget** for the whole `oncompanion-c4144` project, not just
  the proxy, with actual-spend emails at 50%, 90%, 100% and a 100% forecast threshold.
  CHF is the verified billing currency. Credits are included,
  so this tracks net billed spend. Budget emails can lag and do not stop spending.

No custom log-based metrics, uptime checks, scheduled searches, queue, Pub/Sub automation, dashboard,
new IAM grants or billing auto-shutdown. The native request-count descriptor was read and confirms
`response_code`, DELTA metric kind and INT64 values.

## Bind and approve before applying

The ACTIVE function's actual service name has been read and verified as `hospitalsearch`.
Use that exact name in the filters; never silently monitor the entire project's unrelated services.
Public invocation is now set under separate approval; alert policies remain uncreated. See DEPLOYMENT.md.
The approved email channel exists; verify delivery with a separately approved test, then substitute its resource name for
`__CREATED_EMAIL_CHANNEL_RESOURCE_NAME__`. The policy examples are disabled as a safeguard; activating them
requires explicit approval. Local JSON validation is not a cloud API or delivery test.

Only after approval, use these endpoints with the corresponding bodies below:

- `POST https://monitoring.googleapis.com/v3/projects/oncompanion-c4144/notificationChannels`
- `POST https://monitoring.googleapis.com/v3/projects/oncompanion-c4144/alertPolicies` (twice)
- `POST https://billingbudgets.googleapis.com/v1/billingAccounts/{verifiedAccountId}/budgets`

The read-only budget listing was blocked by the Budget API's consumer-project configuration.
Do not change the Firebase CLI's Google-owned OAuth client project. For authorized API use, explicitly
select `oncompanion-c4144` as the consumer/quota project. Enabling `billingbudgets.googleapis.com`
there was separately approved and completed before budget creation. No billing-account IAM change
was needed. Stop for review if future account access is denied; do not grant permissions implicitly.

## Configuration bodies

The wrapper below is a review artifact: submit only each `notificationChannel`, `alertPolicies`
entry or `budget` body to its respective endpoint. Replace all placeholders and obtain approval first.

```json
{
  "notificationChannel": {
    "type": "email",
    "displayName": "Hospital proxy operator",
    "labels": {
      "email_address": "__APPROVED_ALERT_EMAIL__"
    },
    "enabled": false
  },
  "alertPolicies": [
    {
      "displayName": "Hospital proxy failure or retained lock",
      "enabled": false,
      "combiner": "OR",
      "conditions": [
        {
          "displayName": "Sanitized proxy failure event",
          "conditionMatchedLog": {
            "filter": "resource.type=\"cloud_run_revision\" AND resource.labels.project_id=\"oncompanion-c4144\" AND resource.labels.location=\"europe-west1\" AND resource.labels.service_name=\"hospitalsearch\" AND (jsonPayload.event=\"hospital_search_unexpected_failure\" OR jsonPayload.event=\"hospital_search_release_failed\" OR jsonPayload.event=\"hospital_search_upstream_wait_exceeds_limit\" OR jsonPayload.event=\"hospital_search_storage_failure\" OR jsonPayload.event=\"hospital_search_upstream_failure\")"
          }
        }
      ],
      "notificationChannels": [
        "__CREATED_EMAIL_CHANNEL_RESOURCE_NAME__"
      ],
      "alertStrategy": {
        "notificationRateLimit": {
          "period": "3600s"
        },
        "autoClose": "1800s"
      },
      "documentation": {
        "content": "Primary recovery operator: project owner. Backup: TBD. Inspect sanitized events and coordinator state using functions/OPERATIONS.md. Never reset a lock based on its age; prove all potential owners cannot resume before repair. Do not include patient data, queries or credentials in incident notes.",
        "mimeType": "text/markdown"
      }
    },
    {
      "displayName": "Hospital proxy excessive 429 or 503 responses",
      "enabled": false,
      "combiner": "OR",
      "conditions": [
        {
          "displayName": "At least 5 HTTP 429 responses in 5 minutes",
          "conditionThreshold": {
            "filter": "resource.type=\"cloud_run_revision\" AND resource.labels.project_id=\"oncompanion-c4144\" AND resource.labels.location=\"europe-west1\" AND resource.labels.service_name=\"hospitalsearch\" AND metric.type=\"run.googleapis.com/request_count\" AND metric.labels.response_code=\"429\"",
            "aggregations": [
              {
                "alignmentPeriod": "300s",
                "perSeriesAligner": "ALIGN_SUM",
                "crossSeriesReducer": "REDUCE_SUM"
              }
            ],
            "comparison": "COMPARISON_GT",
            "thresholdValue": 4,
            "duration": "0s",
            "trigger": {
              "count": 1
            }
          }
        },
        {
          "displayName": "At least 3 HTTP 503 responses in 5 minutes",
          "conditionThreshold": {
            "filter": "resource.type=\"cloud_run_revision\" AND resource.labels.project_id=\"oncompanion-c4144\" AND resource.labels.location=\"europe-west1\" AND resource.labels.service_name=\"hospitalsearch\" AND metric.type=\"run.googleapis.com/request_count\" AND metric.labels.response_code=\"503\"",
            "aggregations": [
              {
                "alignmentPeriod": "300s",
                "perSeriesAligner": "ALIGN_SUM",
                "crossSeriesReducer": "REDUCE_SUM"
              }
            ],
            "comparison": "COMPARISON_GT",
            "thresholdValue": 2,
            "duration": "0s",
            "trigger": {
              "count": 1
            }
          }
        }
      ],
      "notificationChannels": [
        "__CREATED_EMAIL_CHANNEL_RESOURCE_NAME__"
      ],
      "alertStrategy": {
        "autoClose": "1800s",
        "notificationChannelStrategy": [
          {
            "notificationChannelNames": [
              "__CREATED_EMAIL_CHANNEL_RESOURCE_NAME__"
            ],
            "renotifyInterval": "3600s"
          }
        ]
      },
      "documentation": {
        "content": "Primary recovery operator: project owner. Backup: TBD. Inspect sanitized events and coordinator state using functions/OPERATIONS.md. Never reset a lock based on its age; prove all potential owners cannot resume before repair. Do not include patient data, queries or credentials in incident notes.",
        "mimeType": "text/markdown"
      }
    }
  ],
  "budget": {
    "displayName": "oncompanion course project monthly alert budget",
    "budgetFilter": {
      "projects": [
        "projects/782199763007"
      ],
      "calendarPeriod": "MONTH",
      "creditTypesTreatment": "INCLUDE_ALL_CREDITS"
    },
    "amount": {
      "specifiedAmount": {
        "currencyCode": "CHF",
        "units": "5"
      }
    },
    "thresholdRules": [
      {
        "thresholdPercent": 0.5,
        "spendBasis": "CURRENT_SPEND"
      },
      {
        "thresholdPercent": 0.9,
        "spendBasis": "CURRENT_SPEND"
      },
      {
        "thresholdPercent": 1.0,
        "spendBasis": "CURRENT_SPEND"
      },
      {
        "thresholdPercent": 1.0,
        "spendBasis": "FORECASTED_SPEND"
      }
    ],
    "notificationsRule": {
      "monitoringNotificationChannels": [
        "__CREATED_EMAIL_CHANNEL_RESOURCE_NAME__"
      ],
      "disableDefaultIamRecipients": true
    }
  }
}
```

## Cost and verification

As checked on 10 November 2026, native Google Cloud metric ingestion is non-chargeable. Avoiding
custom metrics and external probes keeps this proposal small. Logging retains its normal usage-based
charges beyond the free allocation (currently 50 GiB/project/month); longer retention can cost extra.
The current pricing page schedules alerting charges no sooner than 1 September 2027. Under that
announced model metric references cost USD 0.35/month each, with additional metric-query charges
potentially applying if the policies remain enabled then. Recheck pricing before
activation or keeping the service running beyond the course; this is not a guaranteed total bill.

Use fixed synthetic event-only logs to check policy matching and notification delivery after approval.
Never manufacture production failures or use real hospital queries, tokens or patient data as test
logs. Verify 429/503 policy aggregation without Nominatim load testing. Until delivery, thresholds and
incident recovery are verified, the proposal is not active monitoring. Idle stuck locks can still be
silent; manually inspect coordinator state during approved rollout and incidents using OPERATIONS.md.

Sources: [Observability pricing](https://cloud.google.com/products/observability/pricing),
[budget behavior](https://docs.cloud.google.com/billing/docs/how-to/budgets),
[alert policy API](https://docs.cloud.google.com/monitoring/api/ref_v3/rest/v3/projects.alertPolicies).
