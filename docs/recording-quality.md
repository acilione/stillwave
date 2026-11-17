# Recording quality

Voice, Balanced, and Studio capture uncompressed, lossless 16-bit PCM WAV. These are PCM bitrates, not AAC/MP3 bitrates; values are not directly comparable with compressed audio settings.

| Preset | Sample rate | Channels | PCM bitrate | Storage per minute |
| --- | --- | --- | --- | --- |
| Voice | 16 kHz | Mono | 256 kbps | 1.92 MB |
| Balanced | 44.1 kHz | Mono | 705.6 kbps | 5.292 MB |
| Studio | 48 kHz | Stereo | 1536 kbps | 11.52 MB |

Calculated as `sample rate × channels × 16` bits/second, with decimal MB and a negligible 44-byte WAV header. The app writes PCM directly, so no compression encoder runs while recording.

Voice handles the least data and is the economical choice for speech notes. Balanced is the recommended general-purpose preset: it retains more audio bandwidth than Voice while using less than half Studio's storage. Studio is the highest fixed preset, useful when a real stereo input is available. An increased sample rate or channel count cannot by itself improve microphone noise, placement, or the analog recording path. Battery use and acoustic fidelity need measurements; data volume alone does not establish those results.

## Attached phone

Read on 25 October 2026 from the connected phone:

- Android model: `M2103K19G`; market name: **Redmi Note 10 5G**.
- `/vendor/etc/audio_policy_configuration.xml` advertises 16-bit PCM primary input at 8, 16, 32, 44.1, and 48 kHz, with mono and stereo channel masks. The built-in microphone entries advertise these rates too.
- All three app presets therefore match advertised input configurations. This is not an acoustic benchmark and does not prove that two independent microphone channels reach an app when stereo is selected. No claim is made about this phone's native ADC precision or maximum fidelity from the policy file alone.

For this phone, start with Voice for spoken notes or Balanced when sound quality matters more than space. Studio is available when stereo is useful; verify the channel separation with the actual input being used.

Android documents PCM16 support and defines frame size from sample size and channel count in [AudioFormat](https://developer.android.com/reference/android/media/AudioFormat). Android's [AudioRecord documentation](https://developer.android.com/reference/android/media/AudioRecord) guarantees mono and 44.1 kHz compatibility; other formats depend on the device and route. The phone's [official specifications](https://www.mi.com/global/product/redmi-note-10-5g/specs/) list its general audio features but do not establish built-in microphone capture fidelity.


## Best available (version 1.3)

The separate **Best available · Auto** control is available in both app control styles and in the widget. With three buttons or the widget, tapping it starts recording. With the single-button layout, select it and tap Record; that selection is remembered. There is no separate default-quality setting.

1. Start a temporary capture at Android's selected rate and inspect that recording's routed input, rather than selecting the first microphone in a device list.
2. Read the input's audio profiles (Android 12+), or its reported encodings, sample rates, and channel counts on older versions. Also inspect Android's device-side recording format, which can differ from the client format.
3. Rank reported combinations by precision, then sample rate, then channel count. Support is limited to mono/stereo at 8–192 kHz. PCM16 and float capture work on supported Android versions; packed PCM24 and PCM32 capture require Android 12+. Float has 24 bits of significand precision and is ranked accordingly.
4. Open each candidate, start it, and require actual sample frames. Reject failed initialization/reads or an unexpected route. Try at most eight candidates within a three-second selection budget, then reopen the validated baseline. Initial discovery has its own bounded read waits. Unknown capabilities use observed formats without inventing 192 kHz or a higher bit depth. Capability gaps and rejected candidates are recorded in the details.
5. Request Android's UNPROCESSED source only when the device reports support; otherwise use the standard microphone source. No compression encoder runs. A direct byte buffer feeds the WAV writer, preserving sample bytes.
6. Keep the format fixed during a recording. A detected input switch saves the current Best available recording; the user can start another to negotiate the new input. Probe audio is discarded; the first validated buffer of the final capture is retained. The timer starts when capture is ready.

The input label and **Recording format details** dialog show the actual saved format, approximate MB/min, Android-reported device format, active microphone identifiers when exposed, processing request, and fallback notes. The editor retains these details. A device/client mismatch explicitly reports that Android converts between formats. A missing identifier is shown as unavailable.

This is the best *validated reported configuration under the policy above*, not a measurement of physical ADC resolution, noise, frequency response, or independent stereo channels. Android can emulate higher bit-depth formats and resample. Larger numbers alone do not establish better acoustic fidelity. USB/headset/Bluetooth capabilities are inspected on their active route, but external microphones and live route switching require separate hardware verification.

WAV files retain integer PCM16/24/32 or IEEE float32 samples. Float files include a fact chunk; odd packed-24 data has RIFF padding outside the audio. Named, overlapping cuts copy exact frames without down-conversion. Recovery journals and database metadata preserve the encoding and input details. Media3 may convert for playback to match the output device; the source and exported files retain their precision.

Relevant Android APIs: [AudioDeviceInfo](https://developer.android.com/reference/android/media/AudioDeviceInfo), [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord), [AudioRecordingConfiguration](https://developer.android.com/reference/android/media/AudioRecordingConfiguration), and [AudioFormat](https://developer.android.com/reference/android/media/AudioFormat). Android's distinction between client and device formats is why Stillwave displays both.


## Remembered microphone formats (version 1.4)

After a successful Best available check, Stillwave remembers the selected format locally. It first identifies the active route, then validates the remembered format with real sample reads. A remembered format is only reused if it still matches the input’s reported candidates and configuration fingerprint. Failed validation triggers a fresh search.

Identity combines device type, product name, available address, and reported physical microphone names. The stored key is a SHA-256 hash; raw addresses are not saved. Temporary numeric microphone IDs are excluded. Ambiguous inputs and external inputs without an address are checked each time rather than grouped under a misleading identity. Android exposes device information through [AudioDeviceInfo](https://developer.android.com/reference/android/media/AudioDeviceInfo); identities are only as distinct as the information the platform provides.

The configuration fingerprint includes reported audio profiles, sample rates, channel masks, encodings, selected audio source, Android firmware fingerprint, and selection-policy version. A change causes a new quality check. Up to 64 entries are kept in a dedicated local DataStore. These preferences are separate from audio files. Fixed Voice/Balanced/Studio buttons continue to use their selected preset.

**Settings → Remembered microphones → Forget** removes an entry so the next Best available recording runs a fresh check. Recording details indicate when a remembered format was validated.

### Physical phone validation, 26 October 2026

On the attached Redmi Note 10 5G (M2103K19G), Android reported microphone identifier **SPH1642HT5H_REV_B**. Best available validated **48 kHz, PCM16, stereo**, and Android reported the same device-side format. All fixed presets passed capture, pause, resume, and save. Repeated Best available captures reused the stored format, including after force-stopping/restarting the app. WAV files contained non-zero samples; this is a functional capture check, not an acoustic fidelity or independent-stereo benchmark.

## Recording startup (version 1.4.1)

Fixed presets open their chosen format directly; they do not search microphone capabilities. A slow storage preflight was delaying every quality while the UI incorrectly displayed "Checking microphone". Startup now checks `File.usableSpace` against the existing 10 MiB minimum instead of querying reclaimable cache through `StorageManager.getAllocatableBytes`. The capture writer still handles later I/O failures. Under low storage, reclaimable cache is no longer counted as immediately available space. Android documents the difference in [StorageManager](https://developer.android.com/reference/android/os/storage/StorageManager#getAllocatableBytes(java.util.UUID)).

On 29 October 2026, the same Redmi Note 10 5G took 1,808 / 1,296 / 1,301 ms to start Voice / Balanced / Studio before the fix, and 366 / 267 / 238 ms afterward. These are command-to-first-published-audio measurements, including service launch and state publication, from one comparative run; they are not guaranteed latency bounds. The storage stage fell from 1,059–1,414 ms for presets to 2 ms. Remembered Best available took 547 ms after the fix and retains its existing route identification and format validation.

Both app and widget now show "Starting recording" during startup. Debug builds log individual startup stages under `CaptureStartup`. `HardwareRecordingTest` writes a timing report in the app cache and accepts optional `maxPresetStartupMs` / `maxBestStartupMs` instrumentation arguments for device-specific regression budgets.
