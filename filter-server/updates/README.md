# Updates directory

Served as-is over HTTPS at `https://<UPDATE_HOST>/updates/` by the `updates` (Caddy) service in
`docker-compose.yml`. Everything in this directory except this README is gitignored -- it's
publish output, not source.

`.github/workflows/update-review.yml`'s `sign-and-publish` job (which only runs after AI review
passed *and* the Guardian approved the protected `release` GitHub Environment) `scp`s two files
here on every release:

- `manifest.json` -- `{"versionCode": N, "versionName": "...", "apkUrl": "...", "sha256": "..."}`,
  overwritten each release. This is what the Otterling app's `ApprovedUpdateManager.checkForUpdate()`
  fetches.
- `otterling-<versionName>.apk` -- the signed release build itself, referenced by `manifest.json`'s
  `apkUrl`. Old versions can be deleted manually once no device needs to fall back to them;
  nothing here does that automatically.

Nothing should ever be written into this directory by hand except for initial testing --
`manifest.json`/the APK should only ever come from a Guardian-approved CI run, or the phone would
be trusting whatever's placed here instead of the actual reviewed-and-signed chain (see
`scripts/update_review_checklist.md`).
