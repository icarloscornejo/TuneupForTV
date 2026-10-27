<h1 align="center">Tuneup for TV</h1>

<p align="center">
  See, stop, disable, restore and uninstall the apps of your own Android TV, with the remote, without a computer.
</p>

<p align="center">
  <img alt="Android TV" src="https://img.shields.io/badge/Android%20TV-8.0%2B-3DDC84?logo=androidtv&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Version" src="https://img.shields.io/badge/version-1.0.0-blue">
  <img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-lightgrey">
</p>

<p align="center">
  <img src="screenshots/home.png" alt="Home" width="860">
</p>

Tuneup talks to the TV's own ADB over the network, so it only works on a device you control. It is built with Kotlin
and Jetpack Compose for a D-pad remote, and tested on a Sony BRAVIA BF1 and a Xiaomi MiTV.

It was first inspired by [backgroundappslist](https://github.com/visnkmr/backgroundappslist), a legendary little app
that showed what runs in the background. Tuneup takes that idea to the big screen and goes further: it acts on what it
shows, safely and with a way back.

And when the cleanup is done, an optional last step offers **LightLauncher**: a tiny launcher with the calm look of
Apple TV's home screen but an Android feel. It is for people who just want a clean TV, with no themes, no widgets and no
endless settings: only speed and simplicity.

## What it does

<table>
  <tr>
    <td width="50%"><img src="screenshots/packages.png" alt="Packages"><br><b>Packages</b><br>Every app with its category and status. Disable, restore, force stop and uninstall one or several at once.</td>
    <td width="50%"><img src="screenshots/packages-grid.png" alt="Packages as a grid"><br><b>List or grid</b><br>Packages and Processes each keep their own view, sort, visibility and categories.</td>
  </tr>
  <tr>
    <td width="50%"><img src="screenshots/package-detail.png" alt="Package page"><br><b>Package page</b><br>The actions the guard allows, the details, and why a package is protected when it is.</td>
    <td width="50%"><img src="screenshots/processes.png" alt="Processes"><br><b>Processes</b><br>What is running now and how much memory it holds. Force stop one app or everything listed.</td>
  </tr>
  <tr>
    <td width="50%"><img src="screenshots/health.png" alt="Health"><br><b>Health</b><br>Memory, swap, CPU and recent crashes, compared with the start of the session.</td>
    <td width="50%"><img src="screenshots/packages-settings.png" alt="Settings of a list"><br><b>Settings of each list</b><br>Sort, view, and whether system apps and services are included.</td>
  </tr>
</table>

Every change asks first, says what it will do, and is written to a journal, so a half-done change is found and shown
at the next start.

<table>
  <tr>
    <td width="50%"><img src="screenshots/uninstall-confirm.png" alt="Confirming an uninstall"></td>
    <td width="50%"><img src="screenshots/uninstall-done.png" alt="An uninstall done"></td>
  </tr>
  <tr>
    <td width="50%"><img src="screenshots/force-stop-all.png" alt="Confirming Force stop all"></td>
    <td width="50%"><img src="screenshots/force-stop-done.png" alt="Force stop all done"></td>
  </tr>
</table>

## Debloat, step by step

The list made for this TV (chosen by brand and model), applied one package at a time and fully undoable. The first time,
an assistant leads: choose, confirm, clean, result and, last and optional, a lighter launcher.

<table>
  <tr>
    <td width="33%"><img src="screenshots/wizard-1-pick.png" alt="Choose"><br><b>1. Choose</b></td>
    <td width="33%"><img src="screenshots/wizard-2-confirm.png" alt="Confirm"><br><b>2. Confirm</b></td>
    <td width="33%"><img src="screenshots/wizard-3-run.png" alt="Clean"><br><b>3. Clean</b></td>
  </tr>
  <tr>
    <td width="33%"><img src="screenshots/wizard-4-result.png" alt="Result"><br><b>4. Result</b>, with Undo</td>
    <td width="33%"><img src="screenshots/wizard-5-launcher.png" alt="Launcher"><br><b>5. Launcher</b>, optional</td>
    <td width="33%"><img src="screenshots/debloat-disabled.png" alt="Debloat tabs"><br><b>Afterwards</b>, three tabs</td>
  </tr>
</table>

## First start

The app reads and changes the TV through its own ADB, so the first time the TV asks you to trust it: turn on
network debugging in the TV's developer options, open Tuneup, and in the prompt choose **Always allow** and then
**Allow**.

<table>
  <tr>
    <td width="50%"><img src="screenshots/notice.png" alt="Before you start"></td>
    <td width="50%"><img src="screenshots/adb-permission.png" alt="Allow USB debugging"></td>
  </tr>
</table>

## Build
Requires JDK 17+ and the Android SDK (see `local.properties`).

```
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
adb connect <tv-ip>:5555
adb -s <tv-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

## Versioning
[Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`.

- The version is written once, as `appVersion` in `app/build.gradle.kts`; `versionCode` is derived from it
  (`major * 10000 + minor * 100 + patch`).
- To release: bump `appVersion`, commit as `Release vX.Y.Z`, and tag it `vX.Y.Z`.

## Debloat lists

The Debloat screen ships 307 lists from [cleanyourtv](https://github.com/Gathin23/cleanyourtv) (MIT, licence in
`app/src/main/assets/licenses/cleanyourtv.txt`), pinned to commit `5ee685b02014fa502ea0951ee1f49abd7ee2e8c1`, plus
`mi-bravia-bf1.txt`, written and tested by hand for the BRAVIA BF1. The pinned commit and its date are in
`tools/cleanyourtv.pin`.

## Launcher download

The launcher is not inside the app. When the launcher step opens, Tuneup asks GitHub for the latest release of
[icarloscornejo/LightLauncher](https://github.com/icarloscornejo/LightLauncher) and looks for two files in it: the one
`.apk`, and a `.sha256` file with the same name plus `.sha256` (for example `shasum -a 256 app-release.apk >
app-release.apk.sha256`). The tag must be `vX.Y.Z`; the version comes from it.

The APK is downloaded only after you confirm, over https, and it is installed only if its SHA-256 is the one published
in that release. The hash comes from the same release, so whoever controls that release controls the APK: the trust is
in the GitHub account that publishes it, and the signature is not pinned.

No internet, no repository, no release, a missing file or a hash that does not match all mean the same thing: the
launcher is not available, nothing is installed, and the step can be skipped or tried again. A TV that already has a
newer LightLauncher than the published one is never taken back.

## Next ideas

Where Tuneup may go next. These are ideas, not promises.

- A cleaner for files nobody uses any more (leftover app data, caches and downloads), with the same rules as
  everything else here: it shows what it will remove, asks first and keeps a journal.
- Tuneup for an Android phone, for when you would rather not install it on the TV: the whole app (packages,
  processes, health, debloat and the launcher) run from the phone like a remote control, over the TV's ADB on the
  network, with the same guard and the same way back.

## License
See [LICENSE](LICENSE).
