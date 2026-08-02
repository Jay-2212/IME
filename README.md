# Sensum Voice Keyboard

Sensum is an Android Input Method Editor (IME) with push-to-talk voice typing.
It records 16 kHz mono audio on the device, encodes it as FLAC with a WAV
fallback, sends the recording to the Groq transcription API, and inserts the
returned text into the active input field.

**Status:** the repository currently publishes a v1.0.0 debug APK. It is a
personal project and is not presented as a production-signed or store-approved
keyboard.

## Download

- [Sensum v1.0.0 release](https://github.com/Jay-2212/Sensum/releases/tag/v1.0.0)
- [Download the v1.0.0 debug APK](https://github.com/Jay-2212/Sensum/releases/download/v1.0.0/sensum-debug.apk)

Android may require you to allow installation from the browser or file manager.
After installation, enable Sensum in Android's keyboard settings, select it as
the active keyboard, and add a Groq API key in the app settings.

## Features

- Push-to-talk recording from a custom Android keyboard.
- Hardware `MediaCodec` FLAC encoding with WAV fallback.
- Groq Whisper transcription through Retrofit and OkHttp.
- Material 3 onboarding and settings screens.
- Password-field protection that disables voice typing in password editors.
- Network-state handling, request throttling, retry queue support, and local
  transcription history.
- Encrypted on-device storage for the Groq API key using AndroidX
  `EncryptedSharedPreferences`.
- Light and dark/pastel visual themes.

## Privacy and data flow

Voice audio is sent over HTTPS directly to
`https://api.groq.com/openai/v1/audio/transcriptions`. The app does not
transcribe audio fully offline. Groq's processing, retention, and account terms
apply to those requests; this repository does not guarantee that voice data is
private, deleted immediately, or excluded from provider logging.

The app stores the Groq API key in Android encrypted preferences. Temporary
audio is held in the app's private storage and the source attempts best-effort
cleanup after use. The app also keeps local transcription/audit history until
the user clears it. These local protections do not change what the Groq API can
receive or retain.

As an Android keyboard, Sensum operates in text fields selected by the user.
Review Android's IME warning and the app's permissions before using it for
sensitive conversations or credentials. The source contains no project-wide
claim of “zero external tracking or data collection.”

## Permissions

The manifest requests:

- `RECORD_AUDIO` for recording;
- `INTERNET` and network state for Groq requests;
- `VIBRATE` and `POST_NOTIFICATIONS` for keyboard feedback and notifications;
- `WAKE_LOCK` for hands-free recording; and
- `READ_PHONE_STATE` to stop recording around phone calls.

The IME service also uses Android's protected `BIND_INPUT_METHOD` permission.
Grant only the permissions and keyboard access you understand.

## Build from source

### Requirements

- JDK 17;
- Android SDK with API 35 available; and
- Android Studio or the included Gradle 8.6 wrapper.

The project supports Android API 26 and newer at runtime (`minSdk 26`) and
targets API 35. Java and Kotlin source compatibility is 11; the Android build
itself should be run with the JDK version required by the Android Gradle Plugin.

### Debug build

```bash
git clone https://github.com/Jay-2212/Sensum.git
cd Sensum
./gradlew test
./gradlew assembleDebug
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. Install it on a connected device
with:

```bash
./gradlew installDebug
```

### Release signing

Release signing is optional and reads `keystore.properties` when that file
exists. Start from `keystore.properties.example`; never commit a real keystore,
password, API key, or local path. The checked-in release APK is a debug build,
not evidence of a verified production signing pipeline.

## API key handling

Enter the key in the app's onboarding/settings flow. Do not put a real key in
`.env`, `local.properties`, `keystore.properties`, source, screenshots, or issue
reports. The repository includes examples only; its Gradle build does not
silently turn `.env.example` into a runtime credential.

## Licence status

No root open-source licence was present in the repository at audit time. This
README does not assert a project-wide licence. Before adding a permissive
licence, confirm Jay's ownership of the original source and the included app
artwork/branding, then document third-party dependency and asset terms
separately.

## Contributing

Issues and pull requests are welcome for reproducible Android compatibility,
audio, IME, accessibility, and documentation problems. Redact API keys, audio,
transcription text, device identifiers, and personal logs from reports.
