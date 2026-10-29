# Xiaomi MiTV-AESP0 install notes (this fork only)

Built on GitHub Actions (`.github/workflows/tv-build.yml`), installed with `adbtv.js` because the
work laptop has no adb or Python. Reviewed upstream commit: `718f98c9`.

Stock launcher, Xiaomi PatchWall (`com.mitv.tvhome.atv`) and Monet stay enabled. Do not use the
upstream install script's default, which disables the stock launcher.

```
H=<tv-ip>
node tools-tv/adbtv.js $H pull-state tv-state-before.txt          # save the package state first
node tools-tv/adbtv.js $H install tvhome-standard.apk
node tools-tv/adbtv.js $H shell "am start -W -n com.gathin.tvhome/.MainActivity"
node tools-tv/adbtv.js $H shell "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" \
                                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
```

If Home still resolves to Xiaomi, use the `allowlist-com.ts.launcher` APK instead (check
`pm path com.ts.launcher` is empty first) and the component `com.ts.launcher/com.gathin.tvhome.MainActivity`.

## Roll back to the previous Home (Monet)
```
node tools-tv/adbtv.js $H shell "cmd package set-home-activity --user 0 com.klevico.monet/<monet-home-activity>" "input keyevent KEYCODE_HOME"
node tools-tv/adbtv.js $H shell "pm uninstall --user 0 com.gathin.tvhome"
```
Find Monet's Home activity first with `cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME`.
