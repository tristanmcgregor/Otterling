# Proxy CA certificate

This directory is where the deployed mitmproxy's **public** CA certificate (`otterling-proxy-ca.pem`)
gets copied to, so it can be committed to the Android app for `DevicePolicyManager.installCaCert`.

**The actual `.pem` file is gitignored and must never be committed as a real secret's private
key** -- but note that `mitmproxy-ca-cert.pem` itself is only the public certificate (safe to
distribute to clients); the matching **private key** stays on the server inside
`filter-server/mitm-ca/` (also gitignored) and must never leave it.

## Generating and extracting it

The CA is generated fresh, unique per deployment, the first time mitmproxy starts with an empty
`./mitm-ca/` directory (see `docker-compose.yml`'s volume mount) -- there is no way to pre-generate
or share one across deployments, and you should not try to.

```bash
cd filter-server
docker compose up -d mitmproxy
# Wait a few seconds for mitmproxy to generate its CA on first start, then:
cp mitm-ca/mitmproxy-ca-cert.pem ca/otterling-proxy-ca.pem
```

Then copy that same file into the Android app before building it:

```bash
cp ca/otterling-proxy-ca.pem ../../app/src/main/res/raw/otterling_proxy_ca.pem
```

(Android resource filenames must be lowercase with underscores, hence the rename.)

**The placeholder `otterling_proxy_ca.pem` already committed under `app/src/main/res/raw/` is a
throwaway self-signed certificate** (generated with `openssl`, not by mitmproxy) that exists only
so the Android project has something to compile `R.raw.otterling_proxy_ca` against out of the box.
It does **not** match any real mitmproxy instance's private key and must be replaced with the real
one from your own deployment before the proxy filter can actually work -- until you do, every
HTTPS site will fail TLS validation once "Use cloud filter" -> "Filter proxy" is turned on, because
the phone will be checking mitmproxy's real leaf certificates against the wrong CA.
