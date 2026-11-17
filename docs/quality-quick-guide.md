# Recording quality: a quick guide

Assign formats to the three recording buttons in **Settings → Recording button qualities**. The same assignments appear in both recording layouts and the widget. **Best available · Auto** remains a separate choice. Opening the guide does not change a quality assignment or a choice you are still editing.

## Easy starting points

These are practical starting points using formats the app offers, not guarantees for every microphone. Make a short test and listen before a long session.

| What you are recording | Starting point | Why |
| --- | --- | --- |
| Voice notes | AAC, 16,000 Hz, 64 kbps, mono | Small files for speech |
| Guitar or guitar + voice | WAV, 48,000 Hz, 16-bit, mono | Keeps an uncompressed original for editing |
| Jam session or room sound with a real stereo input | WAV, 48,000 Hz, 16-bit, stereo | Preserves the input's left/right image |
| A jam session with smaller files | AAC, 48,000 Hz, 256 kbps, stereo when the input provides it | Less storage than WAV; listen for compression artifacts |

Mono can record a whole band; stereo is useful for spatial separation, not a requirement for having several instruments. A phone's stereo option does not prove that independent left/right microphone signals reach the app. Recording one mono signal as two channels cannot create true stereo. [RØDE: Understanding mono signals](https://help.rode.com/hc/en-us/articles/10068838416399-Understanding-Mono-Signals-from-Microphones).

## What the settings mean

**WAV or AAC:** Stillwave's WAV files retain captured samples without lossy compression. AAC makes smaller `.m4a` files by discarding some detail; higher AAC bitrates generally reduce compression artifacts at the cost of space. WAV is a useful original for editing, while AAC is useful for sharing or long recordings. Changing an AAC recording into WAV later cannot recover detail that compression discarded. [Apple: Encoding formats and import settings](https://support.apple.com/guide/itunes/choose-import-settings-itns2965/windows).

**Hz — sample rate:** This is how often sound is measured. `48,000 Hz` and `48 kHz` mean the same thing. A higher rate permits higher sound frequencies to be represented; it does not automatically improve microphone fidelity. Speech can use lower rates; 44,100 or 48,000 Hz is a practical music choice. 96,000 Hz is not automatically clearer for an ordinary phone recording. [Focusrite: Sample rate and bit depth](https://support.focusrite.com/hc/en-gb/articles/115004120965-Sample-Rate-Bit-Depth-and-Buffer-Size-Explained).

**kbps — bitrate:** Kilobits per second measures the amount of data used. It is not a volume setting. For AAC, this is a target for the entire recording, including both stereo channels. For WAV, it is calculated from sample rate, channel count, and bit depth. A larger WAV bitrate is not directly comparable with an AAC bitrate as a quality score. Android describes the encoder setting as an average bitrate, so a target is not an exact file-size promise. [Android MediaFormat](https://developer.android.com/reference/android/media/MediaFormat#KEY_BIT_RATE).

**Mono/stereo — channels:** Mono has one channel and normally plays through both headphone sides. Stereo has left and right channels, which are useful only when the input captures a meaningful difference between them. [RØDE: Understanding mono signals](https://help.rode.com/hc/en-us/articles/10068838416399-Understanding-Mono-Signals-from-Microphones).

**16-bit/24-bit — WAV bit depth:** This describes the precision of each sample. More precision can help with later level changes if the hardware captures it; it also makes larger files. 16-bit is a practical starting point. A higher setting cannot repair overload at the microphone or guarantee better hardware precision. The app's fixed 32-bit WAV presets use integer samples; they are not a promise of clip-proof capture. [Focusrite: Sample rate and bit depth](https://support.focusrite.com/hc/en-gb/articles/115004120965-Sample-Rate-Bit-Depth-and-Buffer-Size-Explained).

## What happens when you change several settings

For uncompressed WAV, the factors multiply: sample rate × channels × bit depth gives bits per second. Android's PCM frame definition confirms that, for example, stereo PCM16 has two 16-bit samples in each frame. The figures below are calculations from that format, not acoustic quality rankings. [Android AudioFormat](https://developer.android.com/reference/android/media/AudioFormat).

| Format | Approximate storage per minute | What changed |
| --- | ---: | --- |
| WAV, 48,000 Hz, 16-bit, mono | 5.76 MB | Reference |
| WAV, 48,000 Hz, 16-bit, stereo | 11.52 MB | Stereo doubles WAV storage |
| WAV, 48,000 Hz, 24-bit, stereo | 17.28 MB | 24-bit adds 50% compared with 16-bit |
| WAV, 96,000 Hz, 16-bit, stereo | 23.04 MB | Doubling sample rate doubles WAV storage |
| AAC, 128 kbps, mono or stereo | 0.96 MB | Target covers the whole stream |
| AAC, 256 kbps, mono or stereo | 1.92 MB | Doubling target bitrate roughly doubles storage |

At a fixed AAC target bitrate, selecting stereo or raising the sample rate keeps approximately the same final size. The encoder must fit more information into the same data budget; increasing Hz without increasing kbps is not a guaranteed quality improvement. Stereo coding does not necessarily divide the bitrate into two equal halves. These statements follow the app's use of one overall AAC bitrate target; individual devices' encoders may vary their output.

MB means 1,000,000 bytes. Estimates exclude small file headers and metadata. Stillwave temporarily captures WAV even for AAC, then converts when saving. Leave room for that temporary file and allow conversion time. Edited copies are WAV, so they can be much larger than the original AAC.

## Best available and better microphone sound

**Best available · Auto** checks the current input, chooses a supported WAV format, and remembers it when Android provides a stable input identity. It checks that the format works; it does not measure microphone sound quality, real bit precision, noise, or stereo separation. It can use more space and take longer to start than a fixed assigned quality. See the app's [format selection policy](recording-quality.md) for the technical details.

Listen to a short test before raising quality numbers. Keep fingers away from the microphone openings, reduce nearby noise, and move the phone to balance voice and instruments. If loud passages distort, move farther from the loud source or reduce input gain when the connected microphone offers it. Bitrate and sample-rate changes cannot repair audio already clipped at the microphone. Microphone position, room sound, and input overload are separate from file format. [Shure: Microphone techniques for recording](https://www.shure.com/en-US/docs/education/Microphone-Techniques-for-Recording), [Focusrite: Setting input gain](https://support.focusrite.com/hc/en-gb/articles/208958089-How-high-should-I-set-the-gain-control-on-my-interface).

Formats depend on the phone and connected input. If a choice is unavailable, use another assigned quality or Best available. The guide's starting points are suggestions; they do not replace a listening check with your actual microphone.
