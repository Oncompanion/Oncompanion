<!-- Co-authored-by: Claude Code <noreply@anthropic.com> -->
# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

- **Patients in active cancer therapy** (primary). Often exhausted, cognitively overloaded ("chemo brain"), sometimes nauseous or in pain. They use the app at home, in waiting rooms and at appointments, often on their worst days. Their job: record how they feel with the least possible effort, keep track of medications and appointments, and prepare for consultations.
- **Relatives in the patient's care circle.** Join through an invite code and follow the patient's care in the sections the patient chose to share (planning, events, symptoms, prescriptions). In a section where the patient also allows it, they can add entries (e.g. log a symptom), each signed with their name; they never edit or delete anything.
- Ligue genevoise staff are **not** users of the app. They keep the events and the directory of contacts and support programs up to date outside the app.

## Product Purpose

An organizational companion for cancer patients: low-effort symptom logging (one tap, voice), a medication schedule drafted from scanned prescriptions, appointments and planning, reminders, events and a directory of emergency contacts and support programs from the Ligue genevoise contre le cancer, and a care circle for relatives. Success means a patient on a bad day can still log a symptom or check today's medication in seconds.

## Positioning

Health tracking that adapts to the patient's energy level: minimal input, minimal cognitive load, built with the Ligue genevoise contre le cancer, which validated the problem and runs testing sessions with real patients.

## Operating Context

- Used one-handed, tired, sometimes lying down, at home or in hospital waiting rooms.
- Essential functions work fully offline (symptoms, medication schedule and prescription scanning, emergency contacts); data syncs once back online.
- Before an appointment the patient can show or export a symptom summary for the doctor (doctors have no account).

## Capabilities and Constraints

- Native Android, Jetpack Compose, Material 3. English UI.
- **Not a medical device**: no diagnosis, no medical advice, never changes a treatment. Anything drafted automatically (e.g. a scanned prescription) is shown for the patient to confirm field by field before saving.
- Prescription scanning uses the camera and on-device text recognition; voice journaling uses on-device speech recognition when available. What either drafts lands in the form for the patient to check.
- Reminders are Android notifications for appointments, confirmed events and medications, with one switch per category in Profile. They repeat what the patient recorded; the app never decides whether a dose was taken.

## Brand Commitments

- Name: **Oncompanion** (never "OnCompanion").
- Feel: warm and human, not clinical. Chosen by the team on 2026-10-26.
- Logo: the team's lavender awareness ribbon. The visual identity ("Morning on the lake": theme, Paper lake illustrations, components) is described in DESIGN.md and drawn in Figma.

## Evidence on Hand

- Partnership and validation with the Ligue genevoise contre le cancer (problem confirmed by staff; patient testing sessions planned).
- No testimonials, metrics or patient quotes exist yet; do not fabricate any.

## Product Principles

1. Every screen must be usable on the patient's worst day.
2. One main action per screen; the fastest path is the default path.
3. Organize, never advise: the app records and reminds, the care team decides.
4. The patient controls what is shared and with whom.

## Accessibility & Inclusion

Hard requirements:
- Text stays usable at 200% system font scale (sp units, no clipped or truncated labels).
- Touch targets of at least 48×48 dp with generous spacing; one primary action per screen.
- WCAG AA contrast for all text and controls.

Dark theme is supported but not a hard requirement.
