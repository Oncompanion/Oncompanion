<!-- Co-authored-by: Codex <noreply@openai.com> -->

# Appointment form screenshots

Device-rendered examples from `AppointmentFormScreenshotsTest`, using fictional input and the app theme:

| State | Screenshot |
| --- | --- |
| Empty | [empty.png](empty.png) |
| Filled and valid | [filled.png](filled.png) |
| Saving | [saving.png](saving.png) |
| Immediate save error | [error.png](error.png) |
| Repeated local time | [repeated-time.png](repeated-time.png) |

The form scrolls; the repeated-time capture shows its upper portion. Saving and error are supplied UI states, not simulated production writes.

Run `AppointmentFormScreenshotsTest` through the project's instrumentation runner to regenerate. It writes `appointment-form-<state>.png` to the target application's cache. Copy the captures before uninstalling the app; Gradle's connected test cleanup removes that cache.
