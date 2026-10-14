<!-- Co-authored-by: Codex <noreply@openai.com> -->

# Appointment form screenshots

Device-rendered examples captured for PR #182, using fictional input and the app theme:

| State | Screenshot |
| --- | --- |
| Empty | [empty.png](empty.png) |
| Filled and valid | [filled.png](filled.png) |
| Saving | [saving.png](saving.png) |
| Immediate save error | [error.png](error.png) |
| Repeated local time | [repeated-time.png](repeated-time.png) |

The form scrolls; the repeated-time capture shows its upper portion. Saving and error are supplied UI states, not simulated production writes.

The one-off screenshot-generation test was removed after these captures were reviewed; it duplicated existing form-state tests. These images are review artifacts, not automated visual regression baselines.

For future UI changes, capture fresh empty and filled states from the emulator using fictional input. Capture saving, error and repeated-time states with a temporary local preview or capture harness using `AppointmentFormContent`; do not change production behavior to stage those states. Inspect the images before replacing these files.

Behavior coverage remains in `AppointmentFormScreenTest`, `AppointmentFormViewModelTest`, `AppointmentNavigationTest` and `PlanningAppointmentsTest`.
