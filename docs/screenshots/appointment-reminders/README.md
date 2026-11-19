<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->

# Appointment reminder choice (#489)

Captured from `AppointmentEditTest` on an isolated Android emulator with fictional appointment
information and local Firebase Auth/Firestore emulators. No live account or cloud data is used.

- [Edit form](edit-form.png): No reminder is retained in the draft; the form scrolls to Save.
- [Portrait picker](picker-portrait.png): all five choices, one-hour selection, shared dialog styling.
- [Landscape picker](picker-landscape.png): options scroll; Cancel stays visible. Tests scroll every
  option into full view on normal and small landscape phones.
- [Saved detail](detail.png): offline edit displays No reminder before synchronizing the same
  appointment to the emulator. Android Back and rotation of the open picker are also exercised.

[Figma reference](https://www.figma.com/design/LoQ2CmgkxNQhODegJmo7wd/OnCompanion?node-id=1818-5693).
Existing toolbar, category layout and other appointment styling are outside this change.
