# Updates directory (git tree)

This folder in git only keeps documentation. **Published APKs and `manifest.json` are not here.**

On the production host they live at:

```text
/var/lib/otterling/updates/
```

written by root via `sudo otterling-release` (see [`SELF_LOCKOUT.md`](../SELF_LOCKOUT.md)).
Caddy mounts that path read-only. GitHub Actions does **not** publish here.

Published files:

- `manifest.json` -- fetched by `ApprovedUpdateManager.checkForUpdate()`
- `otterling-<versionName>.apk` -- referenced by `manifest.json`'s `apkUrl`

Do not hand-copy APKs into that directory from a daily account.
