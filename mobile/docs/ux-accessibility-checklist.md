# UX and accessibility checklist (MOBILE-007)

Automated coverage: design-token lint (`tokens.test.ts`, covers `app/`, `src/shared/ui`, `src/shared/forms`,
`src/features`), WCAG AA palette contrast (light/dark), touch targets, double-submit guard, privacy shield,
no `console.*` in shipped code (`polish.test.tsx`).

## States per screen (loading / empty / error / success)

| Screen                     | Loading            | Empty                | Error (retry)       | Success             |
| -------------------------- | ------------------ | -------------------- | ------------------- | ------------------- |
| Home dashboard             | Skeleton           | EmptyState + add CTA | ErrorState          | Summary / breakdown |
| Expenses list              | Skeleton rows      | EmptyState + add CTA | ErrorState          | List, toast         |
| Expense detail / edit      | Skeleton           | n/a                  | ErrorState          | Toast               |
| Add expense                | Skeleton (lookups) | n/a                  | Inline field errors | Toast               |
| Categories                 | Skeleton rows      | EmptyState           | ErrorState          | Toast               |
| Budget                     | Skeleton           | EmptyState + create  | ErrorState          | Toast               |
| Onboarding / auth / verify | Button busy        | n/a                  | Inline field errors | Navigation          |
| Settings (invitations)     | Skeleton row       | Empty text (inline)  | ErrorState          | Toast               |

## Manual checks to run on device before release (not automatable here)

- Dynamic type 200 % on Home, Add expense and Budget: amounts and primary actions not clipped.
- VoiceOver / TalkBack: add expense, view budget, read dashboard.
- Reduced motion: sheet uses no animation (`useReducedMotion`).
- App switcher: iOS shows the privacy cover (`PrivacyShield`, AppState based). **Android is NOT
  reliably covered** (snapshot may precede the cover; no `FLAG_SECURE`, needs a native module and a
  dependency decision). The cover also appears on iOS "inactive" (permission/picker sheets).

## Status of the remaining items (explicit)

Automated here: touch-target sizing of Button, `Text` font-scale cap (`maxFontSizeMultiplier`), toast
screen-reader announcement, double-submit guard with busy state, privacy cover, no `console.*`.

NOT verified in this pass (manual, results not recorded): 200 % dynamic-type walkthrough, VoiceOver/TalkBack
walkthrough, reduced motion beyond the sheet (toast has no animation), dashboard re-render profiling,
release-build log stripping, keyboard-visibility on every form.
