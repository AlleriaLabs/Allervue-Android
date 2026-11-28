# Allervue 1.4.0 release checklist

This checklist is the release gate for the 1.4.0 line. A successful CI build is necessary, but it does not replace hands-on playback and device validation.

## Build and source quality

- [x] JVM regression tests run before APK compilation in GitHub Actions.
- [x] Debug APK output is checked for existence and non-zero size.
- [x] CI artifact is uploaded as `Allervue-debug`.
- [x] Playback track selection has regression tests for stream ID, language, title, and unmatched tracks.
- [x] Adaptive device classification and recommendation ranking have JVM regression coverage.
- [ ] Run a final clean build from the exact release commit.
- [ ] Review the complete diff and resolve all known release-blocking defects.

## Functional acceptance

- [ ] Sign in, sign out, server URL editing, and account switching.
- [ ] Browse Movies, Series, Anime, Library, Search, and My List.
- [ ] Verify Continue Watching and playback progress synchronization.
- [ ] Verify personalized recommendations with a populated watch history and with a new account.
- [ ] Verify audio and subtitle selection against files with multiple tracks.
- [ ] Verify subtitle appearance: white text with subtle dark shadow.
- [ ] Verify direct play, transcoding fallback, and alternate playback engine behavior.
- [ ] Verify downloads, PiP, playback resume, and network interruption recovery.
- [ ] Verify update notification behavior and diagnostic logging.

## Device acceptance

### Phone
- [ ] Install and launch on a supported Android phone.
- [ ] Test portrait/landscape navigation and player controls.
- [ ] Complete a sustained 1080p playback test.

### Tablet
- [x] Huawei MatePad AGS6-W09 / Android 10 launch and basic app test reported successful.
- [ ] Complete adaptive layout, split-screen/resize, and sustained playback checks.

### Android TV
- [ ] Install and launch from the TV launcher.
- [ ] Navigate every primary screen with D-pad only.
- [ ] Verify visible focus, selection, Back behavior, and playback controls with a remote.
- [ ] Verify safe margins and text readability at normal viewing distance.

## Distribution and signing

- [x] Debug APK is available from GitHub Actions for testing.
- [ ] Configure protected release-signing credentials in repository Actions secrets.
- [ ] Build and verify a signed release APK or Android App Bundle.
- [ ] Verify package ID, version code/name, signing certificate, and upgrade path.
- [ ] Decide distribution channel and publish notes.
- [ ] Keep signing keys and passwords out of source control and build logs.

## Current release decision

**Status: Release candidate not yet approved.**

The latest Phase 4 CI run passed and uploaded a debug APK. The app is still versioned `1.4.0-beta01`; Android TV physical remote validation and release signing/distribution setup remain outstanding. Do not describe the current debug artifact as a production release.
