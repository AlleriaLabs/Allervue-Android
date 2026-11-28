# Allervue 1.4.0 adaptive release validation

## Automated CI coverage
- Run `testDebugUnitTest` before `assembleDebug`.
- Adaptive profile tests cover compact phone, tablet, resizable narrow window, TV, and invalid dimensions.
- APK output must exist and be non-empty before the workflow uploads it.

## Physical-device acceptance checklist

### Android phone / Android 10
- [ ] Install, launch, sign in, and navigate all primary tabs.
- [ ] Verify portrait and landscape transitions where supported.
- [ ] Open a movie and series episode; test play, pause, seek, subtitles, audio selection, and resume.
- [ ] Exercise low-memory behavior by switching apps during playback and returning.
- [ ] Confirm no layout clipping, crashes, or ANRs during repeated navigation.

### Android tablet (including Huawei MatePad / Android 10)
- [ ] Verify navigation rail, catalog grid, poster shelves, and Continue Watching sizing.
- [ ] Rotate between portrait and landscape; resize/split-screen if supported.
- [ ] Open detail, folder, search, settings, and player screens.
- [ ] Confirm no inverted constraints, clipped dialogs, or off-screen controls.
- [ ] Play a long 1080p stream and monitor decoder stability, dropped frames, and memory pressure.

### Android TV
- [ ] Launch from the Leanback launcher.
- [ ] Navigate every primary destination with D-pad only.
- [ ] Confirm visible focus at all times and correct Back behavior.
- [ ] Test playback controls with D-pad center/up/down, seek keys, and media keys.
- [ ] Verify text legibility and safe margins at normal viewing distance.

## Release gate
A successful CI run verifies JVM regression tests and APK compilation only. Mark physical compatibility complete only after the device checklist above has been executed on actual hardware. Record device model, Android version, stream format/resolution, decoder, and any crash or dropped-frame diagnostics with each test.
