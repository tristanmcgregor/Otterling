# Updates directory (git tree)

This folder in git only keeps documentation. **Published APKs and `manifest.json` are not here.**

On the production host they live at:

```text
/var/lib/otterling/updates/
```

owned by `otterling-deploy`, written only over SFTP from GitHub Actions after AI
`VERDICT: PASS`. Caddy mounts that path read-only. See
[`SELF_LOCKOUT.md`](../SELF_LOCKOUT.md).

`.github/workflows/update-review.yml`'s `sign-and-publish` job uploads:

- `manifest.json` -- fetched by `ApprovedUpdateManager.checkForUpdate()`
- `otterling-<versionName>.apk` -- referenced by `manifest.json`'s `apkUrl`

Do not hand-copy APKs into that directory on the server from a daily account.
