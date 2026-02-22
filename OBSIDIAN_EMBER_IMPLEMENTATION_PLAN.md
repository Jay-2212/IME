# Sensum Obsidian Ember - Engineering Execution Checklist

## 0. Document Control
- Document type: Execution contract (implementation-ready)
- Status: Approved planning baseline
- Code policy for this chat: Do not implement, document only
- Target app: Sensum Android IME
- Workspace root: `/Users/jaybharti/Documents/App/IME`
- Primary objective: Ship a premium, black-and-orange Material 3 experience without regressions in voice typing speed or onboarding reliability

## 1. How To Use This Contract
- Follow milestones in order.
- Do not skip a gate.
- Each task must end with a verifiable check.
- If any requirement is ambiguous, use Section 15 defaults.
- If a requirement conflicts with existing behavior, preserve functional correctness first, then visual polish.

## 2. Product Intent (No Guesswork)
### 2.1 Experience target
- Visual style: Obsidian Ember (matte black + warm orange accents)
- Personality: calm, authoritative, premium, minimal
- UX priority: voice-first, one obvious hero action, reduced visual noise

### 2.2 Success criteria (user-facing)
- Onboarding never auto-skips the API step or keyboard-enable step.
- Keyboard controls are icon-first and visually balanced.
- Enter key is dynamic and always context-correct.
- Settings no longer feel generic stock preferences.
- Theme is cohesive from launcher icon -> onboarding -> keyboard -> settings.
- Optional system color mode exists, but brand mode is the default.

## 3. Hard Constraints (Non-Negotiable)
- Preserve app responsiveness and fast transcription workflow.
- Preserve secure API key storage in `EncryptedSharedPreferences`.
- Preserve app name `Sensum`.
- Keep onboarding flow explicit and deterministic.
- Keep touch targets at least 48dp.
- Do not use text labels like `SEARCH` on the enter key in actionable contexts.

## 4. Scope Definition
### 4.1 In scope
- Theme token system (light + dark) in Obsidian Ember palette
- Optional dynamic/system color mode toggle
- Keyboard layout hierarchy and icon polish
- Mic feedback polish (subtle ember wave + haptic timing)
- Settings redesign to custom premium layout
- Onboarding premium pass and progression hardening
- Launcher/icon cohesion checks

### 4.2 Out of scope
- Backend/transcription API architecture changes
- New cloud features
- Play Store publishing setup
- Major feature additions unrelated to UI/theming/polish

## 5. Current Baseline Snapshot (As-Of This Contract)
- App name is already `Sensum` in strings.
- Enter icon mapping exists for `Search/Go/Send/Next/Done`.
- Onboarding auto-skip bug was previously fixed but must be regression-tested.
- Status-bar overlap handling exists in onboarding/settings and must be validated on multiple devices.
- Launcher icon assets for light/dark are present in resources.
- Settings currently still uses `PreferenceFragmentCompat` and needs premium redesign.

## 6. Architecture Decisions
### 6.1 Theming mode strategy
- Default mode: Brand locked (Obsidian Ember)
- Optional mode: Use system colors (Android 12+, API 31+)
- Unsupported versions: show toggle disabled with explanatory summary

### 6.2 Settings architecture decision
- Replace stock preference list presentation with custom card-based settings UI.
- Keep existing preference keys and secure persistence behavior.
- If needed for migration safety, keep `preferences.xml` temporarily as fallback only.

### 6.3 Keyboard hierarchy decision
- Hero mic centered
- Spacebar below mic as main text key
- Right-side action rail for enter + backspace
- Left-side utility settings chip/button near mic

## 7. Design Token Contract
Use semantic tokens only. No component-local hardcoded brand hex values in layout files.

### 7.1 Color tokens (dark)
- `oe_bg_0 = #09090A`
- `oe_bg_1 = #111214`
- `oe_surface = #17181B`
- `oe_surface_2 = #1E2024`
- `oe_outline = #2C2F36`
- `oe_text_primary = #F5F7FA`
- `oe_text_secondary = #B6BDC9`
- `oe_text_muted = #8C94A3`
- `oe_accent = #FF7A1A`
- `oe_accent_soft = #FF9A4D`
- `oe_accent_deep = #D85F00`
- `oe_success = #2EB875`
- `oe_warning = #FFB020`
- `oe_error = #FF5A5F`

### 7.2 Color tokens (light)
- `oe_bg_0 = #F7F8FA`
- `oe_bg_1 = #FFFFFF`
- `oe_surface = #FFFFFF`
- `oe_surface_2 = #F0F2F6`
- `oe_outline = #D6DCE7`
- `oe_text_primary = #14161A`
- `oe_text_secondary = #4B5565`
- `oe_text_muted = #6C7483`
- `oe_accent = #E86A0A`
- `oe_accent_soft = #FF8D3A`
- `oe_accent_deep = #C85100`
- `oe_success = #1E9E61`
- `oe_warning = #D98A00`
- `oe_error = #D6454A`

### 7.3 Shape tokens
- `oe_radius_sm = 12dp`
- `oe_radius_md = 18dp`
- `oe_radius_lg = 24dp`
- `oe_radius_xl = 32dp`

### 7.4 Spacing tokens
- `oe_space_1 = 4dp`
- `oe_space_2 = 8dp`
- `oe_space_3 = 12dp`
- `oe_space_4 = 16dp`
- `oe_space_5 = 24dp`
- `oe_space_6 = 32dp`

### 7.5 Motion tokens
- `oe_motion_fast = 90ms`
- `oe_motion_standard = 180ms`
- `oe_motion_screen = 220ms`
- `oe_press_scale = 0.96`

### 7.6 Accent usage rules
- Accent is only for primary action, active state, or focused control.
- Accent must not be used as full-screen background.
- Glow/ember overlays must stay subtle (4% to 12% opacity equivalent).

## 8. File Ownership Map
### 8.1 Theming and resources
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values/colors.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values-night/colors.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values/themes.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values/dimens.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values/strings.xml`

### 8.2 Runtime theme and preferences
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/GroqVoiceApplication.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/utils/SecurePrefs.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ime/VoiceInputMethodService.kt`

### 8.3 Keyboard UI
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/keyboard_view.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ime/KeyboardView.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ime/InputConnectionHelper.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/ic_action_*.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/ic_key_*.xml`

### 8.4 Onboarding UI
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/activity_welcome.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/fragment_welcome.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/fragment_api_key_setup.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/fragment_keyboard_enable.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ui/onboarding/WelcomeActivity.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ui/onboarding/ApiKeySetupFragment.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ui/onboarding/KeyboardEnableFragment.kt`

### 8.5 Settings redesign
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ui/settings/SettingsActivity.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/activity_settings.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/xml/preferences.xml` (migration reference/fallback)
- New files allowed under `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ui/settings/`
- New files allowed under `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/` for settings cards/components

### 8.6 Launcher/icon assets
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/ic_launcher_background.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable-night/ic_launcher_background.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable-nodpi/ic_launcher_bg_image.png`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable-night-nodpi/ic_launcher_bg_image.png`

## 9. Milestones and Gates (Strict Order)

## M0 - Baseline and Guard Rails
### Objective
Lock baseline behavior before UI rewrite.

### Checklist
- [ ] Create working branch: `codex/obsidian-ember-ui-pass`
- [ ] Record `git status` snapshot
- [ ] Build debug once: `./gradlew :app:assembleDebug --no-daemon`
- [ ] Install on connected device (if available): `./gradlew :app:installDebug --no-daemon`
- [ ] Verify manually:
  - [ ] Onboarding step order is 1 -> 2 -> 3
  - [ ] Step 2 API screen does not auto-skip
  - [ ] Step 3 requires explicit tap to continue
  - [ ] Enter key behavior works in at least one browser field and one chat field
- [ ] Capture baseline screenshots (onboarding/settings/keyboard)

### Exit gate
- [ ] Build green
- [ ] Baseline screenshots saved
- [ ] No blocker crashes

## M1 - Tokenization and Theme Foundation
### Objective
Migrate to semantic Obsidian Ember tokens and remove ad hoc palette drift.

### Checklist
- [ ] Add semantic token resources to light/dark colors files
- [ ] Map Material theme attrs (`colorPrimary`, `colorSurface`, etc.) to semantic tokens
- [ ] Update style overlays and component themes to semantic refs only
- [ ] Add/update spacing and radius dimens for consistency
- [ ] Remove direct legacy blue-themed assumptions from UI styling
- [ ] Keep readability contrast at least 4.5:1 for body text

### File targets
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values/colors.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values-night/colors.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values/themes.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values/dimens.xml`

### Exit gate
- [ ] No hardcoded brand hex in layout files (except transparent utility cases)
- [ ] Light and dark screenshots show same brand identity, not two unrelated UIs

## M2 - Theme Mode Toggle (Brand vs System)
### Objective
Support optional dynamic colors without sacrificing brand default.

### Checklist
- [ ] Add `SecurePrefs.KEY_USE_SYSTEM_COLORS`
- [ ] Add methods:
  - [ ] `isSystemColorsEnabled()`
  - [ ] `setSystemColorsEnabled(enabled: Boolean)`
- [ ] Update app startup theme behavior:
  - [ ] Apply dynamic colors only when toggle is true and API >= 31
  - [ ] Brand mode remains default for first launch
- [ ] Update IME context wrapping in `VoiceInputMethodService` to respect toggle
- [ ] Add UI control in settings for `Use system colors`
- [ ] On API < 31, disable toggle and show clear reason

### File targets
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/utils/SecurePrefs.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/GroqVoiceApplication.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ime/VoiceInputMethodService.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ui/settings/SettingsActivity.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values/strings.xml`

### Exit gate
- [ ] Toggle persists across app restarts
- [ ] Brand mode default verified on fresh install
- [ ] System mode applies only on supported Android versions

## M3 - Keyboard Layout V2 (Premium Hierarchy)
### Objective
Restructure keyboard to premium hero layout.

### Layout contract
- Zone A: transcription preview + state label
- Zone B: hero mic centered
- Zone C: utility/action row
  - Left: settings chip/button
  - Center: wide spacebar
  - Right: action rail (enter + backspace)

### Checklist
- [ ] Refactor `keyboard_view.xml` into explicit zones
- [ ] Keep or adapt IDs so business logic remains stable
- [ ] Ensure spacebar is visually dominant among text keys
- [ ] Move settings out of crowded central control cluster
- [ ] Implement right-side rail with consistent button dimensions
- [ ] Ensure all controls remain reachable with one thumb in portrait

### Recommended dimensions
- Keyboard height: 296dp to 320dp range
- Hero mic diameter: 100dp to 116dp
- Spacebar height: 52dp to 56dp
- Rail key width: 56dp to 64dp
- Min touch target: 48dp

### File targets
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/keyboard_view.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values/dimens.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ime/KeyboardView.kt`

### Exit gate
- [ ] No clipping or overlap on 1080x2340 and 1440x3120 class screens
- [ ] Controls appear optically balanced and center-aligned

## M4 - Iconography and Input Semantics
### Objective
Eliminate text-action regressions and enforce coherent icon language.

### Enter key mapping table (mandatory)
- `IME_ACTION_SEARCH` -> `ic_action_search`
- `IME_ACTION_GO` -> `ic_action_go`
- `IME_ACTION_SEND` -> `ic_action_send`
- `IME_ACTION_NEXT` -> `ic_action_next`
- `IME_ACTION_DONE` -> `ic_action_done`
- No action / multiline -> default enter glyph behavior

### Checklist
- [ ] Keep icon-only action keys where context supports action
- [ ] Prevent `S E A R` or text fallback in actionable modes
- [ ] Standardize icon viewport and optical weight
- [ ] Ensure backspace icon is centered and not visually truncated
- [ ] Ensure settings icon weight matches backspace/enter action style
- [ ] Tint icons via semantic colors (`onPrimary`, `onSurface`), not hardcoded colors

### File targets
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ime/VoiceInputMethodService.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/ic_action_search.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/ic_action_go.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/ic_action_send.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/ic_action_next.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/ic_action_done.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/ic_key_backspace.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/ic_key_settings.xml`

### Exit gate
- [ ] Dynamic enter icon always matches host field action
- [ ] Backspace/settings icons pass visual centering check in all themes

## M5 - Mic Ember Motion and Haptic Polish
### Objective
Add subtle premium feedback without toy-like motion.

### Interaction spec
- Tap down on mic: short outward ember pulse
- Tap release: pulse collapses/fades
- Recording active: restrained glow/pulse, no aggressive bounce
- Haptic: one subtle tick on state transition only

### Checklist
- [ ] Add mic pulse layer (drawable or overlay view)
- [ ] Implement tap-down outward pulse (140ms to 180ms)
- [ ] Implement release settle (120ms to 160ms)
- [ ] Keep press scale near `0.96` for keys
- [ ] Ensure idle state has no heavy continuous animation
- [ ] Respect haptic preference toggle

### File targets
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ime/KeyboardView.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ime/VoiceInputMethodService.kt`
- Optional new drawables under `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/`

### Exit gate
- [ ] Motion feels subtle and premium in live typing
- [ ] No animation leak after repeated IME open/close cycles

## M6 - Settings Premium Redesign
### Objective
Replace stock preference appearance with custom premium settings cards.

### Functional parity requirements (must retain)
- API key set/update (secure storage)
- Model selection
- Double-tap-space toggle
- Haptic toggle
- Clear transcription log
- Privacy policy link
- New appearance toggle: `Use system colors`

### UI structure contract
- Header card:
  - Sensum icon
  - Title (`Sensum Settings`)
  - Subtitle (`Voice-first and privacy-focused` or improved premium copy)
- Section cards:
  - API and model
  - Keyboard behavior
  - Appearance
  - Privacy
- Primary actions styled as filled/outlined pills based on importance

### Checklist
- [ ] Create custom settings layout with Material3 card system
- [ ] Replace generic list look and default dividers
- [ ] Implement explicit insets handling (status/nav bars)
- [ ] Rewire each setting to `SecurePrefs` and existing services
- [ ] Preserve accessibility labels and content descriptions
- [ ] Keep transition from onboarding -> settings stable

### File targets
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ui/settings/SettingsActivity.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/activity_settings.xml`
- Additional settings layout files as needed under `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/`
- Keep `/Users/jaybharti/Documents/App/IME/app/src/main/res/xml/preferences.xml` only as migration safety (optional removal after parity)

### Exit gate
- [ ] Settings no longer visually resembles stock `PreferenceFragmentCompat`
- [ ] All previous settings continue to function
- [ ] No status-bar overlap on tested devices

## M7 - Onboarding Premium Pass and Reliability
### Objective
Finalize polished onboarding while preserving explicit progression.

### Onboarding state machine (mandatory)
- Step 1 (`Welcome`) -> only advances via user CTA
- Step 2 (`API key`) -> advances only after successful validation and explicit action
- Step 3 (`Enable keyboard`) -> advances only when user taps continue after enabled
- Completion -> mark onboarding complete and open settings

### Checklist
- [ ] Confirm page changes are only event-driven, never time-driven
- [ ] Keep one-time setup reassurance text visible on API page
- [ ] Keep plain-language API key steps for non-technical users
- [ ] Align cards, typography, spacing with token system
- [ ] Keep progress indicator calm and consistent
- [ ] Ensure edge-to-edge insets are correctly applied

### File targets
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ui/onboarding/WelcomeActivity.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ui/onboarding/ApiKeySetupFragment.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/java/com/groqvoice/keyboard/ui/onboarding/KeyboardEnableFragment.kt`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/activity_welcome.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/fragment_welcome.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/fragment_api_key_setup.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/layout/fragment_keyboard_enable.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/values/strings.xml`

### Exit gate
- [ ] No auto-skip bug reproducible across 3 fresh-install runs
- [ ] Copy is clear for non-technical users and emphasizes one-time setup

## M8 - Icon Cohesion and Launcher Validation
### Objective
Ensure app icon and in-app palette are cohesive across light/dark.

### Checklist
- [ ] Validate adaptive icon references for light/night backgrounds
- [ ] Validate icon crop/safe area in launcher masks (circle, squircle)
- [ ] Ensure in-app accent hue aligns with icon orange family
- [ ] Ensure no unintended blue accent remnants remain

### File targets
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable/ic_launcher_background.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable-night/ic_launcher_background.xml`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable-nodpi/ic_launcher_bg_image.png`
- `/Users/jaybharti/Documents/App/IME/app/src/main/res/drawable-night-nodpi/ic_launcher_bg_image.png`

### Exit gate
- [ ] Launcher icon remains crisp and centered in both modes
- [ ] Brand cohesion confirmed from launcher icon through runtime UI

## M9 - QA Matrix and Release Readiness
### Objective
Close with deterministic validation and no regressions.

### 9.1 Functional matrix
- [ ] Browser URL field: enter key shows search/go icon and triggers expected action
- [ ] Chat app field: enter key shows send icon when supported
- [ ] Multiline note field: enter inserts newline default
- [ ] API key setup stores key once and persists across app relaunch
- [ ] Keyboard enable step does not auto-advance without user action
- [ ] Settings toggles persist after force-close and relaunch

### 9.2 Visual matrix
- [ ] Dark mode: matte black surfaces with orange accent hierarchy
- [ ] Light mode: clean light surfaces with aligned orange accent hierarchy
- [ ] Dynamic mode (if enabled): applies without layout breakage
- [ ] Keyboard, onboarding, settings share coherent component language

### 9.3 Accessibility matrix
- [ ] Body text contrast >= 4.5:1
- [ ] Touch targets >= 48dp
- [ ] Content descriptions present for icon-only controls
- [ ] Motion does not induce discomfort (short, subtle, non-looping)

### 9.4 Performance matrix
- [ ] No frame drops visible during key press interaction
- [ ] IME open/close 20x does not leak animation/state
- [ ] No ANR or crash in onboarding/settings/keyboard flows

### Build and test commands
- [ ] `./gradlew :app:assembleDebug --no-daemon`
- [ ] `./gradlew :app:installDebug --no-daemon`
- [ ] `./gradlew :app:testDebugUnitTest --no-daemon` (if tests exist)
- [ ] Optional: `./gradlew :app:lintDebug --no-daemon` (if configured)

### Release gating rule
- [ ] Do not generate release APK before live device sign-off by user.
- [ ] After live sign-off, generate APK as a separate controlled step.

## 10. UI Micro-Specs (Nitpicky Details)
### 10.1 Keyboard controls
- Enter, backspace, settings controls should be icon-based where possible.
- Control icon size target: 20dp to 22dp.
- Icon optical centering must be verified manually in both themes.
- Spacebar label can remain text, but avoid high visual weight.

### 10.2 Press feedback
- Key press scale: 0.96, return to 1.0 within 90ms to 120ms.
- Use subtle tonal darken/lighten on press, not dramatic color swaps.

### 10.3 Settings placement
- Settings button should not visually compete with mic hero.
- Place as utility control near mic-left zone with reduced prominence.

### 10.4 Edge-to-edge and overlap protection
- Top app bars must consume status-bar insets.
- Main content must consume nav-bar insets.
- No title should overlap notification shade area.

## 11. API Onboarding Copy Contract
Mandatory messaging goals for step 2:
- Explain API key is free.
- Explain setup is one-time.
- Explain where to obtain key (`console.groq.com/keys`).
- Explain non-technical reassurance (users do not repeat this every time).

Do not use dense technical jargon in onboarding copy.

## 12. Regression Traps To Watch
- Reintroducing auto-advance from timers/coroutines in onboarding.
- Reintroducing text labels on enter action key.
- Breaking `SecurePrefs` key names and losing persisted settings.
- Applying dynamic colors unconditionally and breaking brand default.
- Leaving settings in stock preference look after redesign goal.

## 13. Deliverables for Final Handoff
### 13.1 Code deliverables
- Tokenized theme resources and mapped Material attrs
- Theme mode toggle and runtime application logic
- Keyboard V2 layout + icon and motion polish
- Premium settings screen implementation with functional parity
- Onboarding premium pass and progression hardening

### 13.2 Evidence deliverables
- Screenshot set (minimum):
  - Launcher icon (light and dark)
  - Onboarding step 1/2/3
  - Settings header and all cards
  - Keyboard idle
  - Keyboard recording
  - Keyboard in browser (search icon)
  - Keyboard in chat (send icon)
- QA checklist results (pass/fail)
- Final test command outputs summary

## 14. Change Log Template (Use During Implementation)
Use this exact template during execution updates:

```md
### [Milestone ID] - [Date]
- Files changed:
  - /absolute/path/file1
  - /absolute/path/file2
- What was implemented:
  - ...
- Verification run:
  - command: result
- Remaining risks:
  - ...
```

## 15. Defaults for Ambiguity (Do Not Block)
- Prefer calmer motion over more motion.
- Prefer stronger contrast over softer contrast.
- Prefer icon-only action controls over text labels.
- Prefer brand mode fallback when theme mode is uncertain.
- Prefer preserving stable behavior over experimental visuals.
- Prefer minimal UI density over control crowding.

## 16. Definition of Done (Final Acceptance)
All must be true:
- [ ] Obsidian Ember is the clear default identity in dark and light.
- [ ] Optional system color mode exists and is correctly gated by API support.
- [ ] Keyboard layout matches hero mic + centered space + right action rail + left utility.
- [ ] Dynamic enter icon semantics are correct across real app contexts.
- [ ] Settings are custom premium cards, not stock-looking preferences.
- [ ] Onboarding is explicit and stable with no auto-skip regressions.
- [ ] Status-bar overlap is resolved on settings/onboarding headers.
- [ ] Debug build installs and runs on physical device.
- [ ] User live test sign-off is completed before release APK generation.
