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
| Add expense                | Skeleton (lookups) | n/a                  | Inline + ErrorState | Toast               |
| Categories                 | Skeleton rows      | EmptyState           | ErrorState          | Toast               |
| Budget                     | Skeleton           | EmptyState + create  | ErrorState          | Toast               |
| Onboarding / auth / verify | Button busy        | n/a                  | Inline field errors | Navigation          |
| Settings (invitations)     | Skeleton row       | Empty text           | ErrorState          | Toast               |

## Manual checks to run on device before release (not automatable here)

- Dynamic type 200 % on Home, Add expense and Budget: amounts and primary actions not clipped.
- VoiceOver / TalkBack: add expense, view budget, read dashboard.
- Reduced motion: sheet uses no animation (`useReducedMotion`).
- App switcher shows the privacy cover (`PrivacyShield`, AppState based). Android `FLAG_SECURE`
  (screenshot blocking) needs a native module and is not part of this pass.
