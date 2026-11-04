---
name: Oncompanion
description: Morning on the lake — a calm, warm Material 3 theme for patients in cancer therapy
colors:
  primary: "#2F6F7E"
  on-primary: "#FFFFFF"
  primary-container: "#D7E7EA"
  on-primary-container: "#0E3640"
  primary-text: "#2F6F7E"
  secondary: "#8A4F2E"
  on-secondary: "#FFFFFF"
  secondary-container: "#FADBC8"
  on-secondary-container: "#3A1E0E"
  tertiary: "#6A5A8C"
  on-tertiary: "#FFFFFF"
  tertiary-container: "#E7DEF8"
  on-tertiary-container: "#251A3F"
  error: "#B3261E"
  on-error: "#FFFFFF"
  error-container: "#F9DEDC"
  on-error-container: "#410E0B"
  surface: "#FBF8F4"
  on-surface: "#1F2A30"
  on-surface-variant: "#52606A"
  outline: "#7A858C"
  outline-variant: "#E0DAD2"
  surface-container-lowest: "#FFFFFF"
  surface-container-low: "#F7F3EE"
  surface-container: "#F2EDE7"
  surface-container-high: "#ECE6DF"
  surface-container-highest: "#E6E0D8"
  inverse-surface: "#2E363B"
  inverse-on-surface: "#F2EEE9"
  inverse-primary: "#9AD0DC"
  horizon-dawn-top: "#F6D2B8"
  horizon-dawn-bottom: "#EFB892"
  horizon-day-top: "#CFE5EA"
  horizon-day-bottom: "#A9CFD8"
  horizon-dusk-top: "#E3D9F0"
  horizon-dusk-bottom: "#C3B5E0"
  on-horizon: "#1F2A30"
  dark-primary: "#9AD0DC"
  dark-on-primary: "#00363F"
  dark-primary-container: "#134F5B"
  dark-secondary-container: "#6B3B1F"
  dark-surface: "#151B1E"
  dark-surface-container: "#1F2629"
  dark-on-surface: "#E3E6E7"
  dark-on-surface-variant: "#BCC6CB"
typography:
  display-small:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "36px"
    fontWeight: 700
    lineHeight: "44px"
    letterSpacing: "-0.042em"
  headline-large:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "32px"
    fontWeight: 700
    lineHeight: "40px"
    letterSpacing: "-0.0375em"
  headline-medium:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "28px"
    fontWeight: 700
    lineHeight: "36px"
    letterSpacing: "-0.036em"
  headline-small:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "24px"
    fontWeight: 600
    lineHeight: "32px"
    letterSpacing: "-0.021em"
  title-large:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "22px"
    fontWeight: 600
    lineHeight: "28px"
    letterSpacing: "-0.014em"
  title-medium:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: "24px"
  title-small:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: "20px"
  body-large:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "17px"
    fontWeight: 400
    lineHeight: "26px"
  body-medium:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: "22px"
  body-small:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "18px"
  label-large:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: "20px"
  label-medium:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "13px"
    fontWeight: 600
    lineHeight: "16px"
    letterSpacing: "0.015em"
  label-small:
    fontFamily: "Figtree, Roboto, sans-serif"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: "16px"
    letterSpacing: "0.025em"
rounded:
  small: "8px"
  medium: "12px"
  large: "20px"
  extra-large: "28px"
  full: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  lg-plus: "20px"
  xl: "24px"
  xxl: "32px"
components:
  button-filled:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.label-large}"
    rounded: "{rounded.full}"
    padding: "0 24px"
    height: "56px"
  button-tonal:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary-container}"
    rounded: "{rounded.full}"
    height: "56px"
  chip-selected:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
    rounded: "{rounded.medium}"
    height: "48px"
  chip:
    backgroundColor: "{colors.surface-container-lowest}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.medium}"
    height: "48px"
  card:
    backgroundColor: "{colors.surface-container-lowest}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.large}"
    padding: "18px 20px"
  field-uncertain:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
    rounded: "{rounded.medium}"
  nav-indicator:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
    rounded: "{rounded.full}"
    size: "64px 32px"
  dialog:
    backgroundColor: "{colors.surface-container-lowest}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.extra-large}"
    width: "312px"
  snackbar:
    backgroundColor: "{colors.inverse-surface}"
    textColor: "{colors.inverse-on-surface}"
    rounded: "{rounded.medium}"
  member-boat:
    size: "48px / 40px"
    typography: "Figtree Bold 13 / 11"
---

# Design System: Oncompanion

Source of truth: the **Brand & Theme** page of the Figma file (`LoQ2CmgkxNQhODegJmo7wd`):

- variable collections *Oncompanion* (modes Light and Dark) and *Shape & Space*, text styles *Oncompanion/\**, effect style *Oncompanion/Elevation 1*, and the local components;
- the section **Screens – Paper lake**, one column per feature, every screen with its loading, empty, error and offline states.

The **M2** page lays these screens out as the user stories, with arrows and clickable prototype links (Prototype mode, one flow per story). Other sections of Brand & Theme (*Screens*, *Screens – Pastel purple*, *Illustration directions*, *Logo tests*) and the *Pastel purple* variable modes are earlier explorations, not part of the theme.

Pixel values here are dp (sizes) and sp (text) on Android.

## Overview

**Creative North Star: "Morning on the lake"**

A calm, warm Material 3 app for people in cancer therapy, used on their worst days, often one-handed and tired. The interface stays quiet so today's care can be found in seconds. Its one signature is Lake Geneva drawn in cut paper: the Salève, the Jet d'eau and the water bands sit behind the title of each screen, in the colours of the time of day.

**Key Characteristics:**
- Warm off-white ground, white cards, deep ink text; lake teal is the only strong colour.
- Dawn peach and dusk violet appear as containers and in the illustrations, never as large fields elsewhere.
- Figtree for every role, one step larger than the Material defaults for body text.
- One filled button per screen, always the main action.
- People are boats on the lake: each member of the care circle has a small paper boat with their initial on the sail.

## Colors

### Primary
**Lake teal** (`#2F6F7E`; dark `#9AD0DC`): the filled button, checks for taken doses, links and text buttons (*Primary Text*). Its container (`#D7E7EA`) carries the appointment time badge, the week summary and tonal buttons.

### Secondary
**Dawn** (`#8A4F2E`, container `#FADBC8`): selection (chips, navigation indicator) and content the app drafted and wants checked (a scanned field, what voice picked up).

### Tertiary
**Dusk** (`#6A5A8C`, container `#E7DEF8`): evening and calm, secondary information, and the logo ribbon.

### Neutral
Warm off-white surface (`#FBF8F4`), white cards (`#FFFFFF`), ink (`#1F2A30`) and slate secondary text (`#52606A`). Every text pair passes WCAG AA in light and dark (lowest: primary on white 5.7:1).

### Horizon
The *Horizon* variables give each time of day its sky and lake: dawn 05:00–11:00, day 11:00–18:00, dusk 18:00–05:00, in both themes. Text on the illustrations always uses *On Horizon*.

### Dark theme
Every role has a Dark value in the variables (surface `#151B1E`, primary `#9AD0DC`, primary container `#134F5B`). The dark theme is designed from those values, never by inverting colours. The scan camera is always dark.

**The Paper Lake Rule.** No gradients anywhere. Illustrations are flat shapes in solid colours, layered like cut paper.

**The Quiet Accent Rule.** Strong teal appears in one large shape per screen at most: the main button. Everything else uses containers.

## Typography

**Body Font:** Figtree (fallback Roboto)

**Character:** friendly, open and very legible; round enough to feel human, plain enough to read through fatigue.

### Hierarchy
- **Display Small** (700, 36/44): times in the time picker and the clock in the notification shade.
- **Headline Large** (700, 32/40): the greeting on main screens ("Good morning, Alex", "Alex's care ▾") and the welcome on Sign in.
- **Headline Medium** (700, 28/36): the title of a detail page (an appointment, an event, a prescription) and onboarding questions.
- **Headline Small** (600, 24/32): the date in the date picker, a medication's name, the voice states.
- **Title Large** (600, 22/28): app bar titles, card titles, "Today".
- **Title Medium** (600, 17/24): list titles, times, form questions, section headings.
- **Title Small** (600, 15/20): group headers in lists ("This month", "Scheduled", the week range).
- **Body Large** (400, 17/26): field values and the date under the greeting. **Body Medium** (15/22): details. **Body Small** (13/18): helper text.
- **Label Large** (600, 15/20): buttons and chips. **Label Medium** (13/16): navigation labels and tags ("Wellbeing"). **Label Small** (500, 12/16): weekday and month labels in calendars.

**The 200% Rule.** All text is sp and must reflow at 200% font scale: no fixed-height text containers, no truncated labels.

## Layout

16 dp side margins, 12 dp between cards, 24 dp between form groups. Times sit in a column at least 52 dp wide, growing with the text, so titles align.

- **Main screens** (Overview, Planning, Events): Paper lake header (at least 210 dp, growing with the title) with the title and date on the sky; in landscape or at a large font scale it shrinks to the 100 dp band; the first card overlaps its lower edge; the main button is pinned above the navigation bar.
- **Other screens**: a 100 dp Paper lake band behind the top app bar (back arrow, or close for forms and flows), content below, actions pinned at the bottom (16 dp from the sides, 24 dp from the bottom).
- Long screens scroll under the band; the top app bar and the pinned actions stay.

## Elevation & Depth

Flat by default. One soft shadow, **Elevation 1** (0 1 2 at 6% + 0 6 18 at 8%), only on the card that overlaps the header, on floating buttons and on the voice microphone.

## Shapes

Small 8 (thumbnails), Medium 12 (fields, chips, snackbars), Large 20 (cards, lists), Extra Large 28 (dialogs, sheets), Full (buttons, navigation indicator, round icon buttons).

## Illustrations

### Paper lake
Flat cut paper in the Horizon colours: sky, the Salève, the Jet d'eau, the sun or moon, and three bands of water. Three sizes: the header (at least 210 dp), the band behind top app bars (100 dp) and the Sign in scene. The sky follows the time of day. Static when the system's Remove animations setting is on.

### Empty lake
A round medallion of the same lake with one small boat, for empty states (nothing planned, no prescriptions, no one in the circle).

### Member boats
Each person in the care circle is a small paper boat (*Member boat*: Large 48, Small 40; tones Dawn, Dusk and Lake, and Waiting with a dashed sail for someone who hasn't joined yet). Sail colour is decorative, assigned by position, never a judgement. Their initial sits on the sail like a sail number: Figtree Bold 13 (11 on the small boat), left-aligned to the mast, 3 dp above the foot of the sail; two letters when first letters collide (AM, AR).

## Logo

The team's logo is a lavender awareness ribbon, drawn as the *Logo/Ribbon* component in the Tertiary colour. It appears on Sign in and as the app icon in notifications. On the launcher icon the ribbon fills about 56% of a plain tile so it survives round and squircle masks; the tile colour is chosen among the options on *Logo – Solid ribbon* (contrast ratios noted there).

## Components

### Buttons
Pills 56 dp tall, 24 dp side padding. Filled for the one main action, Tonal for a strong secondary, Outlined or Text for the rest. Disabled at 38% opacity. Round icon buttons are 48 dp (e.g. call, microphone).

### Chips
Filter chips 48 dp tall (full touch target). Selected: dawn container with a check icon.

### Cards / Containers
White on the warm ground, Large radius, no border. Lists live in one card with 1 dp Outline Variant dividers inset 16 dp. A row shows its value above its label (Body Large, then Body Small).

### Inputs / Fields
Outlined, Medium radius, 56 dp, label inside above the value, trailing icon in a 48 dp target. States: empty, filled, error (red border and message) and *check*: a value the app drafted uses the dawn container, a 1.5 dp secondary border and the label "… · please check".

### Switches
Material switches in a card row: name and one-line description on the left, switch on the right (e.g. what a member can see, notifications).

### Navigation
Material 3 navigation bar on Surface Container: Overview, Planning, Events. Active indicator 64×32 in the dawn container. Top app bar: back arrow for screens opened from another screen, close for forms and full-screen flows.

### Today row
Time, title, detail and a 48 dp check target. A taken dose dims (On Surface Variant) in place; rows are sorted by time and never move when their state changes.

### Dialogs, snackbars and banners
- **Dialog**: white, Extra Large radius, 312 dp wide, over a scrim, title, message and text buttons, inline or stacked when labels are long. Only a destructive action is red. Used only for decisions (delete, remove, a duplicate prescription).
- **Snackbar**: short-lived feedback at the bottom, with an action when it helps (Retry).
- **Inline banner**: a lasting note at the top of the content. Neutral (information, a blocked permission) or Waiting (offline, drafted content to check).
- **Message state**: icon, title, one line and an optional action, centered where the content would be.

### Progress
Circular progress, 48 dp, centered where the content will appear; the small on-primary variant sits inside a button ("Signing you in…").

## Patterns

### Loading, empty, error, offline
- **Loading**: the screen keeps its header and bars; a progress indicator replaces only the content.
- **Empty**: the Empty lake, a title, one line saying what will appear, and the action that fills it.
- **Error**: a Message state with Retry. Offline is not an error: the app shows what it saved on the phone, with a Waiting banner when it matters ("You're offline. This is the list saved on your phone."), shown when Firestore says the data came from its cache (`isFromCache`). Never "check your connection" for a Firestore error.
- **Gone**: something deleted elsewhere says so ("This event doesn't exist anymore") with a way back.

### Drafted content
Anything the app drafts (a scanned prescription, what voice picked up) lands in the normal form, marked *please check*, with a Waiting banner. Nothing is saved until the patient taps Save.

### Permissions
Camera, microphone and notifications are asked for when first needed. When refused, the screen explains it in one sentence, offers **Open settings**, and always a way without it (Enter manually, Type instead).

### Caregiver mode
The same screens, read-only. The Overview title is "Alex's care ▾" (tap to switch person). No add, edit or delete actions, except logging a symptom when the patient allows it. A section the patient doesn't share says so ("Alex doesn't share Planning with you"), never an empty screen.

### Voice
"Speak instead" at the top of Log symptoms opens *Speak your symptoms*: the microphone floats on the Paper lake with ripples while listening, the words appear as they are said, and what was picked up shows as dawn chips. Done returns to the form, filled and marked to check.

### Reminders
Android notifications with the ribbon icon: what and when ("Oncology check-up at 09:30", "In 1 hour · HUG"). Caregiver reminders start with the patient's name. Reminders never advise and never say whether a dose was taken. Each category (appointments, events, medications) has a switch in Profile.

## Do's and Don'ts

### Do:
- **Do** keep one filled button per screen.
- **Do** keep every touch target at 48 dp or more.
- **Do** show what a scan read next to the photo and ask the patient to confirm every field.
- **Do** design the dark theme from the Dark mode of the variables, never by inverting.
- **Do** give every screen its loading, empty, error and offline states.

### Don't:
- **Don't** use gradients, anywhere.
- **Don't** use red, or any colour, to judge a health value: ratings are the patient's own words.
- **Don't** add a label above a heading (no eyebrows or kickers).
- **Don't** use plant, medical-cross or heart-rate imagery as decoration.
- **Don't** save anything the app drafted without the patient's confirmation.
