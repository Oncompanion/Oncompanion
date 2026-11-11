<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Minimal course-project proxy monitoring proposal

Deployment task: [#217](https://github.com/Oncompanion/Oncompanion/issues/217).
Deployment evidence: [#435](https://github.com/Oncompanion/Oncompanion/pull/435).

| Resource | State |
| --- | --- |
| Email channel | `projects/oncompanion-c4144/notificationChannels/4564218546212057707`, enabled; delivery unverified |
| Project budget | CHF 5/month, actual 50/90/100%, forecast 100%; alerts only, not a spend cap |
| Proxy alert policies | Two proposals below; not created or activated |
| Operators | Primary: project owner; backup: TBD |

Do not recreate the existing channel or budget. Historical setup/approvals are in #435. Obtain
team agreement and separate authorization before live monitoring changes. Keep the recipient address
private; no patient data, credentials or query text belongs in alert configuration or notifications.

## Policy design
- One log-match policy for unexpected failures, exhausted release retries, retained oversized waits,
  and storage/upstream failures. It uses only fixed sanitized event names, with notifications limited
  to once per hour. No request-derived labels or message/exception parsing.
- One metric-threshold policy with two OR conditions: at least **5 HTTP 429** responses or
  **3 HTTP 503** responses in five minutes. Sum across every revision/instance of only the verified
  proxy service; use no per-instance average. Native request counts also cover platform responses
  that do not reach the handler. Normal contention can cause 429; this is not proof of a stuck lock.

The existing budget covers the whole project, includes credits and tracks net billed spend. Budget
emails can lag and do not stop spending.

No custom log-based metrics, uptime checks, scheduled searches, queue, Pub/Sub automation, dashboard,
new IAM grants or billing auto-shutdown. The native request-count descriptor uses
`response_code`, DELTA metric kind and INT64 values.

## Bind and approve before applying

Verify the function's actual service name against `hospitalsearch` in DEPLOYMENT.md.
Bind the filters to that service/location, never the entire project. Preserve existing invocation IAM.
The email channel exists; verify delivery with a separately authorized test, then substitute its resource name for
`__CREATED_EMAIL_CHANNEL_RESOURCE_NAME__`. The policy examples are disabled as a safeguard; activating them
requires explicit approval. Local JSON validation is not a cloud API or delivery test.

After approval, submit each fully configured policy to:
`POST https://monitoring.googleapis.com/v3/projects/oncompanion-c4144/alertPolicies`.
Read the existing policies first and preserve unrelated resources. Enable only the two reviewed
policies. Use `oncompanion-c4144` as the quota project for authorized API calls; stop on an access
error rather than granting permissions or enabling APIs implicitly.

## Configuration bodies

The wrapper below is a review artifact. The channel/budget bodies document existing settings, not
instructions to create duplicates. Submit only each reviewed `alertPolicies` entry. Replace channel
placeholders with the existing resource name and obtain authorization before enabling a policy.

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
