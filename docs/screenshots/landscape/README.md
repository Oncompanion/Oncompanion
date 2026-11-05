<!-- Co-authored-by: Codex <noreply@openai.com> -->

# Compact-height layouts (#260)

These device-rendered images compare `main` at `b5bcab45` with this fix, using the same fictional
data, landscape window and scrolling actions. Every image is **2282 × 880 pixels**.

| Screen | Before | After |
| --- | --- | --- |
| Planning, scrolled to its last appointment | [planning-before.png](planning-before.png) | [planning-after.png](planning-after.png) |
| Profile, scrolled to its final fields and actions | [profile-before.png](profile-before.png) | [profile-after.png](profile-after.png) |

Planning's fixed header used to leave a strip that cut off a full appointment row. Profile kept
its toolbar and actions pinned around the details. In compact windows the headers now scroll away,
so the content uses the viewport. The calendar and Back remain reachable by scrolling to the top.
Partially visible earlier content at the top of an After image is the normal result of scrolling.

A temporary capture harness supplied the stateless screens with fictional UI state; it was removed
after capturing the images. Behavior is covered by the landscape and restoration tests, activity
recreation tests, portrait regression tests and the real-app rotation steps in `M1EndToEndTest`.
