# Stillwave

A native Android audio recorder and non-destructive editor, built with Kotlin, Jetpack Compose and Material 3. Runs on Android 8.0 (API 26) and newer. No account, ads, tracking or internet access.

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/cili1)

## Install

Download the latest APK from [Releases](https://github.com/acilione/stillwave/releases). An F-Droid listing has been requested. F-Droid will publish the same developer-signed APK, so both sources can update the same installation.

Release signing certificate (SHA-256): `9d:f4:49:30:c6:96:e8:a5:74:77:d2:5b:75:b9:dd:c6:e1:de:67:2f:f1:98:40:b8:40:fa:8e:63:8d:1c:fb:aa`

## Features

- Foreground microphone recording with notification controls, pause/resume, stop/save, and stop/discard. Discard confirmation is enabled by default and configurable.
- One-tap checkpoints during recording or pauses, available in the app, notification, and home-screen widget. Positions use recorded audio time, excluding pauses; checkpoints are saved with the audio and recovered after interruptions.
- Three configurable recording buttons in the app and home-screen widget, initially Voice, Balanced, and Studio. Settings → Recording button qualities assigns each slot from WAV formats or AAC sample-rate/bitrate combinations. Buttons show Hz and kbps and start immediately. Settings → Recording controls also offers one Record button with a quality selector.
- A resizable home-screen widget with direct preset buttons, Pause/Resume, Add checkpoint, Stop & save, and Stop & discard. Discard confirmation appears within the widget and follows the same setting as the app.
- Best available mode in the app and widget detects the active input, checks Android-reported capabilities, validates sample reads, and saves the selected WAV format. Microphone identity, saved format, device-reported format, and fallback details remain available in the editor. Higher-precision PCM24, PCM32, and float WAV are preserved through cuts and recovery. See [selection policy and limits](docs/recording-quality.md).
- Lossless WAV presets include different sample rates, mono/stereo, and PCM precision. Compressed AAC-LC recordings use M4A files with separately selected Hz and kbps. Unsupported device configurations produce an error rather than silently changing capture quality.
- Best available remembers each identifiable microphone’s validated format across app restarts, revalidates it before use, and checks formats again when capabilities/firmware change or the saved choice fails. Settings → Remembered microphones lets you forget an entry.
- Delete recordings from the long-press/overflow menu, editor, or multi-selection toolbar. Confirmation is always required. Deletion removes local audio and its checkpoints/cut windows; already saved cuts remain. Interrupted deletion resumes on the next launch without recovering the deleted audio.
- Titles default to `dd/MM/yy HH:mm:ss` in the device time zone. UUID filenames prevent collisions and keep slashes out of filesystem paths.
- Room stores immutable creation timestamps and measured audio durations independently of editable titles. Pinning, newest/oldest/longest/shortest ordering, inclusive local-date filtering, search, and list/grid/compact layouts.
- Folders with creation, renaming, individual/bulk moves, and All recordings/Unfiled views. New in-app recordings use the folder open when recording starts; saved cuts inherit their source folder. Removing a folder keeps its audio in Unfiled.
- Long-press actions: pin, share, rename, edit, multi-select, and delete. Settings can make a long press enter multi-selection immediately instead of opening the menu; the three-dot button always opens the menu. Selection works in every layout and supports creating a multitrack project from separate recordings.
- Internal cuts remove the checked windows and join the remaining audio into a new editable WAV. Overlapping removal windows are merged; original recordings remain intact.
- Persistent multitrack and loop projects with up to 16 tracks and 128 clips. View source waveforms, split and trim clips, drag them along the zoomable timeline, duplicate them or move them between tracks, and undo the last clip edit. Choose the microphone inside the project, add recordings or record new takes, and save a separate stereo WAV. Original files remain intact. See [multitrack and loop guide](docs/multitrack-loops.md) for editing, timing, and preparation limits.
- Optional playback while recording, disabled by default. When enabled, playback and microphone capture can run together, producing a separate recording rather than digitally copying the backing audio. Choose Automatic or an available recording microphone in Settings; playback keeps Android's selected output route.
- Media3 playback with direct loop buttons beside Play (hideable in Settings) and in recording menus, a seekable sampled waveform, named checkpoints, and independent audio windows with editable names and millisecond timestamps. Each window supports reverse, speed from 0.25× to 4×, optional pitch preservation, single or looped preview, and separate export. Selected windows can also be applied to a full copy of the recording.
- Playback continues in the background and with the screen locked, using Android media controls for play/pause, seeking, and 10-second skips. Settings can instead pause playback when the screen turns off or locks; unlocking does not resume it automatically.
- File details from the recording menu or a single selected recording show the actual WAV/AAC format and on-disk size in decimal MB and exact bytes. The library expands to full screen, retains its scroll position, and brings newly saved recordings into view.
- Every export creates a separate recording. Original files are opened read-only. Unchanged and reversed WAV sections at 1× preserve exact PCM frames, including stereo channel order. Speed changes process samples; AAC sources are decoded for editing. Edited copies are saved as WAV.
- Light and night themes: Settings → Appearance offers System, Light, or Night, and the library header has a one-tap switch.
- Persistent editing metadata, settings, and background exports. The app has no internet permission; recordings leave the device only when you share or export them.
- An atomic pending-recording journal repairs WAV headers and recovers audio, folder membership, and checkpoints after process interruption on the next launch. Room migrations from versions 1–4 to version 5 preserve existing recordings and edits.

## Recording qualities

Open **Settings → Recording button qualities** and choose button 1, 2, or 3. The assignments apply to the app's three buttons, its optional single-button quality selector, and the home-screen widget. There is no separate default-quality setting. In the single-button layout, the selected slot is remembered; changing that slot's assignment changes what it records. **Best available** remains a separate automatic choice.

Open **Recording quality guide** in Settings, or **Guide** inside a quality picker, for plain-language explanations of Hz, kbps, WAV/AAC, mono/stereo, bit depth, and how they affect each other. The guide includes voice/music starting points and approximate storage examples. It works offline and returns to the picker without discarding unsaved choices. A readable copy is in [the quick guide](docs/quality-quick-guide.md).

WAV bitrate is calculated as sample rate × channel count × bit depth; the picker displays Hz, kbps, and estimated storage. Higher requested rates or precision do not establish better microphone fidelity. Unsupported configurations report an error. **Best available** remains a separate automatic WAV option.

AAC offers separate sample-rate, channel-count, and bitrate choices. Capture uses a recoverable temporary PCM file, then encodes an M4A file when saved. Saving AAC takes additional time and temporarily needs space for both files. If encoding fails, the captured WAV is kept and the app reports the fallback. Interrupted capture is recovered as WAV on the next launch.

## Window editing

Open a recording and add a window at the playhead, or choose **Edit whole audio** to create a window spanning the entire recording. Set its name, From/To timestamps, **Reverse**, and **Speed**. Enable **Keep original pitch** for time stretching; otherwise speed changes pitch as well. **Reset effects** restores forward playback at 1×.

Each window's **Preview** plays its current edits. Transformed previews prepare off the UI thread and can be cancelled; changing an effect or range invalidates the earlier preview. Window boundaries always refer to the original recording, even when another window changes duration.

**Save cut** exports one window; **Save all** exports every checked window separately, using their individual names. **Save full audio** asks for a name and replaces the checked sections in a complete copy, preserving the audio between them. Overlapping windows are allowed for separate clips. For a full copy, uncheck or adjust overlaps so each source interval has one transformation. All exports are new WAV files in the source recording's folder; the original remains intact.

**Remove selected sections** treats the checked windows as material to delete. It asks for a name, merges overlapping selections, and joins the audio before, between, and after them into one new WAV. It does not apply window reverse/speed effects. A selection covering the entire source is rejected because no audio would remain. Open the resulting recording to continue editing it; the original and its windows remain available.

## Multitrack and loop station

Open **Multitrack and loop station** from the library header or layout menu. Create a project and choose **Add recordings**, or select several library recordings and choose **Create multitrack from selection**. Each source remains an independent track with its own range, position, volume, mute, solo, and repeat controls. Changes save automatically.

Use the white **Cut position** slider or tap a waveform to place the cut cursor. The exact-time field and **Split** use that same cursor. The separate playback slider seeks the project playhead.

**Play** starts a live preview: tracks are mixed in real time straight from their sources, with no render wait, and volume, mute, solo, moves, trims and splits are heard while it plays. The button beside it plays from the cut cursor. The live preview pauses when you leave the app and limits peaks instead of normalizing them. **Background player** renders the mix first and plays it with lock-screen and notification controls. **Save mix to library** creates an independent 48,000 Hz, 16-bit stereo WAV using a snapshot of the project, with foreground progress and cancellation. Original files and project edits are retained.

Enable **Loop station** in project settings to repeat a cycle of the chosen duration. A new recorded take automatically saves after one cycle and becomes a repeating track. Enable **Settings → Playback while recording** to hear backing tracks during the take. Use headphones and adjust a track's **Position in project** or source range if alignment needs correction. Loop timing is not hardware-synchronized or guaranteed sample-accurate. See [the complete workflow and limits](docs/multitrack-loops.md).

## Home-screen widget

Long-press an empty area of your Android home screen, choose **Widgets**, then add **Stillwave Recorder**. Resize it using your launcher's handles.

Tap any of the three assigned qualities to begin recording. The defaults are **Voice**, **Balanced**, and **Studio**; each button shows Hz and kbps. **Best available · Auto** checks the current input before starting; the timer begins when the selected format is ready. The widget shows the recording format. During recording, the preset buttons become one large **Pause recording** / **Resume recording** control. **Add checkpoint** marks the current recorded position. **Stop & save** saves to the library; **Stop & discard** asks for confirmation inside the widget when the setting is enabled. Recording continues while confirmation is visible; **Keep recording** cancels the discard.

If microphone access has not been granted, the first tap shows Android's permission dialog through a small permission activity, then returns to the launcher. Routine widget controls do not open the library. Android displays the microphone indicator and the recording service notification while capturing.

The preset you tapped is retained through permission requests. Direct buttons and the widget use the tapped slot's assigned quality; they do not change the single-button selector's remembered slot. Pause/resume keeps the current recording's format fixed. If Best available detects a switch to another microphone during capture, it saves the current recording and asks you to start again for the new input. The app's optional single-button style lets you choose an assigned slot or Best available before tapping Record, and remembers that selection for next time. See [recording-quality notes](docs/recording-quality.md) for storage costs and the attached phone's advertised audio capabilities.

## Delete recordings

Open a recording’s three-dot menu (or long-press it), choose **Delete recording**, then confirm. The editor also has a trash button. For several recordings, choose **Select multiple**, select the recordings, and tap the toolbar’s trash button.

Deleting an original keeps already exported cut files. Deleting a cut keeps its original. The recording being played is stopped before deletion. Pending export jobs for the selected original are cancelled; other recordings and their jobs are kept.

## Organize recordings in folders

Tap the library heading (**All recordings**, **Unfiled**, or the current folder name) to open the folder selector. Choose **New folder**, enter a name, and tap **Create**. The folder's three-dot menu offers **Rename folder** and **Delete folder**. Deleting a folder requires confirmation and moves its recordings to **Unfiled** without deleting audio or edits.

To move audio, long-press a recording or open its three-dot menu and choose **Move to folder**. For several recordings, choose **Select multiple**, select the recordings, then tap the folder/move button in the selection toolbar. You can create a new destination folder in the move picker, or choose **Unfiled** to remove folder membership.

**All recordings** shows everything; search, date filters, pins, ordering, and layouts also work inside a folder. Each recording belongs to at most one folder. Folder names are unique ignoring case and surrounding spaces. Folders are a single level.

Recording from inside a folder saves there even if you browse elsewhere before stopping. Recording from All recordings, Unfiled, or the home-screen widget saves to Unfiled. Cut exports inherit the original's folder. If that folder is removed during capture, recovery, or export, the audio saves to Unfiled. Organization uses database metadata; moving a recording does not copy or rename its audio file or change its recording date.

## Library and playback controls

**Settings → Long press a recording** chooses **Open recording menu** (the default) or **Select multiple recordings**. With selection active, taps and long presses add or remove recordings. The recording's three-dot button opens its actions in either mode.

Open a recording's menu and choose **File details** to see its actual file size, WAV/AAC encoding, sample rate, channel count, bitrate, duration, recorded date, folder, and microphone information. WAV files also show PCM bit depth. Selecting one recording exposes an information button. Missing or unreadable audio is reported explicitly.

The compact recorder keeps three direct preset buttons and Best available above the library. In the optional single-button style, Best available occupies its own full-width row below the preset selector, above the timer and Record button. At standard font sizes, the recorder retains the same height when recording starts, pauses, or stops. Larger text can grow the controls for accessibility.

The library heading doubles as the folder selector. Tap **Search recordings** to reveal and focus the search field; **Close search** clears the query and reclaims the space. Active queries remain visible across recreation. The keyboard's Done action dismisses the keyboard while keeping the filter. The options icon contains layout, ordering, and **Filter by recording period**; an active date range remains visible below the toolbar.

Tap the expand icon beside the library heading to give the library the full app screen. Use the collapse icon or Android Back to return to the recorder. Folder browsing, search, selection, playback, and editing remain available. Expanding and collapsing preserve the scroll position. During an ongoing recording, **Show recorder** returns to the recording controls.

Saving audio brings that recording into view without an animated scroll or changing the chosen ordering. If browsing or filtering would hide it, the app opens its destination and clears the blocking filter. Discarding does not jump the list.

Audio continues playing when the app is backgrounded or the phone locks. Android displays media controls in the notification area and lock screen, subject to the phone's notification/lock-screen settings. The controls show the recording or cut title and support pause/resume and seeking. By default, starting microphone capture pauses playback, including when recording from the home-screen widget. **Settings → Playback while recording** enables simultaneous playback and capture in either start order. Disabling it during a take pauses playback while recording continues; re-enabling it does not automatically resume playback. Unplugging headphones and normal Android audio-focus handling also apply.

**Settings → Recording microphone** selects the input for the app, widget, and project takes. **Automatic** uses Android's routing. An explicit input is remembered and checked when recording starts; if it is disconnected or Android cannot route capture to it, the app reports an error instead of silently substituting another microphone. A route change during explicit capture saves the current take. Playback still follows Android's selected headphones, speaker, or other output; the app does not force speaker output. Bluetooth input/output combinations depend on the phone and headset.

**Settings → Stop playback when screen locks** is off by default. Enabling it pauses audio and retains the position when the screen turns off or locks; it also prevents resuming from remote controls until the phone is unlocked. Unlocking alone does not restart playback.

## Build

Use Android Studio supporting AGP 9.4, JDK 21 (the Gradle toolchain requires it; bytecode targets Java 17), Android SDK Platform **37.0**, and SDK Build Tools 36.0.0 or the AGP default. The Gradle 9.6 wrapper includes distribution checksum verification.

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0'
# Set ANDROID_HOME or put sdk.dir=/absolute/path/to/Android/Sdk in local.properties.
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest  # requires a running emulator/device
```

Windows: use `gradlew.bat`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

`./gradlew :app:assembleRelease` produces an unsigned release APK. Release builds are reproducible: building a release tag with the same toolchain gives the same APK as the published one, apart from the signature. See [release signing](docs/release-signing.md).

Pinned stack: AGP 9.4.1, Kotlin/Compose compiler 2.4.21, Compose BOM 2026.09.00, Material 3, Core 1.19.1, Lifecycle 2.11.0, Room 2.8.5 with KSP, DataStore 1.3.0, Media3 1.11.1, WorkManager 2.12.0, and coroutines 1.11.0. Dependencies were checked against official Maven metadata on 2026-10-24. Room 2 is used for its established Android API and migration tooling.

## Architecture and performance

- `audio/RecordingService.kt`: a microphone foreground service, bounded capture buffers, a wake lock while actively recording, and a 10 Hz state flow. Duration comes from actual written frames, excluding pauses.
- `widget/`: native RemoteViews with direct service PendingIntents, session-bound controls, and an on-device Chronometer. The launcher ticks the timer; widget updates occur on state changes rather than every audio buffer. No periodic worker or polling is needed.
- `audio/WavFile.kt`: PCM16/24/32 and float WAV I/O, bounded waveform sampling, and 64 KiB cut buffers. Waveform sampling reads at most 180 × 1024 frames, rather than decoding an entire long recording.
- `audio/WindowRenderer.kt`: bounded frame reversal, anti-aliased varispeed resampling, and Sonic pitch-preserving time stretching. Source-coordinate windows are assembled in order for full exports.
- `audio/AacFile.kt`: Android MediaCodec AAC-LC encoding and decoding with bounded buffers. Private decoded-source and effect-preview caches are bounded, cancellable, and removed with their source recording.
- `audio/ExportWorker.kt`: durable export snapshots with unique output IDs; completed windows are not duplicated when a job restarts. Runs as foreground work so long exports survive leaving the UI.
- `audio/MultitrackRenderer.kt`: two-pass, bounded-buffer mixing with sample-rate conversion, independent offsets and loops, and uniform gain reduction when the mix would clip. Project playback uses private cached renders rather than one player per track.
- `audio/MultitrackAudio.kt` and `MultitrackExportWorker.kt`: ordered unique source locks, shared decoded-source leases, cancellable previews, exact registered preview URI checks, and durable project export snapshots. An interrupted export retains its stable output identity.
- `data/MultitrackStore.kt`: atomic per-project JSON, independent track metadata, and idempotent recovery of captured project takes. Project files remain local; exported mixes and recorded takes use the ordinary library flow.
- `data/`: Room metadata and reactive queries, DataStore preferences, crash recovery. Recording and cut file I/O runs off the UI thread.
- `ui/`: lazy Compose library, settings, editor, and accessibility labels. MediaSessionService owns playback across activity closure; the optional lock-screen preference controls screen-off behavior.

Uncompressed audio favors accurate cuts and low CPU load over disk space. Voice/Balanced/Studio use about 1.9/5.3/11.5 MB per minute. Standard WAV's approximately 4 GiB per-file limit is enforced, including temporary AAC capture and decoded editing files; approaching the capture limit saves the recording with an explanatory message. AAC saves storage after conversion, with additional CPU work at save time. Pitch-preserving processing may introduce audible artifacts, especially at extreme speeds.

## Validation

JVM tests cover overlapping cuts, unchanged source hashes, sample-aligned stereo endpoints, cancellation cleanup, rejected invalid/overwriting cuts, bounded waveform output, strict timestamp parsing, metadata ordering after rename, and daylight-saving date boundaries. Instrumentation tests cover Room migration and metadata, audio/checkpoint recovery, named cut exports, recording checkpoints, widget RemoteViews actions and discard preferences, stale widget actions, Compose interactions, and capture/pause/resume/save at all three fixed presets and Best available. Higher-precision tests verify exact frame preservation, RIFF padding, floating-point metadata, named overlapping exports, and Media3 playback.

Device acceptance checklist:

- Grant/deny microphone permission; record, pause, resume, save; check that paused time is excluded.
- Lock the screen and leave the app while recording; use notification pause/resume/save.
- Discard with confirmation on and off; recover an interrupted capture after process restart.
- Record each quality on real hardware, including Bluetooth/headset disconnection and another app taking the microphone.
- Rename, pin, change layout/order, select multiple recordings, share, and filter on a date boundary.
- Create overlapping windows, type invalid endpoints, preview each, uncheck some, and export selected windows. Compare the original hash.

## References

- [Android Gradle plugin versions and compatibility](https://developer.android.com/build/releases/about-agp)
- [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping)
- [Foreground microphone service exemptions for widget interactions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

## Support

Stillwave is free, with no ads or in-app purchases. If it's useful to you, you can support its development on [Ko-fi](https://ko-fi.com/cili1).

## License

Copyright © 2026 acilione.

Stillwave is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE).
