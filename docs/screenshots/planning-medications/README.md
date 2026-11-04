<!-- Co-authored-by: Codex <noreply@openai.com> -->

# Saved medications in Planning

Device-rendered examples from `PlanningMedicationsTest`, using fictional prescriptions and the
production Planning provider. The test writes through the medication repository and verifies the
screen while Firestore networking is disabled; it does not stage a supplied UI state.

| State | Screenshot |
| --- | --- |
| Medications and a timed appointment, including missing frequency | [mixed.png](mixed.png) |
| The same medications active in the following week | [next-week.png](next-week.png) |
| A medication edited offline and its sibling removed | [edited-offline.png](edited-offline.png) |

The rows describe recorded active medication days. Free-text frequency is displayed as entered;
no intake times, reminders or taken/overdue status are inferred. These images are review evidence,
not visual regression baselines.
