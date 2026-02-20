# GroqVoice Keyboard — Development Logbook

---

## Entry 1

**Timestamp:** 2026-02-20T18:30:00Z

**Current Phase:** Skeleton / Setup (Phase 1 of 5 — TSD Section 9.1)

**Completed:**

### Root Configuration
- `settings.gradle.kts` — root Gradle settings, single `:app` module
- `build.gradle.kts` — top-level build file, plugin declarations
- `gradle.properties` — JVM args, AndroidX flags, Kotlin code style
- `gradle/libs.versions.toml` — centralized version catalog (AGP 8.2.2, Kotlin 1.9.22, all deps)
- `gradle/wrapper/gradle-wrapper.properties` — Gradle 8.6
- `.gitignore` — excludes `.gradle/`, `build/`, keystores, `.env`
- `.env.example` — documents GROQ_API_KEY, GROQ_API_BASE_URL, GROQ_DEFAULT_MODEL

### App Build
- `app/build.gradle.kts` — compileSdk 34, minSdk 26, viewBinding, kapt, all dependency wiring
- `app/proguard-rules.pro` — strips Log.* in release, keeps Moshi/Retrofit/security classes

### Android Manifest & XML Resources
- `app/src/main/AndroidManifest.xml` — IME service declaration, onboarding/settings activities, all permissions (RECORD_AUDIO, INTERNET, VIBRATE, POST_NOTIFICATIONS, WAKE_LOCK)
- `res/xml/method.xml` — IME input method declaration with `en_US` subtype
- `res/xml/network_security_config.xml` — blocks cleartext; cert-pinning placeholder for api.groq.com
- `res/xml/preferences.xml` — settings PreferenceScreen (API key, model, double-tap period, haptic, privacy)

### Value Resources
- `res/values/colors.xml` — full design system palette (TSD 3.1): keyboard_background, accent_primary, accent_secondary, text_primary, disabled, gradient, error, success, warning
- `res/values/strings.xml` — all UI strings: onboarding (3 steps), keyboard states, banners, tooltips, settings, error messages, model arrays
- `res/values/dimens.xml` — all dimensions from spec: keyboard_height 280dp, mic_button 120dp, icon 72dp, corner_radius 24dp, bottom row widths
- `res/values/themes.xml` — base app theme, onboarding theme, settings theme, shape appearance, mic button style, primary button style

### Layout Files
- `res/layout/keyboard_view.xml` — full keyboard: transcription preview strip (48dp), state label, mic FAB (120dp), backspace (60dp) + settings gear + spacebar (200dp), contextual banner
- `res/layout/activity_welcome.xml` — ViewPager2 + dot indicator container
- `res/layout/fragment_welcome.xml` — logo, headline, subtitle, "Get Started" button
- `res/layout/fragment_api_key_setup.xml` — password TextInputLayout, help link, validation status, "Validate & Continue" button
- `res/layout/fragment_keyboard_enable.xml` — Lottie animation placeholder, status label, test EditText, "Open Keyboard Settings" button
- `res/layout/activity_settings.xml` — CoordinatorLayout with toolbar + FrameLayout for PreferenceFragment

### Drawable & Raw Assets
- `res/drawable/ic_back.xml` — vector back arrow for toolbar
- `res/raw/keyboard_setup_animation.json` — placeholder Lottie JSON (replace with real animation in Phase 4)

### Kotlin Source Files

#### Application
- `GroqVoiceApplication.kt` — calls `FileCacheManager.cleanOrphanedFiles()` on startup

#### Model Layer (`model/`)
- `RecordingState.kt` — sealed class (Idle, Recording, Processing, Error) + RecordingMode enum
- `TranscriptionResult.kt` — TranscriptionResponse, GroqMetadata, UsageStats (Moshi), TranscriptionResult sealed class

#### Utils Layer (`utils/`)
- `SecurePrefs.kt` — EncryptedSharedPreferences wrapper (AES256-GCM); stores API key, model, onboarding status
- `PermissionManager.kt` — centralised runtime permission requests for RECORD_AUDIO + POST_NOTIFICATIONS (API 33+)
- `FileCacheManager.kt` — creates/deletes/zero-wipes temp WAV/FLAC files; cleans orphaned files >15min old

#### Audio Layer (`audio/`)
- `AudioRecordingManager.kt` — state machine (StateFlow), AudioRecord config (16kHz/MONO/PCM_16BIT), debounce, max-duration guard, PCM zero-wipe on release
- `AudioEncoder.kt` — `writePcmToWav()` + `buildWavHeader()` with correct 44-byte WAV structure
- `VoiceActivityDetector.kt` — RMS-based amplitude detection, 500ms silence accumulator, reset()

#### API Layer (`api/`)
- `ApiKeyInterceptor.kt` — OkHttp Interceptor that injects `Authorization: Bearer` header from lazy key provider
- `GroqApiService.kt` — Retrofit interface: `transcribeAudio()` (multipart) + `listModels()` (validation)
- `GroqRepository.kt` — Retrofit/OkHttp wiring (connection pool 5/5min), exponential backoff (2/4/8s), full HTTP error mapping, post-call file cleanup

#### IME Layer (`ime/`)
- `InputConnectionHelper.kt` — commitTranscription, composing text lifecycle, UTF-16-aware backspace, space insertion, double-tap period, isPasswordField, isIncognitoMode
- `KeyboardView.kt` — FrameLayout wrapping keyboard_view.xml; exposes listener hooks; applyState() for all 5 visual states
- `VoiceInputMethodService.kt` — full IME lifecycle, 800ms tap/hold threshold, haptic feedback, state observation via coroutines, error → onboarding redirect

#### UI Layer (`ui/`)
- `onboarding/WelcomeActivity.kt` — ViewPager2 host, skip-if-complete logic, dot indicator
- `onboarding/OnboardingPagerAdapter.kt` — FragmentStateAdapter for 3 steps
- `onboarding/WelcomeFragment.kt` — Step 1: permission request, advances on grant
- `onboarding/ApiKeySetupFragment.kt` — Step 2: regex + live API validation, EncryptedSharedPreferences storage
- `onboarding/KeyboardEnableFragment.kt` — Step 3: polls InputMethodManager, auto-advances on detection
- `ui/settings/SettingsActivity.kt` — toolbar + SettingsFragment (PreferenceFragmentCompat), bridges API key and model to SecurePrefs

### Test Files
- `test/.../audio/AudioEncoderTest.kt` — 6 unit tests: RIFF/WAVE markers, chunk size, sample rate, header size, full WAV write
- `test/.../audio/VoiceActivityDetectorTest.kt` — 4 unit tests: RMS computation, loud/silent detection, reset
- `test/.../model/RecordingStateTest.kt` — 7 unit tests: all state equality, mode enum coverage, when expression completeness
- `test/.../ime/InputConnectionHelperTest.kt` — 8 unit tests: commit, surrogate-pair delete, double-tap space, password field, incognito mode
- `androidTest/.../ui/OnboardingFlowTest.kt` — 2 instrumented smoke tests: pager visibility

---

**Architect's Thoughts:**

- **MVVM + Repository** pattern enforced: `GroqRepository` owns network, `AudioRecordingManager` owns audio hardware. The IME service only orchestrates, it never talks to OkHttp directly.
- **StateFlow** chosen over LiveData in `AudioRecordingManager` because the IME Service is not an `Activity`/`Fragment` and cannot use `lifecycle-aware` observers natively — `StateFlow` works cleanly with coroutine scopes.
- **EncryptedSharedPreferences** isolated in `SecurePrefs` to prevent accidental plain-text writes anywhere else in the codebase.
- **`object AudioEncoder`** chosen (singleton) because it is stateless; all WAV encoding state is passed via parameters, making it trivially unit-testable without mocking.
- **Lottie** included for Step 3 animation; a minimal placeholder JSON is provided so the layout inflates without crashing — replace with a real Lottie export in Phase 4.
- **ProGuard** configured to strip ALL `Log.*` calls in release builds (TSD 7.1 security requirement). Retrofit body logging is also DISABLED (headers only in debug) to prevent API key leakage.

---

**Remaining Tasks (for subsequent phases):**

### Phase 2 — Core Logic (Kimi 2.5)
- [ ] Implement `CircularByteBuffer` for hands-free pre-buffering (300ms lookback, TSD 4.3)
- [ ] Wire `TelephonyManager` phone-call interruption handler (TSD 5.2)
- [ ] Implement `WAKE_LOCK` acquisition/release in `AudioRecordingManager` for hands-free mode
- [ ] `onTrimMemory` in `VoiceInputMethodService`: clear audio buffer, stop animations
- [ ] Bluetooth audio routing change handler (TSD 5.2)
- [ ] Long-press backspace repeat (wire `backspaceRepeatRunnable` fully in `KeyboardView`)

### Phase 3 — API Integration (CodeX)
- [ ] ~~Wire `GET /models` correctly~~ — fixed in skeleton (was POST, corrected to GET in `GroqApiService`)
- [ ] Implement WorkManager retry queue for network-unavailable scenario (TSD 5.1, 6.3)
- [ ] Add FLAC encoding support via native library (TSD 6.2; AudioEncoder currently WAV-only)
- [ ] Implement `response_format=verbose_json` parsing for `x_groq` metadata
- [ ] Client-side request throttling (20 req/min limit, TSD Appendix A)
- [ ] Partial transcription detection (200 + warning field)

### Phase 4 — Security & Polish (Gemini)
- [ ] Replace placeholder Lottie animation (`keyboard_setup_animation.json`) with real asset
- [ ] Implement certificate pinning for `api.groq.com` (TSD 7.1; placeholder in `network_security_config.xml`)
- [ ] Memory-wipe API key on app backgrounding (optional enhancement, TSD 2.1 Step 2)
- [ ] Add `ContentDescription` accessibility labels for all interactive elements
- [ ] Mic button animations: pulse (idle), scale 1.1x (recording), rotating gradient border (hands-free), shake (error), pop (commit)
- [ ] `WelcomeActivity.updateDotIndicator()` — replace placeholder dot drawables with proper dot shapes
- [ ] Transcription timestamp audit log (TSD 7.2 — no content, timestamps only)
- [ ] Double-tap period preference wired to `SecurePrefs` in `VoiceInputMethodService.handleSpaceKey()`
- [ ] Google Play Store assets: icon set (mipmap-* all densities), feature graphic, screenshots

### Phase 5 — Testing (All)
- [ ] Espresso test: complete full 3-step onboarding flow
- [ ] Espresso test: mock 401 response → verify redirect to onboarding
- [ ] Unit test: `AudioRecordingManager` state transitions (requires Robolectric or mock `AudioRecord`)
- [ ] Unit test: `GroqRepository` retry/backoff logic (mock OkHttp server)
- [ ] Manual testing matrix (TSD 8.3): API 26–34, dark/light mode, split-screen, foldables, password fields
