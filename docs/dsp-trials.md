# DSP trials: what crashed the audio DSP and what worked

All trials on one phone: RedMagic NX809J, Snapdragon "canoe" (`soc_id` 660), Android 16, rooted. Dates are 2026.
Each trial was run through `phone/test.sh` (crash watchdog, auto-unload, auto-reboot) unless noted.
"LPI" = the DSP's low-power island, the mode it runs in with the screen off. "Pure LPI" = no other app
holding the microphone (PAL logged `nlpi votes 0`).

## Before any trial (Sep 30, read-only checks)

| check | finding |
|---|---|
| Why "Hey Google" is deaf with the screen off | `dumpsys soundtrigger`: Google's app attaches to the sound-trigger module daily, then never loads a model. 0 models enrolled; its detector type is software-only. |
| Does the DSP support Qualcomm's sound trigger (SVA)? | Yes. The audio HAL's resourcemanager XML has a `QC_VOICE_UI` stream with vendor uuid `68ab2d40-e860-11e3-95ef-0002a5d5c51b`, a first-stage GMM engine and a PDK engine on LPI, and a user-defined-keyword (UDK) second stage. |
| Qualcomm Voice Activation (QVA) 6.1.4 is installed | Never run. Its own code only enables itself on a list of 21 SoC ids; this phone's 660 is not on it. Its preset models are all PDK models. |

## Trials

| trial | model / engine | capture mode | result |
|---|---|---|---|
| first run (no watchdog) | QVA 6.1.4 PDK preset | stock screen-off low-power profile (`DUAL_MIC_16KHZ_16BIT_FFNS`) | Model loaded, 0 detections. The ADSP crash-looped about every 4 s (`qup_gpi_island ... bus_iface_clean_get_cmd`). Killing the process and the audio HAL did not stop it; a reboot did. |
| t1, t2, t4 | PDK preset | same stock low-power profile | ADSP `EX:audio_process ... BADVA` within 0.5 s, then the same crash loop; only a reboot cleared it. |
| t3, t5, t6, t7, t10 | PDK preset | forced 48 kHz high-performance profile (`profile.sh`) | Stable, 0 crashes, but 0 detections ever, even with every keyword's bar at 1 for 90 s (t10). |
| t8 | PDK preset | forced 16 kHz single-mic, non-LPI | `startRecognition` hung 8 s, the system watchdog killed the audio HAL, the DSP wedged. Reboot. |
| t11 | PDK (5-keyword preset) | another app held the mic, so not true LPI | No crash in 60 s. |
| t12 | same | switched into the stock low-power profile at screen-off | 1 crash, loop stopped by itself. |
| t13 | PDK preset | Google's hotword capture was still active screen-off | No crash, 0 detections. Invalid trial: the DSP was never in pure LPI. |
| t14 | PDK preset | pure LPI (Google's hotword capture switched off) | Crash 0.5 s after start. **The PDK engine dies in pure LPI on this firmware.** |
| **t15** | **GMM "Alexa" model from QVA 4.3.2, bar 40** | **pure LPI** | **0 crashes. "Alexa" said 3 times → 3 detections (confidence 56, 47, 46).** |
| t16 | same GMM model | stock config, no `profile.sh` | Works. The GMM engine needs no config changes. |

What I got wrong along the way, so you don't:

- **A confounded pair.** I first blamed the opaque confidence-level layout for the crash, from two trials that
  differed in two things (layout and screen state). PAL's own log later showed the layout was read correctly.
  Change one variable per trial.
- **"Deaf" is not a result without a known positive.** I nearly closed the whole DSP route after the PDK
  models never fired. The fix was to find a model that should fire (the GMM Alexa model) and test with that.
- **Hidden mic users.** Google's app kept an `AUDIO_SOURCE_HOTWORD` capture open even with the screen off, which
  kept the DSP out of LPI and made five trials meaningless. List the capture clients
  (`dumpsys media.audio_policy | grep -A3 "AudioRecord Clients"`) before every trial. Turning it off for a trial:
  `cmd appops set com.google.android.googlequicksearchbox RECORD_AUDIO_HOTWORD ignore`, and **set it back to
  `allow` yourself afterwards: app-ops survive a reboot.**
- **Logging is off on some ROMs** (`log.tag=S`). `setprop log.tag V` (resets on reboot) shows the PAL lines that
  tell you what actually ran: `lpi votes N, nlpi votes M`, `ParseSoundModel ... version`, the first-stage levels.

## Building "computer" (Sep 30 to Oct 1)

| model | how | result |
|---|---|---|
| UDK from 5 text-to-speech clips | `udkgen`, matching score 92 | Loaded and ran, 0 crashes. At bar 40 it fired 17 times in about 50 s of normal room sound. Far too loose. |
| UDK from 5 close-up clips of my voice | same | False fires per 90 s of normal room sound (TV, talking), one 90 s window per bar: bar 40: 24, 50: 7, 60: 1, 70: 0. At bar 70: 0 false fires in about 6 h overnight, but it caught me only 2 of 5 times, in each of two runs. |
| UDK from 10 clips said from my normal spot | `tools/cut_clips.py` recipe, matching score 88 | 5 of 5 at bar 70 (scores 70 to 73). This is the live model. |

One ADSP crash happened about 6 h into the first overnight run of a UDK model (Oct 1, 05:06). The supervisor
unloaded the model, waited 30 min and restarted cleanly. The cause is unknown; the kernel log had rolled over.
