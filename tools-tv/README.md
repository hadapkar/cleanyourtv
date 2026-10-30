# Xiaomi MiTV-AESP0 install notes (this fork only)

Built on GitHub Actions (`.github/workflows/tv-build.yml`), installed with `adbtv.js` because the
work laptop has no adb or Python. Reviewed upstream commit: `718f98c9`.

Stock launcher, Xiaomi PatchWall (`com.mitv.tvhome.atv`) and Monet stay enabled. Do not use the
upstream install script's default, which disables the stock launcher.

```
H=<tv-ip>
node tools-tv/adbtv.js $H pull-state tv-state-before.txt          # save the package state first
node tools-tv/adbtv.js $H install tvhome-standard.apk
node tools-tv/adbtv.js $H shell "pm disable-user --user 0 com.google.android.tvlauncher"
node tools-tv/adbtv.js $H shell "am start -W -n com.gathin.tvhome/.MainActivity"
node tools-tv/adbtv.js $H shell "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" \
                                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
```

If Home still resolves to Xiaomi, use the `allowlist-com.ts.launcher` APK instead (check
`pm path com.ts.launcher` is empty first) and the component `com.ts.launcher/com.gathin.tvhome.MainActivity`.

## Roll back (stock launcher)
```
node tools-tv/adbtv.js $H shell "pm enable --user 0 com.google.android.tvlauncher" "cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity" "input keyevent KEYCODE_HOME"
```
To go back to Monet instead: `set-home-activity --user 0 com.klevico.monet/.HomeActivity`. Remove the launcher with
`pm uninstall --user 0 com.gathin.tvhome`. On Git Bash set `MSYS_NO_PATHCONV=1` so /data paths are not rewritten.

## Full setup (launcher, weather, discover, Jarvis)

All commands from the repo root, `MSYS_NO_PATHCONV=1` on Git Bash. `B` is a downloaded build folder.

```
node tools-tv/adbtv.js $H install $B/tvweather-standard.apk
node tools-tv/adbtv.js $H install $B/tvdiscover-standard.apk
node tools-tv/adbtv.js $H install $B/tvjarvis-standard.apk
node tools-tv/adbtv.js $H install $B/tvhome-standard.apk          # pm uninstall com.gathin.tvhome first if the key changed
node tools-tv/adbtv.js $H shell "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity"
node tools-tv/adbtv.js $H theme themes/tv-glass                  # config.json, wallpapers/, clock.png
node tools-tv/adbtv.js $H grant-addons                           # TV listings (Continue watching), microphone
node tools-tv/adbtv.js $H tmdb-token ~/.adbtv/tmdb.token         # For you / Swipe; never committed
node tools-tv/adbtv.js $H gemini-key ~/.adbtv/gemini.key         # Jarvis; never committed
node tools-tv/adbtv.js $H key-service on                         # remote mic button -> Jarvis (off to undo)
node tools-tv/adbtv.js $H shell "settings put secure screensaver_components com.gathin.tvhome/.ClockDream"
```

Undo the mic mapping with `key-service off`; the stock launcher rollback above still applies.
