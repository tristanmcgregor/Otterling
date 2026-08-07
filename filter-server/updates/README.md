# Updates directory (git tree)

This folder in git only keeps documentation. **Published artifacts are not here.**

On the production host:

```text
/var/lib/otterling/updates/
  manifest.json              # Android (phones read this)
  otterling-<version>.apk
  index.json                 # All monorepo components
  android/                   # Same APK + manifest (namespaced)
  filter-server/manifest.json
```

Written by root via `release.sh` after AI `VERDICT: PASS`:

1. Build + publish Android APK
2. Deploy `filter-server` onto this host (compose + mux), preserving `.env` / CA / AdGuard state
3. Write `index.json` listing every component

macOS FocusLock is listed in `index.json` as `not_built_on_linux` until a Mac builder exists.

See [`SELF_LOCKOUT.md`](../SELF_LOCKOUT.md).
