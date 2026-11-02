# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.1.0] - 2026-11-02

### Added

- Settings has a "Check for updates": it reads the latest Tuneup release on GitHub, downloads it, checks it against the
  `.sha256` published with it and hands it to the system installer, which asks you to confirm. Tuneup also looks once
  when it starts, in silence, and says on the Settings card on Home when a newer version is out. It is not installed
  while a change to the TV is running.
- A release can be signed with a fixed key from a `keystore.properties` file that is never committed, so every
  release installs over the previous one.

### Changed

- The bundled debloat lists are refreshed from cleanyourtv at commit `a1e948c` (2026-11-02): 109 new lists, 112
  updated, none removed (416 in all).

### Fixed

- A TV that never shows "Allow USB debugging?" no longer leaves Tuneup on "Connecting to the device…" for good. The
  prompt is a window of its own, so Tuneup knows it is up when it loses the focus; if it keeps the focus for 8 seconds
  before the TV lets it in, it says the TV did not show the prompt and asks for a restart of the TV, which is the only
  thing that clears an ADB stuck on a lost prompt. The connection keeps waiting meanwhile, so a late prompt still works.
- To tell the wait for the prompt apart from the long first read, the connection now starts with a tiny probe; the
  screen says "Loading…" once the TV has let Tuneup in.
- A package the TV refuses no longer stops the cleanup assistant or a batch on the Packages screen: it is skipped, the
  run goes on, and the report shows what the TV said. A run only stops at a change that leaves something for you to
  decide. The report then offers to undo only the ones that failed, to undo everything, and to continue with the
  packages that were not reached (also those left pending by a lost connection). Undo works for disable, uninstall
  and restore batches.
- An uninstall batch no longer counts every package as not clean.
- Force stop now checks afterwards whether the app stayed closed. If Android opened it again right away, or the same
  process is still running, it says so (and suggests disabling the app) instead of reporting it closed. "Force stop
  all" and batches count how many came back.

## [1.0.0] - 2026-10-27

First release.

[Unreleased]: https://github.com/icarloscornejo/TuneupForTV/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/icarloscornejo/TuneupForTV/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/icarloscornejo/TuneupForTV/releases/tag/v1.0.0
