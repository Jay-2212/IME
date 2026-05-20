# Sensum Voice Keyboard

**Sensum** is a high-performance, private, and beautifully designed custom Android Input Method Editor (IME) keyboard that enables instant voice-to-text transcription powered by the Groq Cloud API (Whisper Large V3 Turbo). 

Sensum was designed to combine the speed of Groq's low-latency transcription engine with a premium, accessible user interface and rock-solid offline/online state transitions.

---

## 🌟 Key Features

*   **⚡ Ultra-Fast Transcription**: Uses Groq's hardware-accelerated Whisper-Large-V3-Turbo API for near-instant speech-to-text results.
*   **🎙️ Smart Recording Modes**:
    *   **Hands-Free Mode**: Tap to start recording, speak continuously, and tap again to stop and automatically inject the transcribed text.
    *   **Fallback Resilience**: High-fidelity hardware FLAC compression with an automated, safe fallback to WAV format in case of encoder bottlenecks.
*   **🎨 Premium M3 Design**: Balanced layout featuring a center-anchored voice command button, a modern utility bar (Settings, Enter, Backspace, Spacebar), and clean Material Design 3 elements.
*   **🔒 Privacy-First**: 
    *   All API keys are securely encrypted on-device.
    *   Voice data is processed directly via safe HTTPS endpoints, with zero external tracking or data collection.

---

## 📲 How to Install & Use (For Everyone)

No technical knowledge is required to install and use Sensum.

### Step 1: Download the App
Download the pre-compiled application file (APK) directly from the GitHub Releases page:
👉 **[Download Sensum v1.0.0 APK](https://github.com/Jay-2212/IME/releases/download/v1.0.0/sensum-debug.apk)**

### Step 2: Install on Android
1.  Locate the downloaded `.apk` file in your device's Downloads folder.
2.  Tap on the file. If prompted by Android, allow installation from "Unknown Sources" or your web browser.
3.  Click **Install**.

### Step 3: Configure the Keyboard
1.  Open the **Sensum** app from your launcher.
2.  Enter your **Groq API Key** (you can generate one for free on the [Groq Console](https://console.groq.com/keys)).
3.  Follow the on-screen onboarding steps to:
    *   Enable the keyboard in your device's **Languages & Input** settings.
    *   Select **Sensum** as your default keyboard.
4.  Open any chat app, bring up the keyboard, tap the central mic icon, and start talking!

---

## 🛠️ How to Build from Source (For Developers)

If you would like to build and compile the application yourself, follow these steps.

### Prerequisites
*   **Java Development Kit (JDK)**: Version 17
*   **Android SDK**: Command-line tools or Android Studio (Koala or newer recommended)
*   **Gradle**: Configured wrapper included in project

### Building the APK
1. Clone the repository:
   ```bash
   git clone https://github.com/Jay-2212/IME.git
   cd IME
   ```
2. Build the debug version of the app using Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
3. The generated installer will be located at:
   `app/build/outputs/apk/debug/app-debug.apk`

### Installing via USB Debugging
If you have an Android device connected to your computer with USB Debugging enabled:
```bash
./gradlew installDebug
```

---

## ⚙️ Technical Architecture

Sensum is structured as a native Android Service, ensuring deep OS-level integration.

*   **IME Layer**: `VoiceInputMethodService` manages the lifecycle of the system keyboard, handling touch inputs, enter-key style updates based on context (Search, Send, Go), and editor field states (automatically disabling voice typing on password fields for security).
*   **Audio Pipeline**: Captures 16kHz mono PCM audio via `AudioRecord`, streaming it directly into a highly optimized hardware `MediaCodec` FLAC encoder.
*   **Network Stack**: Leverages **Retrofit 2** and **OkHttp 3** for low-overhead HTTP calls, with intelligent network connectivity listeners that handle connection transitions cleanly.
