# insta-web-shell

Personal Android app that runs a self-contained website (`app/src/main/assets/index.html`) as the entire app, fullscreen and edge-to-edge in a Chrome-based WebView, styled with Instagram branding for private testing.

## Make it yours

1. Replace `app/src/main/assets/index.html` with your own self-contained page.
2. Commit and push the change (or edit the file directly on GitHub).
3. Open the **Actions** tab, select **Build Debug APK**, and click **Run workflow**.
4. When the run finishes, open it and download the `instagram-debug-apk` artifact, unzip it, and install the APK on your device.

## Notes

- Debug-signed and intended for personal testing only. Not affiliated with Instagram.
- The WebView runs JavaScript, DOM storage, alerts/prompts/confirms, file pickers, camera/microphone/geolocation, downloads, fullscreen video, and pinch zoom, and it never lays out content behind the notch or system bars.
- The APK is padded to roughly 70 MB by a filler asset generated at build time (`build/generated/padAssets/pad.bin`); it is never committed to git.
- Requires Android 7.0 or newer.
