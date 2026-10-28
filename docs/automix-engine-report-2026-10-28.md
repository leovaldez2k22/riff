# AutoMix / DJ transition engine: technical report for Riff

28 October 2026. A research and design report only: nothing in the app has changed. It answers the owner's brief for an implementation-ready report on an AutoMix engine modelled on Apple Music's AutoMix (iOS 26), Spotify's Mix and AI DJ transitions, and Algoriddim djay's Automix.

**Target stack.** The brief left the stack blank. This report targets **Riff**: Android 12+, Kotlin, Jetpack Compose, Media3 1.11 ExoPlayer, a single `:app` module. The analysis and mixing core is written so it can later move unchanged in design to C++ (Android NDK, iOS, desktop) and WebAssembly in an AudioWorklet. Section 4.5 covers that port.

It is the implementation reference for Feature 20 (Automix) in the [feasibility and implementation plan](feasibility-glass-font-lyrics-artwork-2026-10-27.md#feature-20-automix-dj-style-transitions), which also lists how Automix fits with the other features (the shared song decoder of Feature 9, the audio chain of Feature 10, and playback speed and casting turning it off). Where the two differ, this report is the more detailed and current one.

**About the numbers.**
- Every equation is given in full.
- Every filter coefficient in the worked examples was computed (48 kHz, double precision) and checked: the 120 Hz crossover's two halves sum to 0.000 dB from 30 Hz to 1 kHz.
- Licence and price facts come from the sources at the end. Where sources disagree or a fact couldn't be confirmed, the text says so and names what to check. These are marked **[verify]**.

## Contents

1. [Streaming DRM versus raw PCM access](#1-streaming-drm-versus-raw-pcm-access)
2. [Music information retrieval: beats, structure, vocals, key](#2-music-information-retrieval-beats-structure-vocals-key)
3. [The real-time transition engine and its mathematics](#3-the-real-time-transition-engine-and-its-mathematics)
4. [SDK, library and API comparison](#4-sdk-library-and-api-comparison)
5. [Reference implementation and failure handling](#5-reference-implementation-and-failure-handling)
6. [Evaluation, delivery plan and risks](#6-evaluation-delivery-plan-and-risks)
7. [Sources](#7-sources)

---

## 1. Streaming DRM versus raw PCM access

### 1.1 What the commercial products do

| Product | What is known about its transitions | Source |
|---|---|---|
| Apple Music AutoMix (iOS 26) | Analyses tempo and key, then uses **time-stretching and beat-matching** for continuous playback. It may overlap beats, fade, or leave a short pause. It's off for full albums. It runs on Apple's own decrypted pipeline. | 9to5Mac, MusicTech, iGeeksBlog |
| Spotify Mix (Aug 2025, Premium, beta) | Automatic or custom transitions on the user's own playlists: presets (fade, rise, blend), volume curves, EQ fades and effects, applied **in real time during playback, without altering the tracks**. It shows each song's BPM and Camelot key. | TechCrunch, RouteNote, Gear Patrol, MakeTechEasier |
| Algoriddim djay Automix | Tempo modes **Automatic, Sync, Sync + Tempo Blend** (gradually moves the outgoing tempo toward the incoming one). Transition types include Fade, Filter, EQ, Echo, Dissolve and Neural Mix (real-time stem separation into vocals, harmonic, bass and drums). Durations in bars or seconds. | Algoriddim help centre, MusicTech, DJ Mag |

All three run inside the rights holder's or a licensed player's own audio pipeline, where the decrypted PCM is available. A third-party app usually doesn't have that.

### 1.2 Where third-party apps can and can't touch the audio

| Source | Encryption and transport | PCM access for a third-party app | Terms (as found) | Feasible engine |
|---|---|---|---|---|
| Spotify (Web Playback SDK, iOS/Android SDKs) | Widevine / FairPlay DRM; playback inside Spotify's own player or SDK | None. The SDK exposes play, pause, seek, volume and position only | Analysis endpoints closed to new apps on **27 Nov 2024**: Audio Features, Audio Analysis, Recommendations, Related Artists, Featured and Category playlists. Existing extended-mode apps kept access | Volume-only fades between two players at best, timed from metadata. **No beat-matching, no EQ** |
| Apple Music (MusicKit, MusicKit JS) | FairPlay DRM; playback by Apple's player | None | A published clause sample says MusicKit content may not be downloaded, modified or **synchronized with any other content** unless the documentation permits it. The App Store guidelines say MusicKit isn't a replacement for licences a deeper integration needs **[verify the current DPLA 3.3.6 wording]** | Standard controls only |
| YouTube (IFrame Player API, Data API) | Player in an iframe; no PCM to the host page | None through the official API | A 2012 YouTube legal letter quoted the API terms as forbidding anyone to "separate, isolate, or modify the audio or video components" **[verify against the current YouTube API Services Terms]** | Volume only, through the official API |
| Browser EME (any Widevine stream) | Content decryption module may output only to hardware | A `MediaElementAudioSourceNode` on protected media yields silence (proposed in W3C discussion as the cross-origin treatment) **[verify current browser behaviour]** | Per service | Volume only |
| **Local files** (MP3, AAC, Opus, FLAC, WAV) | None | Full: decode to PCM | The user's own files | **Full DSP engine** |
| Royalty-free or licensed stem catalogues | None, or licensed decryption | Full | Per licence; stems allow stem-based mixing | Full, including stems |
| **Riff's YouTube Music streams** | Unencrypted Opus/AAC over HTTPS, fetched through the InnerTube client (`innertubex`), **not** the official YouTube API | Full: Media3 decodes to PCM, and Riff's `VolumeBoostProcessor` already processes it | Riff isn't an official API client; its README states it's independent and not affiliated with Google or YouTube. Riff already modifies PCM (boost, normalisation, crossfade) | **Full DSP engine, technically.** The legal position is the same grey area Riff already occupies; AutoMix doesn't change its nature |

**Conclusion for Riff:**
- Riff is in the rare position of having raw PCM for streamed music, so the complete engine is technically possible.
- For DRM services (Spotify, Apple Music, YouTube's official player) the only legitimate engine is two players with volume ramps. Section 1.3 describes it for completeness and for any future provider.

### 1.3 The workaround for DRM streams: two players with gain envelopes

1. **Two player instances, A and B.** Each needs its own playback session. Spotify typically allows one active stream per account, so two simultaneous Spotify players on one account may not be possible **[verify]**.
2. **Lookahead metadata.** Without PCM, BPM, key and section times must come from a cloud source (section 4.3), not from Spotify's closed endpoints.
3. **Timing.** The SDK reports position with unknown output latency, typically tens of ms and varying by device. Beat alignment needs ≤10–15 ms, so **beat-matching is not achievable**. Use **gain-only equal-power fades** (section 3.3) placed at metadata-estimated phrase boundaries, with the fade long enough (≥4 s) that a 50–100 ms timing error isn't audible as a flam.
4. **Volume control** through the SDK's `setVolume` at about 20–50 Hz. Most SDKs smooth volume changes; check for zipper noise.

### 1.4 True DSP on raw PCM (Riff's case)

With PCM, the engine can:
- time-stretch (section 3.2);
- filter and EQ each deck (section 3.3);
- align beats at sample level (section 3.1);
- apply effects (echo-out, filter sweep);
- compute loudness exactly (EBU R128);
- analyse every song itself, with no cloud dependency (section 2).

---

## 2. Music information retrieval: beats, structure, vocals, key

All analysis runs **on the phone, ahead of playback**, on the first and last 60–90 s of each song. Results are cached per song (schema in section 5.2).

### 2.1 Pre-processing

1. **Decode:** decode with `MediaExtractor` + `MediaCodec` (the decoder Feature 9 of the feasibility report introduces).
2. **Mono mix:** `m[n] = (L[n] + R[n]) / 2`.
3. **Resample** to `fs_a = 22,050 Hz` for analysis: windowed-sinc, Kaiser β = 8.6, 32 taps per phase. Analysis doesn't need content above 11 kHz.
4. **STFT:** frame `N = 2048` (93 ms), hop `H = 441` (20 ms, so **50 frames per second**), Hann window `w[n] = 0.5 − 0.5·cos(2πn/(N−1))`.
5. **Log-magnitude spectrogram:** `Y(m,k) = log(1 + γ·|X(m,k)|)` with `γ = 100`.

### 2.2 Beat grid and downbeats

**Algorithm comparison:**

| Algorithm | Method | Accuracy reported | Licence | Fit for Riff |
|---|---|---|---|---|
| madmom `RNNDownBeatProcessor` + `DBNDownBeatTrackingProcessor` | BLSTM activations, then a dynamic Bayesian network (HMM approximation) over tempo and bar position | Strong on Western pop and dance; the DBN post-processing became the field's standard | Code **BSD**, pretrained models **CC BY-NC-SA 4.0** (commercial use needs permission from the authors) | Models can't ship in a GPL app (NC is an extra restriction). Python only. Research and evaluation use only |
| Essentia `RhythmExtractor2013` | `multifeature` (BeatTrackerMultiFeature, slower, more accurate, gives a confidence) or `degara` (faster, less accurate, no confidence); requires 44.1 kHz input | Independent tests: accuracy depends mostly on steady rhythm and drums; worst on slowed-down material | **AGPL-3.0** or commercial from UPF; its pretrained models **CC BY-NC-ND 4.0** | Code is AGPL (combinable with GPL-3.0, but AGPL obligations apply). C++ |
| aubio `tempo`, `onset` | Onset detection functions, autocorrelation tempo, causal beat tracking | Good for real-time onset detection; simpler tempo tracking | **GPL-3.0** (commercial by arrangement) | Licence compatible with Riff; C |
| librosa `beat_track` | Ellis 2007 dynamic programming over onset strength | Classic baseline; tempo octave errors on half-time music | **ISC** | Python only; the algorithm is easy to reimplement (below) |
| **Beat This!** (CPJKU, ISMIR 2024) | Transformer on spectrograms, **no DBN** | Ballroom beat F1 97.5, downbeat F1 95.3 (8-fold CV); GTZAN beat F1 88.9, downbeat 75.5–78.3 (sources differ) | **MIT** for code and published weights, per secondary sources **[verify CPJKU/beat_this LICENSE]** | Best accuracy; ONNX int8 about 4.5 MB plus ONNX Runtime |
| allin1 (all-in-one structure) | One network for tempo, beats, downbeats, section boundaries and labels; depends on madmom | Strong on pop structure | Package licence **not confirmed** **[verify]**; depends on madmom's NC models | Research only until confirmed |

**Recommended for Riff:**
- **Tier 1, ship first:** a Kotlin reimplementation of the classic pipeline below. No model, ~50 KB of code, no licence questions.
- **Tier 2, optional download:** Beat This! in ONNX Runtime, only if measured accuracy on the owner's test set (section 6.1) needs it.

#### 2.2.1 Onset strength (spectral flux)

```
SF(m) = Σ_{k=1..K} max(0, Y(m,k) − Y(m−1,k))          K = bins from 30 Hz to 11 kHz
O(m)  = SF(m) − mean_{|j−m|≤W} SF(j),  clipped below at 0     (W = 25 frames, a 1 s local mean)
O(m)  = O(m) / std(O)                                          (normalise)
```

Also keep a **low-band** onset function `O_low(m)` over 30–150 Hz bins only, for kick drums and downbeats.

#### 2.2.2 Global tempo

Autocorrelation of `O` over lags `τ` (frames), weighted by a log-Gaussian tempo prior centred at 120 BPM (Ellis 2007):

```
A(τ)  = Σ_m O(m)·O(m+τ)
W(τ)  = exp( −0.5 · ( log2(τ/τ0) / σ )² )      τ0 = 60·fps/120 = 25 frames,  σ = 1.0 octave
τ*    = argmax_{τ ∈ [fps·60/200, fps·60/60]} W(τ)·A(τ)
BPM   = 60·fps / τ*                            refined by parabolic interpolation of A around τ*
```

**Octave correction:** if `A(2τ*) > 0.6·A(τ*)` and `BPM > 140`, prefer `BPM/2`. If `A(τ*/2) > 0.6·A(τ*)` and `BPM < 80`, prefer `2·BPM`. Store both octaves; the planner (section 5.4) may use either.

#### 2.2.3 Beat positions (dynamic programming, Ellis 2007)

With beat period `P = τ*` frames:

```
C(t)    = O(t) + max_{s ∈ [t − 2P, t − P/2]} ( C(s) + α·F(t − s, P) )
F(Δ, P) = −( ln(Δ / P) )²                      α = 100 ("tightness")
back(t) = the s that achieved the max
```

Take the beat with the highest `C` in the last `P` frames, follow `back` to the start, and reverse. Refine each beat to the nearest onset peak within ±2 frames (±40 ms), then by parabolic interpolation to sub-frame precision (~2 ms).

#### 2.2.4 Downbeats ("the 1")

For meter `M ∈ {3, 4}` (4 by default) and phase `φ ∈ {0..M−1}`:

```
S(φ) = mean_j [ O_low(b_{Mj+φ}) ] + λ · mean_j [ D_chroma(b_{Mj+φ}) ]       λ = 0.5
```

where `D_chroma(b)` is the cosine distance between the mean chroma of the beat before and after `b` (harmony tends to change on downbeats). The downbeat phase is `φ* = argmax S(φ)`.

**Meter check:** compute `S` for `M ∈ {3, 4, 5, 6, 7}`. If the best non-4 meter beats 4/4 by more than 15 %, record that meter with low confidence. This is used by the 5/4 fallback in section 5.5.

#### 2.2.5 Confidences

- **Beat confidence:** `c_beat = 1 − CV(IBI)`, the coefficient of variation of inter-beat intervals over the analysed window, mapped into [0, 1] by `clamp((0.08 − CV)/0.08, 0, 1)`. Steady electronic music lands near 1. Rubato lands near 0.
- **Tempo stability:** the analysis window is split into 8-second blocks, with a local BPM for each. `drift = (max − min)/median`. Above 2 %, use a **dynamic grid** (local beats), never a single BPM.
- **Downbeat confidence:** `c_down = (S(φ*) − second-best S) / S(φ*)`.

A tempo from metadata alone (a cloud API) carries `c_beat = 0` and **never** permits beat-matching. This is the same rule BitChord's planner uses.

### 2.3 Structure: intro, verse, chorus, drop, outro

1. **Beat-synchronous features:** for each beat, the mean of:
   - 12-bin chroma (constant-Q or STFT mapped to pitch classes, normalised);
   - 13 MFCCs (40 mel bands, DCT-II);
   - RMS energy;
   - low-band energy (30–150 Hz).
2. **Self-similarity:** `S_ij = cos(f_i, f_j)` over beats.
3. **Novelty (Foote, 2000):** correlate along the diagonal with a Gaussian-tapered checkerboard kernel of 2×16 beats:

   ```
   K(a,b) = sign(a)·sign(b)·exp(−(a² + b²)/(2·(0.5·L)²)),   a, b ∈ [−L, L), L = 16 beats
   N(i)   = Σ_{a,b} K(a,b) · S_{i+a, i+b}
   ```

4. **Boundaries:** peaks of `N` above `mean + 0.5·std`, at least 8 bars apart, **snapped to the nearest downbeat**, and preferably to phrase lines (every 8 or 16 bars from the first downbeat).
5. **Labels by energy and position:**

   | Label | Rule |
   |---|---|
   | `intro` | First segment, if its mean RMS < 0.6 × song median, or its low-band energy < 0.5 × median |
   | `outro` | Last segment, if its RMS falls below 0.6 × median and stays there to the audible end |
   | `drop` / `chorus` | Segments in the top quartile of RMS and low-band energy; "drop" if preceded by a segment with low-band energy < 0.3 × median (a breakdown) |
   | `breakdown` | Low-band energy < 0.3 × median between two high-energy segments |
   | `verse` | Everything else |

6. **Mix candidates:**
   - **mix-out** candidates are downbeats at the start of `outro`, `breakdown`, or the last phrase line before the audible end;
   - **mix-in** candidates are the first downbeat after the audible start, the end of `intro`, and the first `drop`.

   Each gets a score:

   ```
   score = w_type · c_down · (1 − vocal_activity_in_window) · phrase_bonus
   w_type: drop 1.0, intro_end 0.8, outro_start 0.9, breakdown 0.7, first_downbeat 0.5
   phrase_bonus: 1.0 on a 16-bar line, 0.9 on 8, 0.7 otherwise
   ```

   Published DJ cue-point work (Zehren et al., *Computer Music Journal* 2022, with the M-DJCUE annotated dataset; cue-point estimation as object detection, arXiv 2407.06823) can be used to tune these weights against human-chosen cues.

### 2.4 Vocal activity and avoiding vocal clashes

**Goal:** never overlap two songs' vocals at full level during a blend.

| Method | How | Licence | Cost on a phone | Accuracy |
|---|---|---|---|---|
| **Heuristic (recommended first)** | HPSS (median filtering, Fitzgerald 2010): harmonic part `H = median_time(|X|, 17 frames)`, percussive `P = median_freq(|X|, 17 bins)`, soft mask `M_H = H²/(H² + P²)`. Then on the harmonic part, in 300–3,400 Hz: vocal score `v(m) = (E_band/E_total) · (1 − flatness) · f0_salience`, smoothed over 0.5 s, threshold at the 70th percentile of the song | Own code | Small (median filters) | Moderate: confuses lead guitar or synth with voice |
| Spleeter 2-stem (Deezer) | U-Net masks vocals/accompaniment | **MIT** | TensorFlow model ~tens of MB; offline only | Good |
| Demucs (htdemucs) | Hybrid transformer separation | Code **MIT**; weights' licence **disputed** (author earlier said research-only; a 2026 model card says MIT) **[verify]** | Heavy; not real-time on phones | Best |
| open-unmix (UMX-HQ) | BLSTM separation | Weights described as **CC BY-NC-SA 4.0** in the official README; a third-party export says MIT **[verify Zenodo 3370489]** | Medium (BitChord ships a 9 MB int8 ONNX) | Good |

**For Riff:**
- Use the heuristic. It needs no model and has no licence questions.
- Use separation only offline, for evaluating the heuristic.
- djay's Neural Mix (real-time stems) is out of reach on mid-range Android without a large model.

**Rule in the planner:** the overlap window `[t_start, t_end]` may not have `v_A(t) > θ` and `v_B(t) > θ` at the same time for more than 1 beat (θ = the 70th percentile). If it would, then:
1. move the mix points to the nearest candidates without overlap;
2. otherwise **duck B's mid band** (300–3,400 Hz peaking EQ, −9 dB, Q 0.7) until A's vocal ends;
3. otherwise shorten the blend to ≤1 bar.

### 2.5 Key detection and harmonic compatibility

**Key detection (Krumhansl–Kessler profiles):**

1. Compute a chroma vector `c` (12 pitch classes, C = 0) averaged over the song's analysed window, weighting each frame by its harmonic energy (from HPSS).
2. Correlate with the 24 rotated profiles:

   ```
   major profile  K_maj = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88]
   minor profile  K_min = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17]
   r(key) = pearson( c, rotate(K_mode, tonic) )
   key*   = argmax r ;  key confidence c_key = r(key*) − r(second best)
   ```

**Camelot / Open Key mapping in code.** With pitch class `p` of the tonic (C = 0, C♯/D♭ = 1, … B = 11):

```
camelotNumber(major, p) = ((7·p) mod 12 + 7) mod 12 + 1          letter B
camelotNumber(minor, p) = camelotNumber(major, (p + 3) mod 12)    letter A  (same number as the relative major)
Open Key: number = ((camelot − 8 + 12) mod 12) + 1;  letter 'd' (major) / 'm' (minor)
```

That gives (computed):

| Key | Camelot | Key | Camelot |
|---|---|---|---|
| C | 8B | A minor | 8A |
| G | 9B | E minor | 9A |
| D | 10B | B minor | 10A |
| A | 11B | F♯ minor | 11A |
| E | 12B | C♯/D♭ minor | 12A |
| B | 1B | G♯/A♭ minor | 1A |
| F♯/G♭ | 2B | D♯/E♭ minor | 2A |
| D♭ | 3B | B♭ minor | 3A |
| A♭ | 4B | F minor | 4A |
| E♭ | 5B | C minor | 5A |
| B♭ | 6B | G minor | 6A |
| F | 7B | D minor | 7A |

**Compatibility score** between keys `(n1, l1)` and `(n2, l2)`, where `d = min(|n1−n2|, 12−|n1−n2|)` is the distance around the wheel:

| Relation | Condition | Score |
|---|---|---|
| Same key | d = 0, same letter | 1.00 |
| Adjacent ("±1") | d = 1, same letter | 0.90 |
| Relative major/minor | d = 0, other letter | 0.85 |
| Diagonal | d = 1, other letter | 0.65 |
| Energy boost ("+2") | d = 2, same letter | 0.60 |
| Semitone up ("+7") | n2 = n1 + 7 mod 12, same letter | 0.50 |
| Anything else | otherwise | `max(0, 0.45 − 0.08·(d − 2))` |

The score is multiplied by `min(c_key,A, c_key,B)/0.2` (capped at 1), so uncertain keys count less.

**Pitch-preserving transposition bounds:**
- A pitch shift of `s` semitones scales frequency by `2^(s/12)`.
- Changing tempo by **resampling** (vinyl-style) shifts pitch by `12·log2(r)` semitones. A 124 → 128 BPM match (r = 1.0323) by resampling shifts pitch **+0.55 semitones** (computed), audibly out of tune. So tempo matching must use **pitch-preserving** time-stretching (section 3.2).
- Deliberate transposition to improve key compatibility is limited to **±1 semitone**, and only on the **incoming** song during the blend and its first phrase. Larger shifts colour vocals audibly.
- Riff's default is **no transposition**. Key decides only the overlap length:
  - score ≥ 0.85: up to 16 bars;
  - 0.6–0.85: up to 8 bars;
  - below 0.6: at most 2 bars, or a filter/echo transition.

---

## 3. The real-time transition engine and its mathematics

### 3.1 The two-deck state machine

Deck A is the song playing; deck B is the next. States and transitions:

```
             analysis ready & next item known
  IDLE ─────────────────────────────────────────▶ PLANNED
   ▲                                                  │  t_now ≥ t_preload  (= t_mixstart − 20 s)
   │                                                  ▼
   │                                              PRELOADING ── B fails to buffer by t_arm ──▶ FALLBACK_FADE
   │                                                  │  B decoded & buffered ≥ (blend + 10 s)
   │                                                  ▼
   │                                               ARMED   (B paused at cue_B − lead, muted, stretch ratio set)
   │                                                  │  t_now ≥ t_mixstart − lead
   │                                                  ▼
   │                                              SYNCING  ── |phase error| > 20 ms after 2 beats ──▶ FALLBACK_DJ
   │                                                  │  |phase error| ≤ 10 ms for 1 beat
   │                                                  ▼
   │                                              BLENDING (automation lanes run; tempo ramps; bass swap)
   │                                                  │  t ≥ t_mixend
   │                                                  ▼
   │                                              HANDOFF  (A released; B becomes A; tempo returns to 1.0 over 4 bars)
   └──────────────────────────────────────────────────┘

  FALLBACK_DJ   : no stretch; B started on A's downbeat with the same lanes minus tempo.
  FALLBACK_FADE : equal-power fade of planned length at the plan's points, or Riff's existing crossfade.
  ABORTED       : user seek, skip, pause > 2 s, queue edit or song change during PRELOADING..BLENDING
                  → stop B, restore A gain/filters to unity over 50 ms, return to IDLE (or start B now if the user skipped).
```

**Timing parameters:**
- `lead`: time B starts before the mix point to settle its decoder and stretcher: 2 beats, at least 500 ms.
- `t_preload`: 20 s before the mix start. Riff's streams need time to buffer.

**Sample-accurate alignment.** Two separate output streams (two `AudioTrack`s) can't be aligned to better than their reported timestamps' jitter. Riff's current crossfade holds them within **120 ms** (`crossfadeTailAligned`), and needs a correction loop to get further. The design therefore uses **one output and a mixer**:

- **Deck B's player** gets a custom Media3 `AudioSink`, a "capture sink". It does not write to an `AudioTrack`. It writes B's processed PCM (after B's own stretcher and filters) into a **lock-free single-producer/single-consumer ring buffer** of 2 s of 48 kHz stereo float (192,000 frames × 2 channels).
  - It reports its playback position as **frames consumed by the mixer**, so B's player clock is driven by actual consumption.
  - `handleBuffer` returns `false` when the ring is full, which is ExoPlayer's normal back-pressure.
- **Deck A's player** keeps the real `DefaultAudioSink`. Its processor chain gains a `DeckMixProcessor`, placed after the equalizer and before the booster and limiter. Per output block:
  1. pull `n` frames from B's ring, starting at an exact frame index;
  2. apply both decks' gain and filter lanes;
  3. sum them.

  Alignment is then exact to the sample: the mixer decides which B frame lines up with which A frame.
- **At HANDOFF** the roles swap. B's player moves to the real sink at its current position, and A's player is released. Media3 allows changing the sink only by rebuilding the renderer, so the swap is done at a song boundary, with a 20 ms gain bridge. This is equivalent to what Riff's crossfade does today when the tail player becomes main.

**Phase lock with the mixer:**
- The mixer starts reading B at output frame `n_start`:

  ```
  n_start = n_A(cue_A) − round( (cue_B − pos_B0) · fs / r_B )
  ```

  - `n_A(cue_A)`: the output frame at which A's chosen downbeat plays;
  - `pos_B0`: B's content time at its first buffered frame;
  - `r_B`: B's playback rate during the blend.
- The residual phase error is the stretcher's group delay, a known constant per stretcher, measured once at startup with a click track, and corrected in `n_start`.
- `SYNCING` then only checks that B's ring contains the frames needed, with no feedback loop.
- **Fallback when the capture sink isn't available** (for example on a decoder error): Riff's present two-player method, with `CrossfadeTailSynchronizer`'s tolerance tightened from 120 ms to 10 ms using `AudioTrack.getTimestamp`. Speed nudges are clamped to ±0.5 % for ≤400 ms. If the error isn't under 20 ms within 2 beats, go to `FALLBACK_DJ`.

### 3.2 Time-stretching and pitch-shifting

Notation:
- `s` is the **speed** (output plays content `s` times faster);
- the stretch factor is `α = 1/s` (output duration over input duration);
- `N` is the frame length, `H_a` the analysis hop and `H_s` the synthesis hop, with `H_a = s·H_s`.

#### 3.2.1 WSOLA (Waveform Similarity Overlap-Add; Verhelst & Roelands 1993)

```
N   = 1024 at 48 kHz (21.3 ms),   H_s = N/2,   w = Hann (sums to 1 at 50 % overlap)
τ_m = m·H_a                                     nominal analysis position of frame m
Δ_m = argmax_{Δ ∈ [−Δmax, Δmax]}  Σ_n  x[τ_{m−1} + Δ_{m−1} + H_s + n] · x[τ_m + Δ + n]
                                                (normalised cross-correlation over n ∈ [0, N))
                                                Δmax = 256 samples (5.3 ms)
y[m·H_s + n] += w[n] · x[τ_m + Δ_m + n]
```

The search makes each new frame continue the waveform of the previous one ("natural progression"), which avoids phase jumps.

- **Cost:** O(N·Δmax) per frame, or O(N log N) with FFT correlation.
- **Artefacts:** transient doubling or skipping at large ratios; very little colouration at |s − 1| ≤ 4 %.
- **Latency:** about N + Δmax ≈ 27 ms.

SoundTouch (LGPL) uses WSOLA-like routines in the time domain, with typical I/O latency around 100 ms. Sonic (Apache-2.0) is pitch-period based and optimised for speech at high speeds; its author says WSOLA suits music better. Media3's built-in `SonicAudioProcessor` is Sonic-based.

#### 3.2.2 Phase vocoder (Flanagan & Golden 1966; Laroche & Dolson 1999)

```
X(m,k)  = STFT of x with hop H_a, N = 4096, Hann
ω_k     = 2πk/N
Δφ      = arg X(m,k) − arg X(m−1,k) − ω_k·H_a
Δφ_w    = Δφ − 2π·round(Δφ / 2π)                     (wrap to [−π, π])
ω̂(m,k)  = ω_k + Δφ_w / H_a                           (instantaneous frequency)
φ_s(m,k)= φ_s(m−1,k) + H_s · ω̂(m,k)                  (synthesis phase)
Y(m,k)  = |X(m,k)| · e^{j φ_s(m,k)} ;   y = ISTFT with hop H_s
```

- **Phase locking (Laroche–Dolson "identity phase locking"):** for each spectral peak `k_p`, bins in its region of influence take `φ_s(m,k) = φ_s(m,k_p) + arg X(m,k) − arg X(m,k_p)`. This removes most "phasiness".
- **Transients:** reset the phases to the analysis phases at detected onsets (spectral flux peaks) to stop drum smearing.
- **Cost:** O(N log N) per hop.
- **Latency:** ≥ N (85 ms at 4096).

#### 3.2.3 Pitch-shifting

To shift pitch by `s` semitones without changing tempo:
1. time-stretch by `α = 2^(s/12)`;
2. resample by `1/α`.

Formant-preserving variants (cepstral envelope correction) matter for shifts beyond ±1 semitone; they're not needed within Riff's ±1 bound.

#### 3.2.4 Choice for Riff

| Engine | Method | Licence | Quality at ±4 % | Latency | Notes |
|---|---|---|---|---|---|
| Media3 `SonicAudioProcessor` | Sonic | Apache-2.0 | Acceptable; minor graininess on sustained tones | Low | Already in Riff; zero size cost |
| SoundTouch | WSOLA-like | LGPL-2.1 | Good on rhythmic music | ~100 ms | Native library; LGPL allows dynamic linking |
| **Rubber Band** (R3 "finer" engine) | Phase vocoder hybrid with transient handling | **GPL-2.0-or-later** or commercial; App Store distribution needs the commercial licence | Best of the open options | ~50–100 ms (R3 more) | **Compatible with Riff's GPL-3.0**; NDK build, about 0.5–1 MB |
| Superpowered | Proprietary time-stretch | Free-tier claims conflict with its 2022 master licence ("no free licence that allows public release") **[verify with licensing@superpowered.com]** | Very good | Low | Proprietary; not GPL-compatible to bundle |

**Recommendation:**
- **Stage 1:** Media3 Sonic (already present), limited to speed-ups of ≤4 %. Speeding up drops slivers of audio cleanly; slowing down repeats them, which sounds metallic. BitChord's planner found the same and only ever speeds up.
- **Stage 2:** Rubber Band through the NDK, if listening tests (section 6.1) show Sonic's artefacts.

### 3.3 Gain curves, EQ and the bass swap

#### 3.3.1 Fade curves

With progress `x ∈ [0, 1]` through the blend:

| Curve | Outgoing `g_A(x)` | Incoming `g_B(x)` | Property |
|---|---|---|---|
| Linear | `1 − x` | `x` | Constant **amplitude**: sums to 0 dB for identical (coherent) signals; −3.01 dB at the midpoint for uncorrelated signals |
| Equal power | `cos(πx/2)` | `sin(πx/2)` | Constant **power** (`g_A² + g_B² = 1`): 0 dB for uncorrelated signals; **+3.01 dB** at the midpoint for coherent signals (+2.32 dB at x = 0.25 and 0.75) |
| S-curve (DJ) | `cos²(πx/2)` | `sin²(πx/2)` | Linear-sum in amplitude with gentle ends |

**Which to use:** two different songs are mostly uncorrelated, so **equal power** is right for the full-band signal. Beat-matched kick drums and basslines are partly **coherent**:
- two kicks landing together at equal level sum to +6.02 dB (amplitude doubles), or +3.01 dB under equal-power gains at the midpoint;
- two different basslines in the same range mask each other and sound muddy.

That's why the **low band is never shared**: it's handed over at a single downbeat (the bass swap below), and only the mid and high bands cross-fade with equal power.

#### 3.3.2 Splitting each deck into bands

Each deck is split with a **4th-order Linkwitz–Riley (LR4) crossover at `f_c = 120 Hz`**. LR4 = two cascaded 2nd-order Butterworth sections (Q = 1/√2) for the low-pass, and the same for the high-pass. The two outputs are −6.02 dB at `f_c`, in phase, and sum to a flat magnitude (an all-pass).

**RBJ biquad (Audio EQ Cookbook):**

```
w0 = 2π·f0/fs,   α = sin(w0)/(2Q)
LPF: b0 = (1 − cos w0)/2,  b1 = 1 − cos w0,     b2 = (1 − cos w0)/2,  a0 = 1 + α,  a1 = −2 cos w0,  a2 = 1 − α
HPF: b0 = (1 + cos w0)/2,  b1 = −(1 + cos w0),  b2 = (1 + cos w0)/2,  a0 = 1 + α,  a1 = −2 cos w0,  a2 = 1 − α
Normalise all by a0.  Difference equation (transposed direct form II):
  y = b0·x + z1;   z1 = b1·x − a1·y + z2;   z2 = b2·x − a2·y
```

**Worked values, fs = 48,000 Hz, f0 = 120 Hz, Q = 0.7071** (computed): `w0 = 0.0157080`, `cos w0 = 0.99987663`, `α = 0.01110675`.

| Section | b0 | b1 | b2 | a1 | a2 |
|---|---|---|---|---|---|
| LP (one of two cascaded) | 6.1006179e-05 | 1.2201236e-04 | 6.1006179e-05 | −1.9777864838 | 0.9780305085 |
| HP (one of two cascaded) | 0.98895424807 | −1.9779084961 | 0.98895424807 | −1.9777864838 | 0.9780305085 |

Measured response of the cascades (LR4), computed:

| Frequency | LR4 low-pass | LR4 high-pass | LP + HP |
|---|---|---|---|
| 30 Hz | −0.03 dB | −48.20 dB | 0.000 dB |
| 60 Hz | −0.53 dB | −24.61 dB | 0.000 dB |
| 120 Hz | −6.02 dB | −6.02 dB | 0.000 dB |
| 240 Hz | −24.61 dB | −0.53 dB | 0.000 dB |
| 1 kHz | −73.72 dB | 0.00 dB | 0.000 dB |

Use 32-bit float state at minimum and 64-bit for the low-pass at 120 Hz. The poles are close to the unit circle (`a2 ≈ 0.978`), and float32 accumulates noise there.

**Deck output with band gains:**

```
deck(t) = g_full(t) · [ g_low(t) · LR4_LP(x)(t) + g_high(t) · LR4_HP(x)(t) ]
```

With `g_low = g_high = 1`, the deck is unchanged in magnitude.

#### 3.3.3 The bass swap

At the swap downbeat `t_sw` (the middle phrase line of the blend), over `T_sw` = 1 beat:

```
u        = clamp((t − t_sw)/T_sw, 0, 1)
g_low_A  = 1 − u        g_low_B  = u                 (linear: the low bands are coherent-ish; amplitude must stay constant)
before t_sw:  g_low_B = 0                            (B enters with its bass removed)
after  t_sw + T_sw:  g_low_A = 0
mid/high: g_high_A = cos(πx/2), g_high_B = sin(πx/2) over the whole blend (equal power)
```

At no moment are both kick drums or basslines at full level. A **hard swap** (`T_sw` = 1 sample, still on the downbeat) is the classic DJ move and is allowed when both songs' downbeat confidence is above 0.7. A soft 1-beat swap is the default.

#### 3.3.4 Kill EQ (alternative to the crossover)

For decks without the split, a low shelf at 120 Hz, Q 0.7071, gain −26 dB acts as a "bass kill". Computed coefficients at 48 kHz:

```
b = [0.9819470899, −1.9535730813, 0.9716799503],   a1 = −1.9530617497, a2 = 0.9541383718
```

Changing shelf gain while audio plays needs coefficient smoothing. Interpolate the **gain in dB** linearly per block of 64 samples and recompute coefficients per block; never jump. Do the same for every automated filter.

#### 3.3.5 Filter-sweep and echo-out transitions

- **Low-pass sweep on A** (for `FALLBACK_DJ`):
  - cutoff `f(t) = f_hi · (f_lo/f_hi)^x`, exponential from 20 kHz to 300 Hz over the blend;
  - resonance Q 0.9;
  - recompute per 64-sample block.
- **High-pass rise on B:** `f(t) = 300 Hz · (20/300)^x`, exponential from 300 Hz to 20 Hz.
- **Echo-out** (when beats can't be matched):
  - a feedback delay on A of one beat `T_b` (or 3/4 beat), feedback 0.55, high-pass at 200 Hz in the loop;
  - send gain 0 → 1 over the last bar;
  - A's dry signal is cut on the downbeat, so the echo tail rings over B's start.

#### 3.3.6 Loudness

- Both decks are first normalised to the same integrated loudness (EBU R128, LUFS, K-weighted). Riff already normalises.
- During the blend, measure the **momentary loudness** (400 ms window) of the mixed output.
- If it exceeds the pre-blend level by more than 1.5 LU, apply a slow trim (time constant 300 ms, max −3 dB) to the mix bus. The existing limiter in `PcmVolumeBoost` remains the final safety.

---

## 4. SDK, library and API comparison

### 4.1 Client-side DSP engines

| Engine | Language and platforms | Real-time latency | Licence | Price (as found) | Fit for Riff |
|---|---|---|---|---|---|
| **Media3 ExoPlayer + AudioProcessor** (Riff today) | Kotlin/Java; Android | Device output latency (typically 20–200 ms; not relevant once mixing is inside one chain) | Apache-2.0 | Free | **Base of the design** |
| Oboe / AAudio | C++; Android | Lowest on Android: exclusive MMAP where available; shared mode mixes streams; use the native sample rate (usually 48 kHz) | Apache-2.0 | Free | Needed only if Riff moved mixing out of Media3 (not planned) |
| Superpowered | C++; iOS, Android, desktop, Web (WASM) | Low | Proprietary; free-tier claims conflict with the 2022 master licence | Contact vendor **[verify]** | Not GPL-compatible to bundle |
| JUCE 8 | C++; desktop, iOS, Android | Low | **AGPL-3.0** or commercial | Starter free (≤ $20 k revenue/funding); Indie $40/month or $800 perpetual (≤ $300 k); Pro $175/month (12-month minimum) or $3,500 perpetual | Heavy framework; AGPL combinable with GPL-3.0 |
| Web Audio API + AudioWorklet | JavaScript/WASM; browsers | 128-frame render quantum (2.67 ms at 48 kHz) plus output latency | W3C standard | Free | For a future web player (section 4.5) |
| BASS (+ BASS_FX for tempo) | C; Windows, macOS, Linux, iOS, Android | Low | Proprietary | Free for non-commercial; shareware €125; single commercial €950 (€475 iOS/Android); unlimited €3,450; BASS_FX terms **[verify]** | Not GPL-compatible to bundle |
| Rubber Band | C++; all | 50–100 ms+ | **GPL-2.0+** or commercial | Free under GPL | **Stretcher option** |
| SoundTouch | C++; all | ~100 ms | LGPL-2.1 | Free | Stretcher option |
| Sonic | C; all | Low | Apache-2.0 | Free | In Media3 already |

### 4.2 MIR analysis libraries

| Library | Language | What it gives | Licence (code / models) | Latency | Fit for Riff |
|---|---|---|---|---|---|
| Essentia | C++ (Python bindings) | Beats, tempo, key, loudness, onsets, HPCP, structure, many more | **AGPL-3.0** or commercial / models **CC BY-NC-ND 4.0** | Offline or streaming | Combinable (AGPL) but heavy; NC models excluded |
| Essentia.js | JavaScript/WASM | Same core in the browser | **AGPL-3.0** (follows Essentia) **[verify]** | Offline in browser | For a web player |
| librosa | Python | Onsets, `beat_track`, tempo, chroma, MFCC, HPSS, segmentation | **ISC** | Offline | Reference and evaluation only |
| madmom | Python (Cython) | RNN/DBN beats and downbeats, tempo, onsets, key | Code **BSD** / models **CC BY-NC-SA 4.0** | Offline (some online processors) | Evaluation only |
| aubio | C | Onsets, tempo, pitch, MFCC | **GPL-3.0** | Real-time capable | Licence-compatible; optional |
| Beat This! | Python/ONNX | Beats and downbeats | **MIT** (code and weights, per secondary sources) **[verify]** | Offline, seconds per song on a phone (int8) | Optional download (Tier 2) |
| allin1 | Python | Tempo, beats, downbeats, section boundaries and labels | **[verify]**; depends on madmom | Offline | Research only |
| Spleeter | Python/TensorFlow | 2/4/5-stem separation | **MIT** | Offline | Evaluation of the vocal heuristic |
| Demucs | Python/PyTorch | 4-stem separation | Code MIT / weights **disputed** **[verify]** | Offline, heavy | Evaluation only |
| **Riff's own analyser** (this report) | Kotlin | Tempo, beats, downbeats, meter check, structure, vocal heuristic, key | GPL-3.0 (Riff's) | ~0.2–0.5 s CPU per song (estimate) | **Ships** |

### 4.3 Cloud audio intelligence APIs

None of these can run a transition in real time: they return metadata over the network. They matter only for DRM sources without PCM, or to cross-check Riff's own analysis.

| Service | What it offers (as found) | Input | Pricing (as found) | Notes |
|---|---|---|---|---|
| Spotify Web API (audio features / analysis) | Was: tempo, key, sections, beats, bars | Spotify ID | — | **Closed to new apps since 27 Nov 2024**; existing extended-mode apps kept access |
| Cyanite.ai | Music tagging and similarity (mood, genre, and more); BPM/key fields not confirmed by these sources **[verify]** | Upload or catalogue | Custom pricing; no free plan (third-party listing, 2024) | GraphQL API per vendor docs **[verify]** |
| Sonovault | Metadata API (ISRC/ISWC, cross-platform IDs, 93 M recordings), audio identification; **sources conflict** on whether BPM/key are provided | ISRC, IDs, audio file | Free tier 1,000 requests/month; radio monitoring €29/station/month | Check the current docs for BPM/key |
| Musicae DJ Track Audio Analysis API | BPM, Camelot and Open Key, mood, nine DJ scores, a beat grid (whether timestamps are exposed is unconfirmed); exports to rekordbox XML, M3U, cue sheet | Spotify ID or ISRC | On RapidAPI and Apify; price per listing **[verify]** | Closest to a Spotify audio-analysis replacement for DJ data |
| RoEx Tonn | AI **mixing, mastering and mix analysis** (not DJ transition analysis) | Audio files and stems | Credits: mastering $2.20 (small plan) / $1.76 (large); multitrack mix $2.50 / $2.00; mix settings only $1.25 / $1.00; processed stems $0.50 / $0.40; 1,000 free credits on sign-up | Useful for loudness/mix diagnostics, not for cue points |
| ReccoBeats | Spotify-style features (tempo, energy, danceability) and feature extraction from uploaded audio | Spotify ID or audio | Free, no key (per a directory) | Coverage reported inconsistent |
| GetSongBPM | Tempo, key, danceability | Title/artist | Free API (key improves coverage) | Reliability reports mixed |
| AcousticBrainz | Was: open audio features | MBID | — | Reported shut down for new data; not for new work |

**Latency** of all cloud options is a network round trip plus queueing: hundreds of ms to seconds. It's fine for **pre-computed** metadata fetched during the previous song, and useless inside the audio callback.

### 4.4 Choice for Riff

| Need | Choice | Why |
|---|---|---|
| Decode, play, process | Media3 (present) | No new dependency |
| Mixing two decks sample-accurately | Capture sink + `DeckMixProcessor` (section 3.1) | Works inside Media3; no Oboe rewrite |
| Stretch | Sonic now; Rubber Band later if needed | Zero cost now; GPL-compatible upgrade |
| Analysis | Own Kotlin analyser | No licence or size cost; tunable |
| Better beats if needed | Beat This! as an optional download | Best accuracy; MIT per sources (verify) |
| Vocals | HPSS heuristic | No model, no licence questions |
| Cloud | None | Riff has the PCM; avoids sending listening data to third parties |

### 4.5 Cross-platform port (for a future desktop or web Riff)

- **Core in C++17:** analyser (FFT via KissFFT, BSD), biquads, LR4, mixer, plan executor. The Kotlin version translates one to one.
- **Android:** JNI from the Media3 `AudioProcessor`, processing float buffers in place, with no allocation in `queueInput`.
- **Web:** Emscripten to WASM inside an `AudioWorkletProcessor`. Decks come from `AudioBufferSourceNode`s, or WASM decoders feeding a `SharedArrayBuffer` ring (the page must be cross-origin isolated: `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp`). The 128-frame quantum keeps automation at 2.67 ms resolution.
- **Python offline pipeline:** librosa and madmom (research only, non-commercial models), used to produce reference annotations and to evaluate the shipped analyser (section 6.1). Never shipped.

---

## 5. Reference implementation and failure handling

### 5.1 Components in Riff

| Component | File (new unless noted) | Responsibility |
|---|---|---|
| `TrackDecoder` | shared with Feature 9 (`TrailingSilenceScanner.kt`) | Decode head/tail windows to float PCM at 22,050 Hz mono for analysis |
| `TrackAnalyzer` | `automix/TrackAnalyzer.kt` | Sections 2.1–2.5; writes `TrackAnalysis` |
| `AnalysisStore` | `automix/AnalysisStore.kt`, a table in `library-state.db` | Cache by video ID and analyser version |
| `TransitionPlanner` | `automix/TransitionPlanner.kt` | Chooses tier, points, lanes; pure function of two analyses and settings |
| `TransitionExecutor` | `automix/TransitionExecutor.kt` | The state machine of section 3.1, in the service |
| `CaptureAudioSink` | `automix/CaptureAudioSink.kt` | Deck B's sink: writes PCM to the ring; reports consumed position |
| `DeckMixProcessor` | `automix/DeckMixProcessor.kt` | In A's chain: pulls B, runs lanes, sums |
| `AutomationLane` | `automix/Automation.kt` | Sample-time curves (gain, cutoff, band gains, rate) |
| Settings | `TransitionSettings.kt` | Off / Crossfade / Smart / AutoMix; max blend; harmonic mode; performance |

### 5.2 Data: JSON schemas

**TrackAnalysis v1** (stored as JSON in `track_analysis.payload`; times in seconds of content time):

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "riff:track-analysis:v1",
  "type": "object",
  "required": ["version", "trackId", "durationS", "analysedRanges", "tempo", "beats", "downbeats",
               "audible", "sections", "mixIn", "mixOut", "vocal", "key", "loudness"],
  "properties": {
    "version":        { "const": 1 },
    "trackId":        { "type": "string", "pattern": "^(yt:[A-Za-z0-9_-]{11}|local:.+)$" },
    "durationS":      { "type": "number", "minimum": 0 },
    "analysedRanges": { "type": "array", "items": { "type": "array", "prefixItems": [{"type":"number"},{"type":"number"}], "minItems": 2, "maxItems": 2 } },
    "tempo": {
      "type": "object", "required": ["bpm", "bpmAlt", "confidence", "drift", "meter", "meterConfidence", "source"],
      "properties": {
        "bpm":             { "type": "number", "minimum": 30, "maximum": 300 },
        "bpmAlt":          { "type": "number", "description": "the other octave (half or double)" },
        "confidence":      { "type": "number", "minimum": 0, "maximum": 1 },
        "drift":           { "type": "number", "minimum": 0, "description": "(max-min)/median of 8 s local tempos" },
        "meter":           { "enum": [3, 4, 5, 6, 7] },
        "meterConfidence": { "type": "number", "minimum": 0, "maximum": 1 },
        "source":          { "enum": ["dsp-v1", "beat-this", "metadata"] }
      }
    },
    "beats":     { "type": "array", "items": { "type": "number" } },
    "downbeats": { "type": "array", "items": { "type": "number" } },
    "downbeatConfidence": { "type": "number", "minimum": 0, "maximum": 1 },
    "audible": {
      "type": "object", "required": ["startS", "endS", "contentEndS"],
      "properties": { "startS": {"type":"number"}, "endS": {"type":"number"}, "contentEndS": {"type":"number"},
                      "falseEndings": { "type": "array", "items": { "type": "object",
                        "required": ["silenceStartS", "silenceEndS"], "properties": {
                        "silenceStartS": {"type":"number"}, "silenceEndS": {"type":"number"} } } } }
    },
    "sections": { "type": "array", "items": { "type": "object", "required": ["startS", "endS", "label", "rms", "lowRms"],
      "properties": { "startS": {"type":"number"}, "endS": {"type":"number"},
                      "label": { "enum": ["intro", "verse", "chorus", "drop", "breakdown", "outro"] },
                      "rms": {"type":"number"}, "lowRms": {"type":"number"} } } },
    "mixIn":  { "type": "array", "items": { "$ref": "#/$defs/cue" } },
    "mixOut": { "type": "array", "items": { "$ref": "#/$defs/cue" } },
    "vocal": { "type": "object", "required": ["hopS", "activity", "threshold"],
      "properties": { "hopS": {"const": 0.1}, "activity": { "type": "array", "items": {"type":"number","minimum":0,"maximum":1} },
                      "threshold": {"type":"number"} } },
    "key": { "type": "object", "required": ["tonic", "mode", "camelot", "confidence"],
      "properties": { "tonic": {"type":"integer","minimum":0,"maximum":11}, "mode": {"enum":["major","minor"]},
                      "camelot": {"type":"string","pattern":"^(1[0-2]|[1-9])[AB]$"}, "confidence": {"type":"number"} } },
    "loudness": { "type": "object", "required": ["integratedLufs", "truePeakDbtp"],
      "properties": { "integratedLufs": {"type":"number"}, "truePeakDbtp": {"type":"number"} } }
  },
  "$defs": {
    "cue": { "type": "object", "required": ["timeS", "type", "score", "phraseBars"],
      "properties": { "timeS": {"type":"number"}, "type": {"enum":["drop","intro_end","outro_start","breakdown","first_downbeat","phrase"]},
                      "score": {"type":"number","minimum":0,"maximum":1}, "phraseBars": {"enum":[0, 4, 8, 16, 32]} } }
  }
}
```

**TransitionPlan v1** (made by the planner; times are **content seconds** of each deck, so the executor converts them to output frames):

```json
{
  "version": 1,
  "outgoing": "yt:AAAAAAAAAAA",
  "incoming": "yt:BBBBBBBBBBB",
  "tier": "BEATMATCHED",
  "reasons": ["both beat grids trusted (0.91, 0.88)", "tempo ratio 1.0323 within 1.04", "keys 8A→9A score 0.90"],
  "cueOutS": 187.42,
  "cueInS": 15.06,
  "blendBeats": 32,
  "rate": { "deck": "B", "target": 0.96875, "rampBeatsBefore": 2, "returnBars": 4 },
  "lanes": [
    { "deck": "A", "param": "gainHigh", "curve": "cos",    "fromBeat": 0,  "toBeat": 32, "from": 1, "to": 0 },
    { "deck": "B", "param": "gainHigh", "curve": "sin",    "fromBeat": 0,  "toBeat": 32, "from": 0, "to": 1 },
    { "deck": "A", "param": "gainLow",  "curve": "linear", "fromBeat": 16, "toBeat": 17, "from": 1, "to": 0 },
    { "deck": "B", "param": "gainLow",  "curve": "linear", "fromBeat": 16, "toBeat": 17, "from": 0, "to": 1 },
    { "deck": "A", "param": "lowpassHz","curve": "exp",    "fromBeat": 24, "toBeat": 32, "from": 20000, "to": 2000 }
  ],
  "fallback": { "tier": "DJ_ASSISTED", "blendBeats": 16 }
}
```

`rate.target` 0.96875 means deck B (128 BPM) is played at 124/128 of its speed during the blend. That is a **slow-down**, which the rule in section 3.2.4 allows only when the outgoing track is the faster one and the alternative (speeding up A) would cut into A's final bars. Otherwise the planner speeds up the slower track: here, A to 128/124 = 1.0323 over its last 2 beats before the blend.

### 5.3 Executor and scheduling

```kotlin
internal const val PARAM_GAIN_FULL = 0; internal const val PARAM_GAIN_LOW = 1; internal const val PARAM_GAIN_HIGH = 2
internal const val PARAM_LOWPASS_HZ = 3; internal const val PARAM_HIGHPASS_HZ = 4; internal const val PARAM_COUNT = 5
internal const val CURVE_LINEAR = 0; internal const val CURVE_SIN = 1; internal const val CURVE_COS = 2; internal const val CURVE_EXP = 3
private const val HALF_PI = (Math.PI / 2).toFloat()

/** One automation lane, already converted from beats to frames on the OUTPUT timeline. */
internal class CompiledLane(val deck: Int, val param: Int, val curve: Int,
    val startFrame: Long, val endFrame: Long, val from: Float, val to: Float)

/** Runs in DeckMixProcessor.queueInput on the audio thread: no allocation, no locks, no I/O. */
internal class LaneRunner(private val lanes: Array<CompiledLane>) {
    /** Unity gains; filters open (low-pass at 20 kHz, high-pass at 10 Hz). */
    private val defaults = floatArrayOf(1f, 1f, 1f, 20_000f, 10f)

    fun valueAt(deck: Int, param: Int, frame: Long): Float {
        var v = defaults[param]
        // Later lanes for the same parameter win once they have started; earlier ones hold their end value.
        for (lane in lanes) if (lane.deck == deck && lane.param == param && frame >= lane.startFrame) {
            val span = (lane.endFrame - lane.startFrame).coerceAtLeast(1L)
            val x = if (frame >= lane.endFrame) 1f else (frame - lane.startFrame).toFloat() / span
            v = when (lane.curve) {
                // from·(to/from)^x: equal ratios per unit time, which is how cutoff sweeps must move. Needs from, to > 0.
                CURVE_EXP -> lane.from * Math.pow((lane.to / lane.from).toDouble(), x.toDouble()).toFloat()
                // sin rises 0→1 with equal-power shape; with from=1,to=0, COS yields cos(πx/2).
                CURVE_SIN -> lane.from + (lane.to - lane.from) * kotlin.math.sin(x * HALF_PI)
                CURVE_COS -> lane.from + (lane.to - lane.from) * (1f - kotlin.math.cos(x * HALF_PI))
                else -> lane.from + (lane.to - lane.from) * x
            }
        }
        return v
    }
}
```

For deck A's equal-power fade, the plan's lane `{param: gainHigh, curve: "cos", from: 1, to: 0}` gives `1 + (0 − 1)·(1 − cos(πx/2)) = cos(πx/2)`. Deck B's `{curve: "sin", from: 0, to: 1}` gives `sin(πx/2)`. Together they're the equal-power pair of section 3.3.1.

**Per audio block** (Media3 hands buffers of about 10–20 ms):
1. Split the block into **64-frame sub-blocks**. For each, read lane values at the sub-block start, and update filter coefficients only if a value changed by more than 0.01 dB or 0.1 % in frequency.
2. Pull 64 frames of B from the ring at `readIndex = n_out − n_start`. If fewer frames are available (an underrun), mix silence for B, raise an `underrun` flag, and let the executor go to `ABORTED` at the next control tick.
3. `out = A·g_A + B·g_B`, per band as in section 3.3.2, then the loudness trim (section 3.3.6).

**Control thread** (the service's main looper, every 50 ms):
- moves the state machine;
- converts the plan's beats to output frames once per state change (`frame = n_out_at_cueA + (beatTime_A(k) − cueOutS) · fs / r_A`);
- swaps a new `CompiledLane[]` into the processor with an atomic reference. The audio thread only reads.

**Beat-to-frame conversion** under a tempo ramp: for a linear rate ramp `r(t) = r0 + (r1 − r0)·t/T` over `T` output seconds, content advanced is `∫r dt = T·(r0 + r1)/2`. The planner chooses the ramp start so that A's cue beat lands exactly on the computed frame, solving that integral for the ramp start.

### 5.4 The planner: choosing the tier

```
inputs: analysis A (outgoing), B (incoming), settings, context (queue source, item kinds)

0. Not eligible → NONE (Riff's normal behaviour):
     podcast/episode/live; album played in order; gapless pair (crossfadeSkipsItem);
     repeat-one; last item; sleep timer active; either song shorter than 60 s;
     Automix off for this source (default: on for playlists, radio, autoplay; off for albums, as Apple does).
1. Fold tempos: candidates for ratio ρ = bpmB/bpmA × {1, 2, 1/2, 3/2, 2/3}; pick the one closest to 1.
     Δ = |ρ − 1|
2. Evidence: cA = A.tempo.confidence, cB = B.tempo.confidence, dA = A.downbeatConfidence, dB = …
     metadata-only tempo ⇒ c = 0.
3. Tier:
     BEATMATCHED  if min(cA,cB) ≥ 0.6 and min(dA,dB) ≥ 0.5 and Δ ≤ 0.04 and meters equal (4/4 or 3/4)
                     and both drift ≤ 0.02
     DJ_ASSISTED  else if min(cA,cB) ≥ 0.35 and min(dA,dB) ≥ 0.3
     PLAIN        otherwise
4. Points: best-scoring mixOut on A whose remaining music after it ≤ 32 bars and ≥ blend length,
     and best mixIn on B; enforce vocal rule (section 2.4) and key-based max overlap (section 2.5).
     Never discard more than 15 % of A's remaining music (BitChord's rule, adopted).
5. Length: BEATMATCHED 16–32 beats; DJ_ASSISTED 8–16; PLAIN = the Crossfade setting (1–12 s).
6. Lanes: per tier (BEATMATCHED: equal-power mids/highs, bass swap at the middle phrase line,
     optional LP on A's last 8 beats; DJ_ASSISTED: same minus rate; PLAIN: equal-power full band).
```

### 5.5 Edge cases and fallback heuristics

| Case | Detection | Action |
|---|---|---|
| **BPM delta > 4 %** after octave folding | Δ from step 1 | No stretch → `DJ_ASSISTED`: start B on A's downbeat; 8–16 beat blend; LP sweep on A |
| **BPM delta > 15 %** | Δ > 0.15 | No beat relation is attempted. **Echo-out** (section 3.3.5) on A's last downbeat, or an 8 s equal-power fade at energy-chosen points |
| **Half-time / double-time** (174 vs 87 BPM) | ρ folding picks ×2 | Treat as Δ = 0, match on every other beat of the faster song; bars counted at the slower song's tempo |
| **Triplet relation** (120 vs 180) | ρ = 3/2 within 4 % | `DJ_ASSISTED` only (no stretch); align the bar start, not the beat |
| **Rubato, live drummer, tempo drift** | `confidence < 0.35` or `drift > 0.02` | `PLAIN` at energy-based points (outro start, intro end); never phase-align |
| **5/4 vs 4/4 (or 7/8, 6/8)** | `meter` differs and both `meterConfidence > 0.5` | Never overlap more than **one bar of A's meter**: `DJ_ASSISTED` with 1-bar blend starting on A's downbeat, or a cut on the downbeat with a 50 ms equal-power bridge |
| **No beats at all** (ambient, spoken word, classical) | `confidence < 0.1` | `PLAIN`, length from the user's crossfade setting; quiet-intro skip still allowed |
| **False silence ending** (silence then more music or a hidden track) | `audible.falseEndings` non-empty: a gap ≥ 2 s below −50 dBFS followed by ≥ 5 s of audio above −35 dBFS | If the music after the gap is ≥ 30 s, it's a hidden track: transition after it (or not at all, if it runs to the end). If < 30 s, use `contentEndS` (the last music before the gap) as the end, and ignore the remainder |
| **Applause or crowd at the end** (live recordings) | High spectral flatness (> 0.5), no beats, RMS still above −35 dBFS | Treat as outro: start the fade at its onset |
| **Fade-out ending** (long studio fade) | RMS slope negative and steady over the last 20–40 s | Mix out where RMS has fallen 6 dB below the song median; the fade does the outgoing curve's work, so B ramps in faster (sin curve over 60 % of the window) |
| **Cold ending** (abrupt stop on a hit) | RMS drops > 30 dB within 100 ms at the end | Don't fade A at all: start B **on** the final hit's downbeat with a 2-beat fade-in, or after it with no gap |
| **Very long intro on B** (> 32 bars of low energy) | Section labels | Enter at `intro_end` instead of the start, if the skipped part has no vocals |
| **Key clash** | Camelot score < 0.6 | Max overlap 2 bars; prefer filter or echo transitions |
| **Vocal overlap** unavoidable | Section 2.4 rule fails | Duck B's mids −9 dB until A's vocal ends; else ≤ 1-bar blend |
| **Analysis missing or late** | No `TrackAnalysis` for A's tail or B's head by `t_preload` | `PLAIN` with Riff's existing crossfade |
| **B not buffered** (slow network) | Ring < blend + 10 s at `t_arm` | `FALLBACK_FADE` short (4 s) at A's natural end; never stall A |
| **User action during a transition** | Seek, skip, pause, queue edit | `ABORTED` (section 3.1): restore A over 50 ms, or start B immediately if the user skipped |
| **Speed setting ≠ 1×** (Feature 15) | Player speed | Automix off; plain crossfade |
| **Casting** (Feature 19) | Cast session | Automix off (mixing runs on the phone) |
| **Thermal or battery saver** | `PowerManager` | Analysis paused; existing analyses still used; `BEATMATCHED` downgraded to `DJ_ASSISTED` (no stretcher) |

---

## 6. Evaluation, delivery plan and risks

### 6.1 Evaluation

**Analysis accuracy, on a 50-song set of the owner's own files** across genres, with beats and downbeats tapped once by hand and checked against the Python reference pipeline (section 4.5):

| Measure | Definition | Target to ship BEATMATCHED |
|---|---|---|
| Tempo Acc1 | Within ±4 % of annotated BPM | ≥ 85 % on steady-beat genres |
| Tempo Acc2 | Acc1, also allowing ×2, ×½, ×3, ×⅓ | ≥ 95 % |
| Beat F-measure | Beat within ±70 ms of annotation | ≥ 0.85 on steady-beat genres |
| Downbeat F-measure | Downbeat within ±70 ms | ≥ 0.70 |
| Key accuracy | Exact; plus "relative/fifth" counted as 0.5 (MIREX weighted) | ≥ 0.65 weighted |
| Vocal heuristic | Frame accuracy against separation (Spleeter) | ≥ 0.80 |

**Engine accuracy, measured on the phone with click tracks:**
- **Alignment:** cross-correlate A and B onsets in the mixed output over the blend. Target |error| ≤ 2 ms with the capture-sink mixer, and ≤ 10 ms with the two-player fallback.
- **Loudness continuity:** short-term loudness (3 s) during the blend within ±1.5 LU of the pre-blend level.
- **CPU:** analysis per song ≤ 0.5 s on the owner's phone; mixer ≤ 5 % of one core during a blend.

**Listening test:** 30 song pairs from the owner's playlists, each rendered as:
1. Riff's current crossfade;
2. PLAIN with smart points;
3. DJ_ASSISTED;
4. BEATMATCHED (where the pair qualifies).

Played blind in random order, each rated 1–5. A tier ships when its mean beats the current crossfade by ≥ 0.5 and none of its renders scores 1.

### 6.2 Delivery plan

| Stage | Contents | Depends on | Size |
|---|---|---|---|
| A | Decoder (shared with silence skip), analyser v1 (sections 2.1–2.3, 2.5), `AnalysisStore` | Feature 9 decoder | Medium |
| B | Planner with `PLAIN` smart points + equal-power + LR4 bass swap, on Riff's existing two-player crossfade | A | Medium |
| C | Capture sink + `DeckMixProcessor` + lanes; `DJ_ASSISTED` | B | Large |
| D | `BEATMATCHED` with Sonic; evaluation on the 50-song set | C | Medium |
| E | Vocal heuristic; key-limited overlaps; echo and filter transitions | C | Medium |
| F (optional) | Rubber Band; Beat This! as a download | D, measurements | Medium |

**Size** (Riff's APK is 9.4 MB): stages A–E are Kotlin only, about +150–300 KB. Rubber Band adds about 0.5–1 MB (arm64). Beat This! as a download adds nothing to the APK; on the phone, ONNX Runtime plus the model is about 11 MB.

### 6.3 Risks

| Risk | Effect | Mitigation |
|---|---|---|
| Media3 sink swap at handoff is fiddly | Audible glitch at the end of each blend | 20 ms gain bridge; swap only at a song boundary; JVM tests with a fake sink |
| Analysis wrong on some genres | Train-wreck transitions | Confidence gates; tiers fall back; owner's test set before shipping each tier |
| CPU and battery | Heat on long sessions | Analyse once per song (cached); low priority; pause under battery saver |
| Model licences (if used) | Can't ship in GPL app | Use only MIT-verified models; none by default |
| YouTube changes streams | Analysis needs PCM, which Riff already has | Same exposure Riff has today; AutoMix adds no new dependency on YouTube |
| Legal position | Modifying audio from a source Riff isn't licensed by | Unchanged from today (crossfade, boost and normalisation already do this); AutoMix stays on the phone and doesn't store or share audio |

---

## 7. Sources

**Products:**
- Apple AutoMix: [9to5Mac](https://9to5mac.com/2025/06/16/automix-apple-music-ios-26/), [MusicTech](https://musictech.com/news/gear/apple-music-automix-ai/), [iGeeksBlog](https://www.igeeksblog.com/apple-music-automix-in-ios-26/)
- Spotify Mix: [TechCrunch](https://techcrunch.com/2025/08/19/spotifys-latest-feature-lets-you-add-your-own-transitions-to-playlists), [RouteNote](https://routenote.com/blog/spotify-mix-customizable-transitions/), [Gear Patrol](https://www.gearpatrol.com/audio/spotify-playlist-mix-eliminate-transitions/), [MakeTechEasier](https://maketecheasier.com/spotify-mix-dj-for-playlists/)
- djay: [Automix help (Android)](https://help.algoriddim.com/user-manual/djay-android/settings/automix), [Using Automix (Mac)](https://help.algoriddim.com/user-manual/djay-pro-mac/mixing-basics/using-automix), [MusicTech on Neural Mix](https://musictech.com/news/gear/algoriddim-free-dj-software-djay-pro-ai-automix-and-neural-mix), [DJ Mag](https://djmag.com/news/new-djay-ai-ios-adds-improved-ai-mixing)

**APIs and terms:**
- Spotify: [Changes to the Web API (27 Nov 2024)](https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api), [TechCrunch](https://techcrunch.com/2024/11/27/spotify-cuts-developer-access-to-several-of-its-recommendation-features/)
- MusicKit: [clause sample](https://www.lawinsider.com/clause/musickit), [developer forum on EQ](https://developer.apple.com/forums/thread/799647)
- YouTube API terms as reported in 2012: [9to5Google](https://9to5google.com/2012/06/19/report-google-warns-legal-action-against-youtube-mp3-org-blocks-websites-access-to-youtube/)
- EME and Web Audio: [W3C public-audio thread](https://lists.w3.org/Archives/Public/public-audio/2014JanMar/0032.html), [web.dev EME](https://web.dev/articles/media-eme)

**MIR:**
- [Beat This! paper](https://arxiv.org/pdf/2407.21658), [Beat This! GGUF (licence notes)](https://huggingface.co/cstr/beat-this-GGUF), [BeatFM comparison](https://arxiv.org/pdf/2508.09790)
- madmom: [downbeats docs](https://madmom.readthedocs.io/en/v0.16/modules/features/downbeats.html), [paper](https://bpb-us-e1.wpmucdn.com/wp.nyu.edu/dist/2/2294/files/2016/08/b%C3%B6ck-madmom.pdf), [PyPI licence note](https://pypi.org/project/madmom/0.14.1)
- Essentia: [RhythmExtractor2013](https://essentia.upf.edu/reference/std_RhythmExtractor2013.html), [beat detection tutorial](https://essentia.upf.edu/tutorial_rhythm_beatdetection.html), [licensing](https://essentia.upf.edu/licensing_information.html), [Essentia.js](https://essentia.upf.edu/essentiajs), [TU Delft evaluation](https://repository.tudelft.nl/file/File_ba1d57c5-b63b-4169-8f3c-f966d72ab3b5)
- [aubio](https://aubio.org/), [allin1](https://pypi.org/project/allin1)
- Separation: [Spleeter JOSS paper](https://joss.theoj.org/papers/10.21105/joss.02154.pdf), [HTDemucs weights licence discussion](https://huggingface.co/adefossez/HTDemucs/discussions/1), [open-unmix](https://pytorch.org/hub/sigsep_open-unmix-pytorch_umx/), [UMX-HQ ONNX](https://huggingface.co/edgetools/umx-hq)
- DJ research: [Automatic DJ transitions with differentiable effects and GANs (ICASSP 2022)](https://arxiv.org/pdf/2110.06525), [cue-point detection (Computer Music Journal)](https://direct.mit.edu/comj/article/46/3/67/117159/Automatic-Detection-of-Cue-Points-for-the), [M-DJCUE dataset](https://github.com/MZehren/M-DJCUE), [cue points as object detection](https://arxiv.org/html/2407.06823v1), [DJ mix analysis](https://mir-aidj.github.io/djmix-analysis/)

**DSP engines:**
- [Rubber Band licence](https://breakfastquay.com/rubberband/license.html), [Sonic](https://gitcode.com/openharmony/third_party_sonic/blob/master/doc/index.md), [SoundTouch](https://allstar.jhuapl.edu/repo/p4/i386/soundstretch/doc/README.html)
- Superpowered: [licensing](https://superpowered.com/licensing), [Android engine](https://superpowered.com/superpowered-android-audio-engine)
- JUCE: [JUCE 8 EULA](https://forum.juce.com/t/amendments-to-the-juce-end-user-licence-agreement-for-juce-8/61265), [revenue limits](https://forum.juce.com/t/revenue-limits-for-juce-tiers/61058)
- [BASS](https://www.un4seen.com/bass.html), [Oboe low-latency guide](https://developer.android.com/games/sdk/oboe/low-latency-audio)

**Cloud APIs:**
- [Cyanite (SaaSworthy listing)](https://www.saasworthy.com/product/cyanite-ai/pricing)
- Sonovault: [site](https://sonovault.now/), [Spotify comparison](https://sonovault.now/compare/spotify-api)
- Musicae: [API](https://api.musicae.io/), [RapidAPI](https://rapidapi.com/musicae-musicae-default/api/dj-track-audio-analysis-api/playground), [Apify](https://apify.com/musicae/dj-track-audio-analyzer/api)
- RoEx Tonn: [pricing](https://tonn-portal.roexaudio.com/pricing), [FAQ](https://tonn-portal.roexaudio.com/docs/faq)
- Spotify alternatives: [overview](https://www.musiciwant.com/studio/spotify-audio-features-alternative), [ReccoBeats](https://providers.apievangelist.com/providers/reccobeats/)

**Methods cited by name** (standard literature; equations reproduced above): Ellis, "Beat Tracking by Dynamic Programming" (JNMR 2007); Foote, "Automatic Audio Segmentation Using a Measure of Audio Novelty" (ICME 2000); Fitzgerald, "Harmonic/Percussive Separation Using Median Filtering" (DAFx 2010); Krumhansl & Kessler key profiles (1982); Verhelst & Roelands, WSOLA (ICASSP 1993); Laroche & Dolson, "Improved Phase Vocoder Time-Scale Modification of Audio" (IEEE TSAP 1999); Bristow-Johnson, "Audio EQ Cookbook"; Linkwitz–Riley crossovers (JAES 1976); EBU R128 / ITU-R BS.1770 loudness.
