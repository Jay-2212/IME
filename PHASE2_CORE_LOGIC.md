# Phase 2: Core Logic Implementation Documentation

**Agent:** Kimi  
**Timestamp:** 2026-02-21T00:30:00Z  
**Phase:** Core Logic (TSD Section 4, 5, 6)

---

## Overview

This document describes the implementation of Phase 2 — Core Logic for the GroqVoice Keyboard. This phase focuses on state machine refinement, audio pre-buffering, system interruption handling, and memory management.

---

## Files Created

### 1. CircularByteBuffer.kt
**Path:** `app/src/main/java/com/groqvoice/keyboard/audio/CircularByteBuffer.kt`

#### Purpose
A thread-safe circular byte buffer designed for real-time audio pre-buffering in hands-free mode. Implements the 300ms lookback requirement from TSD Section 4.3.

#### Key Features
- **Circular Structure**: Overwrites old data continuously without reallocation
- **Thread Safety**: Uses `ReentrantReadWriteLock` for efficient concurrent access
- **Security**: Zero-fills buffers on `clear()` and `close()` per TSD 7.1
- **Configurable Capacity**: Default 19,200 bytes (300ms at 16kHz/16-bit PCM with 2× safety margin)

#### Memory Calculation
```
300ms pre-buffer = 0.3s × 16,000 samples/s × 2 bytes/sample × 2× safety = 19,200 bytes
```

#### API Reference

| Method | Description |
|--------|-------------|
| `write(data: ByteArray)` | Writes bytes to buffer, overwriting old data if full |
| `readAll(): ByteArray` | Returns all valid bytes in chronological order |
| `drainTo(destination: ByteArray?): Int` | Moves data to output and clears buffer |
| `clear()` | Zero-fills and resets buffer state |
| `close()` | Permanently closes buffer (security) |

#### Thread Safety Model
- **Write Lock**: Acquired for `write()`, `clear()`, `drainTo()`, `close()`
- **Read Lock**: Acquired for `readAll()`, `size`, `isEmpty`
- Multiple concurrent readers allowed; writes are exclusive

---

### 2. CircularByteBufferTest.kt
**Path:** `app/src/test/java/com/groqvoice/keyboard/audio/CircularByteBufferTest.kt`

#### Test Coverage
- Initial state validation
- Capacity clamping (MIN/MAX bounds)
- Sequential write/read operations
- Circular wrapping behavior
- Overflow handling (data > capacity)
- Empty buffer edge cases
- `drainTo()` semantics
- Clear and close operations
- Security (data not exposed in `toString()`)
- Boundary conditions (exact capacity, capacity+1)

---

## Files Modified

### 1. AudioRecordingManager.kt
**Path:** `app/src/main/java/com/groqvoice/keyboard/audio/AudioRecordingManager.kt`

#### Changes Made

##### A. Context-Aware Constructor
Added `Context` parameter to constructor for system service access:
```kotlin
class AudioRecordingManager(
    private val context: Context,
    private val fileCacheManager: FileCacheManager,
    private val vad: VoiceActivityDetector = VoiceActivityDetector()
)
```

##### B. Circular Pre-Buffering for Hands-Free Mode
- Added `preBuffer: CircularByteBuffer?` field
- Added `isPreBuffering: AtomicBoolean` flag
- `startPreBuffering()`: Initializes and starts pre-buffer coroutine
- `doPreBuffering()`: Continuously captures audio to circular buffer
- `drainPreBuffer()`: Transfers pre-buffered audio to main PCM buffer on recording start
- `stopPreBuffering()`: Stops pre-buffer coroutine

**Pre-buffering Flow:**
1. When hands-free mode is selected, pre-buffering starts immediately
2. Audio is continuously written to the circular buffer (300ms window)
3. When user taps mic to start recording, pre-buffer is drained first
4. This captures speech that occurred before the tap

##### C. Wake Lock Management (TSD 6.3)
```kotlin
private fun acquireWakeLock()
private fun releaseWakeLock()
```

- Uses `PowerManager.PARTIAL_WAKE_LOCK` (CPU only, no screen wake)
- 5 minute 30 second timeout as safety net
- Acquired on hands-free recording start
- Released on recording stop/interruption

##### D. Phone Call Interruption (TSD 5.2)
```kotlin
private val phoneStateListener: PhoneStateListener
private fun registerInterruptionListeners()
private fun unregisterInterruptionListeners()
```

- Listens for `CALL_STATE_OFFHOOK` and `CALL_STATE_RINGING`
- Auto-stops recording immediately when call detected
- Discards buffers for privacy
- Notifies via `onInterrupted` callback

##### E. Bluetooth Audio Routing (TSD 5.2)
```kotlin
private var audioRoutingReceiver: BroadcastReceiver
```

- Registers for `ACTION_SCO_AUDIO_STATE_UPDATED`
- Registers for `ACTION_HEADSET_PLUG`
- Auto-stops recording when audio routing changes
- Prevents corrupted recordings across device switches

##### F. Interruption Handling API
```kotlin
enum class InterruptionReason { PHONE_CALL, BLUETOOTH_ROUTING_CHANGE, TRIM_MEMORY }
fun stopForInterruption(reason: InterruptionReason)
var onInterrupted: ((InterruptionReason) -> Unit)?
```

##### G. onTrimMemory Support
```kotlin
fun onTrimMemory(level: Int)
```

- `TRIM_MEMORY_RUNNING_CRITICAL`: Stops recording, clears all buffers
- `TRIM_MEMORY_RUNNING_LOW`: Clears pre-buffer only
- `TRIM_MEMORY_RUNNING_MODERATE`: Clears pre-buffer only

##### H. Security Enhancements
- PCM data zero-filled after encoding in `finishRecording()`
- Pre-buffer cleared on recording stop
- All buffers zero-filled before release

---

### 2. VoiceInputMethodService.kt
**Path:** `app/src/main/java/com/groqvoice/keyboard/ime/VoiceInputMethodService.kt`

#### Changes Made

##### A. onTrimMemory Implementation (TSD 5.3)
```kotlin
override fun onTrimMemory(level: Int)
```

Handles three severity levels:
- **CRITICAL/COMPLETE**: Stops recording, clears animations, releases resources
- **LOW**: Propagates to AudioRecordingManager for buffer clearing
- **MODERATE/UI_HIDDEN**: Clears pre-buffer only

##### B. Backspace Long-Press Completion (TSD 5.4)
```kotlin
private fun wireBackspaceButton(view: KeyboardView)
private fun stopBackspaceRepeat()
private var isBackspaceRepeating: Boolean
```

**Touch Lifecycle:**
1. `ACTION_DOWN`: Normal click registered
2. `LONG_PRESS` (500ms): `onBackspaceLongClick` triggers repeat start
3. `ACTION_UP`/`ACTION_CANCEL`: `onBackspaceTouchUp` stops repeat

**Repeat Logic:**
- Initial hold trigger: 500ms
- Repeat interval: 100ms
- Cancellation on touch up ensures no runaway deletion

##### C. Audio Routing Receiver
```kotlin
private fun registerAudioRoutingReceiver()
private fun unregisterAudioRoutingReceiver()
```

- Service-level receiver for SCO audio state changes
- Complements AudioRecordingManager's internal receiver
- Handles UI updates when Bluetooth disconnects

##### D. Interruption Callback Handling
```kotlin
audioManager.onInterrupted = { reason ->
    // Shows appropriate error banner
    // Clears composing text
    // Auto-resets to Idle after 3 seconds
}
```

##### E. Constructor Update
Updated to pass `Context` to `AudioRecordingManager`:
```kotlin
audioManager = AudioRecordingManager(this, fileCacheManager)
```

---

### 3. KeyboardView.kt
**Path:** `app/src/main/java/com/groqvoice/keyboard/ime/KeyboardView.kt`

#### Changes Made

##### A. Backspace Touch-Up Callback
```kotlin
var onBackspaceTouchUp: (() -> Unit)? = null
```

Added to enable proper cancellation of long-press repeat:
```kotlin
btnBackspace.setOnTouchListener { _, event ->
    when (event.action) {
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL -> {
            onBackspaceTouchUp?.invoke()
        }
    }
    false // Allow long-click detection
}
```

##### B. Enhanced Documentation
- Added complete class-level KDoc
- Added state mapping table for `applyState()`
- Documented layout structure
- Clarified button state transitions

---

### 4. AndroidManifest.xml
**Path:** `app/src/main/AndroidManifest.xml`

#### Changes Made
Added permission for phone state monitoring:
```xml
<!-- Phone state for auto-stopping recording during calls (TSD 5.2) -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

---

### 5. strings.xml
**Path:** `app/src/main/res/values/strings.xml`

#### Changes Made
Added interruption error messages:
```xml
<string name="error_recording_interrupted_call">Recording stopped — phone call detected</string>
<string name="error_recording_interrupted_bluetooth">Recording stopped — Bluetooth audio changed</string>
<string name="error_recording_interrupted_memory">Recording stopped — low memory</string>
```

---

## Architecture Decisions

### 1. Pre-Buffering Design
**Decision**: Separate pre-buffer coroutine with `CircularByteBuffer`

**Rationale**:
- Pre-buffering must run independently of recording state
- Captures audio before user taps mic (essential for hands-free UX)
- Circular buffer naturally handles overflow without allocation

**Trade-offs**:
- + Seamless hands-free experience
- + No speech clipping at start
- - Slightly higher battery usage when keyboard visible
- - Additional memory (~19KB)

### 2. Wake Lock Strategy
**Decision**: `PARTIAL_WAKE_LOCK` only, acquired only during hands-free recording

**Rationale**:
- Screen wake would be intrusive during voice typing
- CPU must stay active to process audio during Doze
- Not needed for push-to-talk (user holding phone)

### 3. Interruption Handling
**Decision**: Stop-and-discard policy for all interruptions

**Rationale**:
- **Phone calls**: Privacy priority — user doesn't want voice data mixed with call audio
- **Bluetooth changes**: Audio routing switch would corrupt recording
- **Low memory**: System health priority over recording completion

### 4. Threading Model
**Decision**: `Dispatchers.IO` for all audio operations, `Dispatchers.Main` for callbacks

**Rationale**:
- AudioRecord.read() blocks; must not be on main thread
- StateFlow updates are thread-safe
- UI callbacks must post to main thread

---

## Edge Cases Handled

### 1. Rapid State Changes
- Debounce (300ms) prevents state machine corruption
- AtomicBoolean flags prevent race conditions in pre-buffering

### 2. Buffer Overflow
- CircularByteBuffer naturally handles overflow
- Main PCM buffer protected by max duration (5 minutes)

### 3. Interruption During Encoding
- `stopForInterruption()` discards buffers before encoding
- Zero-fill ensures no data leakage

### 4. Bluetooth Disconnection Mid-Recording
- Audio routing receiver triggers immediate stop
- User sees "Bluetooth audio changed" message

### 5. Incoming Call During Hands-Free
- PhoneStateListener detects CALL_STATE_RINGING
- Recording stops before user answers

### 6. Low Memory During Recording
- `onTrimMemory` propagates through component hierarchy
- Critical level stops recording gracefully
- Lower levels clear non-essential buffers

### 7. Wake Lock Leak
- Timeout (5m 30s) ensures release even if callback fails
- `release()` in service `onDestroy()` as final safeguard

---

## Testing Strategy

### Unit Tests
- `CircularByteBufferTest.kt`: 20+ test cases covering all buffer operations

### Integration Tests (Recommended)
1. Start hands-free recording → verify wake lock acquired
2. Simulate phone call → verify recording stops, error shown
3. Simulate Bluetooth disconnect → verify recording stops
4. Low memory simulation → verify buffers cleared
5. Long-press backspace → verify repeat behavior, cancellation on up

### Manual Tests
- Enable keyboard, start hands-free, receive call → verify stop
- Record with Bluetooth headset, disconnect → verify stop
- Long text input, long-press backspace → verify smooth deletion
- Background app during recording → verify behavior

---

## Performance Considerations

### Memory Usage
| Component | Memory |
|-----------|--------|
| CircularByteBuffer | ~19 KB |
| PCM buffer (max 5min) | ~9.6 MB |
| AudioRecord buffer | ~64 KB |
| Total peak | ~9.7 MB |

### Battery Impact
- Pre-buffering: Low (small audio chunks, efficient ring buffer)
- Wake lock: Only during hands-free recording
- Phone state listener: Passive (system callback)

### CPU Usage
- VAD RMS computation: O(n) per chunk (negligible)
- Buffer copies: Minimized via System.arraycopy

---

## Security Notes

### Data Handling
- All PCM buffers zero-filled before release
- Pre-buffer cleared on interruption
- No audio data logged or exposed in toString()

### Permissions
- `READ_PHONE_STATE`: Only for call detection, no call content accessed
- `WAKE_LOCK`: Partial only, no screen wake

---

## Unresolved / Next Steps

### For Phase 3 (CodeX)
1. **FLAC Encoding**: Currently WAV-only; add FLAC for 50% size reduction
2. **WorkManager Retry Queue**: Network-unavailable scenario per TSD 5.1
3. **Verbose JSON Parsing**: Implement `x_groq` metadata extraction
4. **Rate Limiting**: Client-side throttling (20 req/min)

### For Phase 4 (Gemini)
1. **Mic Button Animations**: Pulse, scale, gradient border, shake, pop effects
2. **Certificate Pinning**: Implement in network_security_config.xml
3. **Accessibility**: Add ContentDescription labels
4. **Transcription Audit Log**: Timestamp logging per TSD 7.2

---

## Handoff Notes for Next Agent

### Architecture Awareness
1. **State Machine**: All recording state changes flow through `AudioRecordingManager.state` StateFlow
2. **Interruption Handling**: Uses callback pattern (`onInterrupted`) → service posts to main thread
3. **Pre-buffering**: Independent coroutine; ensure stopped before release to avoid leaks

### Dependencies Added
- `android.permission.READ_PHONE_STATE` (manifest)
- PowerManager wake lock (runtime)
- TelephonyManager listener (runtime)
- AudioManager broadcast receiver (runtime)

### Potential Issues to Watch
1. **Context Leak**: AudioRecordingManager holds context reference; ensure `release()` called
2. **Wake Lock Timeout**: 5m30s may be too long/short for some use cases
3. **Bluetooth Race**: Multiple receivers (service + manager) may duplicate handling

### API Changes
- `AudioRecordingManager` now requires `Context` in constructor
- New `onInterrupted` callback in `AudioRecordingManager`
- New `onBackspaceTouchUp` callback in `KeyboardView`

---

## Verification Checklist

- [x] CircularByteBuffer implemented with thread safety
- [x] Unit tests for CircularByteBuffer (20+ cases)
- [x] TelephonyManager phone call interruption
- [x] Bluetooth audio routing change handling
- [x] WAKE_LOCK acquisition/release for hands-free
- [x] onTrimMemory handling in service
- [x] Long-press backspace repeat with cancellation
- [x] READ_PHONE_STATE permission added
- [x] Error strings for interruptions added
- [x] Comprehensive documentation created
