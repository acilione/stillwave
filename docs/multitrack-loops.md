# Multitrack, loops, and recording inputs

## Put separate recordings together

Open **Multitrack and loop station** from the library header or layout menu, create a project, then choose **Add recordings**. You can select several recordings in the library and use **Create multitrack from selection** instead. A recording's menu also offers **Open in multitrack**.

Each clip references an original library recording. Adding recordings creates separate tracks; cutting can put several clips on the same track. The project saves changes automatically and supports up to 16 tracks, 128 clips, and a duration of up to 6 hours. Existing projects load with their original positions and audio settings.

The timeline displays the waveform of each clip's source section. Stereo waveforms show the louder channel. Waveform overviews load in the background and are cached; long recordings use bounded sampling, so the overview is not a sample-accurate meter. Zoom in and use the timeline view control for closer positioning. Drag a clip left or right to move it; the position previews while dragging and saves on release. Canceling a gesture leaves the saved position unchanged. A normal project grows when a moved clip needs more room; a loop project's cycle stays fixed and material past its end is excluded. Repeating clips shift their phase. Clip editing is disabled during a project take.

Tap a waveform to select a clip and place the **white cut cursor**, or drag the white slider below **Cut position**. The slider works before playback is prepared and supports accessibility adjustment. **Cut position** also accepts an exact time; all three controls set the same cursor. **Split** divides the selected clip there into two independently movable pieces on the same track. Select overlapping or very small pieces from the clip selector above the editing buttons. The red line and separate playback slider control playback of the prepared mix.

**Trim** changes the source From–To range. Trimming the beginning keeps the remaining sound at the same absolute time. **Position / name** changes the clip's name, source range, and exact timeline position. Times accept seconds, `mm:ss.SSS`, or `hh:mm:ss.SSS`. Clip names do not rename library recordings.

**Duplicate** appends an ordinary copy after the selected clip; **Move to track** places a clip on an existing or new track. Overlapping clips mix together. **Remove clip** removes only that project reference. **Undo last clip edit** restores the previous clip edit during the current session, provided no later change or recording has replaced that revision. Originals are never cut or overwritten; **Save mix to library** exports the arrangement to a new file.

To remove an internal section, split at its start and end, select the middle piece, and choose **Remove clip**. Move the remaining clips together if you want to close the gap.

Splitting a repeating clip first turns its repetitions across the project into finite pieces, including partial pieces at the beginning/end, then applies the cut. This keeps the existing cycle instead of repeating the shorter halves. A split that would exceed 128 clips is rejected; use a shorter project or a longer repeating section. Trimming an unsplit repeating clip changes its repeated source range.

The selected clip has volume from 0% to 200%, **Mute**, **Solo**, and **Repeat clip**. Solo makes the chosen clips audible while excluding other clips; a muted clip remains silent even if Solo is enabled. Repeat clip loops its From–To section through the project. Position acts as a phase offset, wrapping around the beginning as needed.

## Listen and save

**Play** mixes the project live from its source files: playback starts at once and every edit (levels, mute, solo, source ranges, position, project duration) is heard within about a tenth of a second, without rendering. Opening an AAC source for the first time still needs decoding. The live preview pauses when the app goes to the background, unless you are recording a take into the project, and it hard-limits peaks rather than normalizing them, so very loud stacks can sound harsher than the saved mix.

**Background player** prepares a private WAV mix, then starts normal background playback with lock-screen controls. The playhead can seek within the prepared mix. Resuming an unchanged loaded mix reuses it immediately, without waiting for source decoding or waveform loading. Changes to levels, mute, solo, source ranges, position, or project duration require another preparation before they are heard. The previous mix can continue playing during preparation. Longer projects or more tracks take more time and storage; preparation can be cancelled. AAC sources also need decoding on their first use or after their decoded cache is evicted.

This version uses prepared mixes rather than a live mixer with immediate effects or guaranteed seamless changes between mixes. It does not route live microphone input back to the headphones. Use the recording editor to make reverse, speed, or other window edits, save the resulting file, then add that file as a track.

**Save mix to library** asks for a name and saves a new **48,000 Hz, 16-bit stereo WAV**. Different source sample rates are converted for mixing; mono sources feed both stereo channels. If combined levels would clip, the whole mix is reduced uniformly while keeping the relative track levels. A silent or all-muted project exports silence.

Saving runs in the background with progress and cancellation. The job uses the project settings captured when saving began, so later edits do not change that export. It keeps a stable output identity across interruptions to avoid duplicate recordings. The exported WAV can be edited, shared, moved, or backed up like any other recording.

Deleting a project keeps its recordings and exported mixes. Deleting a source recording leaves its project track marked as missing; remove or replace that track before playing or exporting. Project definitions themselves remain local.

## Record while listening

**Settings → Playback while recording** is **off by default**. When off, starting a recording pauses playback, and playback cannot resume while capture is active. Turning this setting off during a take pauses the backing audio without stopping capture. Turning it back on does not restart playback automatically.

When enabled, you can start playback before or after starting a microphone recording. The microphone creates a separate audio file. It does not digitally record the app's backing audio; sound from the speaker can still enter the microphone acoustically. Connect headphones if you want a clean new take.

Inside a project, start **Play**, then choose a quality under **Record new track**. A normal project take is placed at the current playhead and saves when you press **Stop & save**. A paused project retains its playhead position; a project that has not been loaded for playback starts at zero. The saved recording is also available separately in the library.

Capture and playback have separate hardware clocks and routing latency. The app estimates the take's position when input starts, but alignment is not guaranteed sample-accurate. Adjust **Position in project** and, if needed, trim the source's **From** time to align a take by ear. Bluetooth can introduce additional delay. Project takes use Stop & save or Discard rather than pause/resume so their timeline stays continuous.

## Build a loop

1. Create a project with **Loop station** enabled and set **Loop duration** to the desired cycle length.
2. Add recordings, or record the first take. New loop takes stop and save automatically after one cycle.
3. Enable **Playback while recording**, play the project, and record another take while listening through headphones.
4. Each saved loop take becomes a repeating track. Adjust its range, phase/Position, level, mute, or solo, then listen to the newly prepared mix.
5. Use **Save mix to library** to export one project cycle. The Loop control repeats project playback; it does not export an infinitely repeating file.

**Stop & save** can finish a take before the cycle ends; that shorter source then repeats at its own length. **Discard** removes only the unsaved take and uses the usual discard-confirmation preference. Enabling the project's Loop control does not automatically change the Repeat clip setting on existing clips; those controls remain independent.

This loop station does not provide a tempo grid, count-in, quantization, automatic latency calibration, or hardware-synchronized overdubbing. Set the cycle length and adjust alignment manually. Newly recorded tracks are heard after saving and preparing the updated mix, not immediately as live monitored input.

## Choose the microphone

Choose **Microphone** under **Record new track** directly inside a project, or open **Settings → Recording microphone**. Both controls share the same saved choice and update when inputs connect or disconnect. **Automatic** lets Android choose the capture route. Select a listed built-in, headset, USB, Bluetooth, or other available input to request that microphone for future recordings in the app, widget, and projects. The choice and its name are remembered. Devices that Android cannot distinguish reliably may need selecting again after reconnection or an app restart. Finish an active recording before changing its microphone.

An unavailable selected input stays visible with a disconnected message. Reconnect it or choose Automatic/another input. Recording reports an error if an explicit microphone cannot be used; it does not silently switch to another one. If its route changes during recording, the current take is saved. **Best available** checks formats for the selected input; it does not override microphone selection.

Input selection changes capture routing only. Playback follows the output selected by Android, including connected headphones. The app does not force the speaker or promise every microphone/headphone combination. Bluetooth microphones can change the output route or reduce playback quality according to the phone and headset's capabilities.

Android distinguishes a requested input from the actual active route; Stillwave checks the latter before accepting a capture. See [AudioRouting](https://developer.android.com/reference/android/media/AudioRouting) and [AudioManager input enumeration](https://developer.android.com/reference/android/media/AudioManager#getDevices(int)).

## Loop a recording or editor window

Tap the repeat icon beside Play in the recording list to start looping that recording. Tap it again to turn repeat off and finish the current pass. Play/Pause keeps the current loop choice. **Settings → Show loop button in recording list** hides the list icon; **Loop recording** remains available in the recording's three-dot menu.

Each editor window also has a **Loop** control. It repeats only that window, including its reverse, speed, and pitch settings. Choosing another recording or window with ordinary Play/Preview starts it without repeat. Looping is a playback mode; it does not change the source or the exported file. Repeat continues during background playback, subject to the existing screen-lock and playback-while-recording settings. Playback uses Media3's [repeat-current-item mode](https://developer.android.com/reference/androidx/media3/common/Player#REPEAT_MODE_ONE).

## Remove sections from a recording

In the ordinary editor, create windows around the sections to remove and tick their checkboxes. Choose **Remove selected sections**, enter a name, and save. The remaining audio is joined into one new WAV, including material before, between, and after those windows. Overlapping removal windows are merged. Window speed/reverse settings are not applied to this operation.

Removing the entire source is rejected because nothing would remain. The original and its windows remain intact. Open the new recording to continue editing, or add it to a multitrack project.
