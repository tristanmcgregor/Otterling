# Updates directory (git tree)

This folder in git only keeps documentation (plus `macos-manifest.example.json`, a template --
**not** a live manifest). **Published artifacts are not here.**

On the production host:

```text
/var/lib/otterling/updates/
  manifest.json              # Android (phones read this)
  otterling-<version>.apk
  macos-manifest.json        # macOS (UpdateManager reads this) -- published manually, see below
  otterling-macos-<version>.zip
  index.json                 # All monorepo components
  android/                   # Same APK + manifest (namespaced)
  filter-server/manifest.json
```

`manifest.json`/`otterling-<version>.apk` are written by root via `release.sh` after AI
`VERDICT: PASS`:

1. Build + publish Android APK
2. Deploy `filter-server` onto this host (compose + mux), preserving `.env` / CA / AdGuard state
3. Write `index.json` listing every component

**`macos-manifest.json`/`otterling-macos-<version>.zip` are not part of that automated path.** The
release host above is Linux-only (Android SDK, no Xcode/`codesign`) and structurally can't build or
sign a macOS `.app` -- there is no macOS build agent in this pipeline. Until one exists, publishing
a macOS release is a manual step: run `macos/FocusLock/Scripts/publish_release.sh` locally (after
`build_app.sh` with a real Developer ID identity, not ad-hoc/free "Apple Development"), then copy
its two output files onto this host yourself. See
[`macos/FocusLock/RELEASE.md`](../../macos/FocusLock/RELEASE.md) for the full picture, including
what actually adding CI automation for this would take. `index.json` lists macOS as
`not_built_on_linux` for the same reason, until that changes.

Even though the build itself is manual, `UpdateManager` on the client will only install a
`macos-manifest.json` whose `reviewAttestation` field verifies against a key that lives only on
*this* host, at `/var/lib/otterling/ci/secrets/macos_review_attestation_ed25519` -- and that host
only ever signs a manifest naming the git SHA in `last_published_sha` (i.e. one that already passed
the same cumulative AI review Android's APK goes through). Run `sudo otterling-attest-macos` here to
get that signature; see `macos/FocusLock/RELEASE.md`'s "Per-release" section for the exact command.

See [`SELF_LOCKOUT.md`](../SELF_LOCKOUT.md).
