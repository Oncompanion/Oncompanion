<!-- Co-authored-by: Claude Code <noreply@anthropic.com> -->

# Morning on the lake theme (#343)

These images compare `main` at `e40f1e7a` with the new theme (colors, Figtree, shapes), using the
same fictional data on a 412 × 915 dp phone. Only the theme changes: the layouts are the same, and
each area's own UI task (#346–#355) restyles them.

| Screen | Light and dark, before and after |
| --- | --- |
| Sign in | [sign-in.png](sign-in.png) |
| Overview, with its navigation bar | [overview.png](overview.png) |
| Symptom journal | [symptom-journal.png](symptom-journal.png) |
| Log symptoms | [log-symptoms.png](log-symptoms.png) |
| Prescriptions | [prescriptions.png](prescriptions.png) |
| All five at 200% font scale, after | [font-200.png](font-200.png) |

At 200% font, no text is cut by the theme. Two labels were already cut on `main` and still are:
"Enter manually" on Prescriptions and a long note in the symptom journal. Their layouts are fixed
in their area's UI task.

A temporary capture harness (Robolectric, native graphics) gave the stateless screens fictional
UI state; it was removed after capturing the images.
