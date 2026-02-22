# Sensum UI Redesign Blueprint

## Goal
Make Sensum feel premium, modern, and intentional while preserving the current fast voice-typing flow.

## Product Principles
1. Voice-first: mic remains the hero element.
2. Pixel-like clarity: strong hierarchy, large touch targets, smooth motion, high readability.
3. Minimal cognitive load: fewer controls visible at once, clear primary action.
4. Consistent iconography: no mixed text-symbol keys for control buttons.

## Current UX Issues (Observed)
1. Enter action key showed text labels (`Search`, `Send`) instead of iconography.
2. Bottom row feels crowded and visually unbalanced.
3. Settings button competes with typing controls.
4. Onboarding screens still feel utilitarian instead of premium.
5. Keyboard control semantics are good, but visual hierarchy does not communicate priority.

## Target Visual Direction (Material 3 Expressive)
1. Tonal surfaces with subtle dynamic gradients (no flat slabs).
2. Rounded geometry system (large radius for containers, medium radius for keys).
3. One hero action (mic), one main text key (space), one compact action rail (backspace + enter/search).
4. Icon-first control language for utilities and actions.
5. Motion with purpose: short spring-in on state transitions, not decorative noise.

## Keyboard Layout V2 (Proposed)
```
[state / transcription strip]

       [  MIC  ]
[settings chip]    [action rail: enter/search]
        [      SPACEBAR      ] [backspace]
```

## Component Spec
### 1) Mic (Hero)
1. Keep centered and large.
2. Keep recording states and pulse animation.
3. Keep highest visual prominence.

### 2) Spacebar
1. Move directly under mic as the central typing anchor.
2. Wider than any other key.
3. Subtle tonal fill, high-contrast label.

### 3) Action Rail (Right)
1. Enter key is dynamic:
   - Search: magnifier icon
   - Go: arrow/launch icon
   - Send: paper-plane icon
   - Next: forward icon
   - Done: check icon
   - Default: return glyph
2. Backspace uses consistent icon shape and weight.
3. Rail buttons share identical size and corner style.

### 4) Settings (Left Utility)
1. Move from main row to a compact chip/floating button near the mic.
2. Reduce visual weight vs typing controls.
3. Keep single-tap access, but de-emphasize.

## Onboarding Spec
1. Screen 1:
   - Branded logo card
   - Strong headline + short supporting line
   - Primary CTA
2. Screen 2:
   - One-time setup card with plain-language API steps
   - Visible reassurance that setup is one-time
3. Screen 3:
   - Keyboard-enable instructions in a card
   - Explicit `Continue` after enabled (no auto-skip)

## Style Tokens
1. Spacing scale: 8 / 12 / 16 / 24 / 32.
2. Radius scale: 16 / 24 / 32.
3. Typography:
   - Hero: 30sp semibold
   - Section title: 24-26sp semibold
   - Body: 16-17sp
   - Support text: 12-13sp
4. Elevation:
   - Mostly flat tonal layers
   - Elevation only for hero controls and active emphasis

## Motion Guidelines
1. Enter/exit transitions: 160-220ms.
2. Key press scale: 0.96 with fast return.
3. Success states: short pop on mic only.
4. Avoid continuous animation unless recording is active.

## Implementation Plan
### Phase 1 (Now)
1. Fix icon inconsistencies (enter/search/send/go/next/done, backspace, settings).
2. Fix onboarding page-control logic and visual polish baseline.
3. Keep layout stable while improving legibility and consistency.

### Phase 2 (Next Iteration)
1. Restructure keyboard layout to `hero + space + action rail`.
2. Move settings to left utility position near mic.
3. Rebalance spacing and alignment based on thumb ergonomics.

### Phase 3 (Refinement)
1. Fine-tune animation timings and haptics.
2. Contrast/accessibility audit in light/dark and dynamic colors.
3. Device QA on different screen sizes/aspect ratios.

## Definition of Done
1. No onboarding auto-skip regressions.
2. Enter key always shows icon in actionable contexts.
3. Backspace and settings icons are crisp and centered.
4. Keyboard looks balanced with clear primary/secondary hierarchy.
5. Visual style remains coherent in light, dark, and dynamic themes.

## QA Checklist
1. Browser URL input: Enter shows search/go icon and triggers action.
2. Messaging app: Enter shows send icon and sends when supported.
3. Notes app multiline: Enter falls back to newline.
4. Onboarding: Step 2 never auto-skips; Step 3 requires explicit continue.
5. Contrast and readability pass on Samsung + Pixel.
