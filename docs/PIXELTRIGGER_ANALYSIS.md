# PixelTrigger visual monitor: APK evidence and GREAT adaptation

Analyzed input: `PixelTrigger.apk` extracted from the supplied `PixelTrigger.apk(4).zip`.
SHA-256: `4112509cdf785f63fc808a286b9a32a35af8df378c72cfa5e91c959b3ad5939b`.
JADX 1.5.3 was used to inspect the actual DEX. Raw fallback instructions were
checked for `PixelSampler.sampleCircularRegion` and `ScreenCaptureService.sampleSensor`:
the former uses floating-point white/dark ratios, and the latter uses floating-point
screen-to-crop scaling. Decompiled Java alone incorrectly makes some divisions look integral.
The APK and full decompiled application are not included in this repository.

## Two distinct paths, not interchangeable

The requested `ManualNubiaPairController.processMonitorFrame` calls the shared sampler,
then `isMonitorOnColor`. This returns true when `ColorSample.isArmingWhite()` is true **or**
average RGB lies in inclusive ranges R=237..255, G=208..244, B=102..146. It changes
`monitorWhite` on a single frame and posts `applyState` to the UI handler. It does not
use the right circle's arm/fire state machine. Its 120 ms minimum firing interval is
inside the manual touch handler, not a visual debounce. Its trigger view is 13 mm;
the sample diameter is 0.3 mm and its centre hole is nominally 1.2 mm.

The user selected the **right-hand firing circle**, after being informed of this
difference. GREAT therefore implements `ScreenCaptureService.processRightFrame` →
`sampleSensor` → `PixelSampler` → `DetectionEngine.updateTriggerState` with the same
right-detector configuration (white rearm enabled, timed rearm disabled). The yellow
exception and manual tap/shoulder actuation are intentionally outside this selected path.

## Actual color and sampling rules

| Item | Observed behavior |
| --- | --- |
| Input | Plane 0 RGB bytes at offset `buffer.position + y*rowStride + x*pixelStride`; alpha ignored |
| Luminance | Integer `(54*R + 183*G + 19*B) >> 8` |
| Chroma | `max(R,G,B) - min(R,G,B)`; no HSV, Lab or gamma conversion |
| White pixel | Luminance ≥190 AND minimum RGB channel ≥170 AND chroma ≤60 |
| White sample | White probe count / valid probe count ≥0.5 (floating-point ratio) |
| Sample diameter | Fixed 0.3 mm; round(mean(valid xdpi, valid ydpi)*0.3/25.4), at least 1 screen pixel |
| DPI fallback | Each axis must be finite and in 100..1000 inclusive; otherwise use densityDpi |
| Probe plan | Centre, left, right, up, down; at most 5 points, in that order |
| Probe spacing | `max(1,floor(radiusX/Y))`; include only points inside the normalized ellipse |
| Tiny regions | Radius <1 can exclude side probes, leaving one centre pixel; minimum radius 0.5 capture pixels |
| Crop edges | Skip out-of-crop points and invalid byte offsets; compact valid probes in order |
| Caching | Reuse probe offsets when the float radius bits are unchanged |

`isHoldingWhite` (0.35 coverage) and `isFireDark` (0.45 dark coverage) exist in the APK,
as do average-white constants and older change constants. **The chosen live state
path does not consult them.** Copying constants by name would produce the wrong detector.
The sampler calculates dark pixels using luminance ≤88, maximum channel ≤118 and
chroma ≤72, but that dark ratio is not the live fire condition either.

## Right-circle transitions

1. Start in WAITING_FOR_WHITE. Dark at startup does not fire.
2. Three consecutive white samples arm. A nonwhite sample or changed probe count
   restarts this sequence. Reference probes are per-channel integer averages over
   those three frames, not the last white frame.
3. While ARMED, a probe departs if any channel differs by ≥18, or luminance drops
   by ≥12, or it changes from white to nonwhite under the exact pixel rule above.
   Compare the minimum of current/reference probe counts. Quorum: 3 for five,
   2 for three/four, 1 for one/two.
4. Fire on the first sample satisfying **both** departure quorum and average
   sample luminance ≤90. Merely becoming gray/yellow/nonwhite is insufficient.
5. WAITING_REARM ends after three consecutive white samples. There is no timer
   delay in this configured path. GREAT names this state TRIGGERED and holds its
   monitor Freeze source continuously until rearming.

The engine also has a predictive fallback for samples with no probes: coverage drop
≥0.15, or luminance drop ≥18 with minimum-channel drop ≥14, or chroma rise ≥24 with
luminance drop ≥8; the ≤90 fire gate still applies. The actual `PixelSampler` always
returns 1..5 probes or null, so this fallback cannot be reached through this selected
capture path. GREAT keeps that invariant instead of adding an unused synthetic-sample API.
The optional timed/manual override is likewise inactive for the selected right path.

## Capture, geometry and speed

`ScreenCaptureService` uses RGBA_8888 `ImageReader(maxImages=2)`, `acquireLatestImage`,
and closes every acquired image even on exceptions. It processes available frames
on `HandlerThread("PixelTriggerCapture", -8)`. Width, height and density are each
rounded to 50% of the screen values. The virtual display uses AUTO_MIRROR (16).
There is no requested fixed FPS and no per-frame polling delay in this path. The
actual rate is determined by delivered MediaProjection frames and device workload.
No claim of a guaranteed reaction time in milliseconds follows from this code.

The screen centre is mapped into `Image.cropRect`, rounded and clamped to the crop.
Radii scale independently by crop/screen dimensions. Android produces the rotated
capture; there is no raw touchscreen or finger coordinate transformation. On display
dimension changes, PixelTrigger refreshes geometry (posted after 16 ms), replaces
the reader, resizes the existing virtual display, swaps its surface, closes the old
reader and resets the right detectors. Its orientation position store saves separate
portrait/landscape positions and projects normalized centres across dimensions.

The detector/sampler do not invoke JNI. The APK's `libpixeltrigger_shoulder.so` is
loaded by `input.ShoulderInputUserService` for native init/key down/key up/reset/status,
downstream shoulder actuation. No resources, model files or native calls participate
in the selected RGB classification or right-circle state transitions. The two circle
views draw their graphics programmatically.

## GREAT integration and deliberate presentation changes

- Same live RGB rules, cross probes, 0.3 mm sampling, half-resolution capture,
  urgent-display capture thread and latest-image processing. No full-screen Bitmap
  allocation or image encoding. Screen pixels remain in memory and are not saved or sent.
- One ring: amber while initially waiting, green when armed, red while triggered.
  Ring diameter is adjustable 12..48 dp; it never alters the 0.3 mm sample diameter.
- PixelTrigger's right view draws a one-pixel ring on the sampling boundary. GREAT
  moves the visual stroke outside the ROI, leaving the whole centre transparent and
  a ≥5 dp radial clearance (including space for the capture scaling filter). No fill,
  crosshair or text is drawn over probes while monitoring. Edit-only markers are removed
  before sampling resumes. `FLAG_SECURE` is not used: capture blackouts would corrupt the sample.
- `FLAG_NOT_TOUCHABLE` and window opacity ≤0.6 / the platform's touch-obscuring maximum
  allow original physical touches through during monitoring. Dragging is enabled only
  in explicit Edit mode, where the detector is paused and its Freeze source released.
- Persist portrait/landscape positions and ring size. Obtain fresh capture consent for
  each new session; runtime ON state and consent tokens are not silently restored.
- Register MediaProjection callbacks before creating the display, run the required
  mediaProjection foreground service, reuse the virtual display on resize, and stop on
  revocation/screen-off. Full-display capture is requested on Android 14+. A captured
  app region with incompatible dimensions is rejected rather than guessing its origin.
- Serialize edit/stop/layout invalidation with frame decisions. Stale reader generations
  cannot turn Freeze back on after a geometry reset. Actual overlay screen coordinates
  are read after layout. Rotation also resets the detector, including 180° rotations.
- The capture thread updates the existing independent `FreezeCore.setHoldTrigger`
  source directly. Effective Freeze remains `manual OR monitor`; green/stop affects
  only the monitor source. The manual button's state/timer, UDP rules, target ownership,
  queue capacity and packet release behavior are unchanged.

Other overlays or game menus placed over the sample centre are part of the captured
scene. Do not place GREAT's control panel over the sensor. Protected/secure game
surfaces cannot be captured through ordinary MediaProjection; no bypass is attempted.

## Verification

GitHub Actions runs the full unit suite and assembles the APK. Added cases exercise
white boundaries, floating coverage, probe quorum, three-frame averaged baseline,
single-frame dark firing, interrupted rearm, malformed/strided/cropped buffers, DPI
fallback, geometry rounding and manual/monitor independence including manual timeout.

Device validation still required: screen-capture consent on the user's Android version,
rotation with cutouts and navigation modes, centre alignment on actual captured frames,
touch pass-through inside the ring, no self-sampling, and latency under the target game.
CI success validates build and rules; it does not constitute on-device verification.
