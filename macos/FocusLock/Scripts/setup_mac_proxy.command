#!/bin/bash
# One-time setup so the Mac can be content-filtered through the filter-server's mitmproxy (the same
# proxy the phone tunnels through). Does three root things:
#   1. Trusts the mitmproxy CA certificate in the System keychain, so intercepted HTTPS doesn't throw
#      cert errors. (Firefox uses its OWN cert store -- if you use Firefox, also import the CA under
#      Firefox > Settings > Privacy & Security > Certificates > View Certificates > Import.)
#   2. Writes the proxy password to /Library/Application Support/FocusLock/proxy_password (root,0600)
#      so ProxyEnforcer can set an authenticated system proxy. WITHOUT this file, proxy enforcement
#      stays inert (fail-open) even if you turn it on -- an authenticated proxy with no password would
#      407 every request.
#   3. Copies the same CA cert to /Library/Application Support/FocusLock/proxy_ca.pem (root,0644 --
#      world-READABLE, unlike proxy_password, since it's a public cert, not a secret) so
#      ShellProxyEnvManager can point Node/Python-based CLI tools (npm, pip, and the `claude` CLI
#      itself) at it via NODE_EXTRA_CA_CERTS/SSL_CERT_FILE/REQUESTS_CA_BUNDLE. Those tools carry
#      their own root store instead of using the System keychain step 1 just updated, so without
#      this they fail TLS verification against mitmproxy once --force is on -- indistinguishable
#      from "the network is blocked" even though browsers on the same Mac work fine.
#
# After this, turn enforcement on with:  otterlingctl enable-proxy         (browsers only)
#                                    or:  otterlingctl enable-proxy --force (firewall-lock all :80/:443)
#
# Get the CA cert from the server first, e.g.:
#   scp admin@vpn.bartholomew.help:/path/to/filter-server/mitm-ca/mitmproxy-ca-cert.pem ~/Downloads/
#
# Usage: double-click this file, or run it from Terminal.

set -euo pipefail

CERT_DEFAULT="$HOME/Downloads/mitmproxy-ca-cert.pem"

CERT=$(/usr/bin/osascript -e "text returned of (display dialog \"Path to the mitmproxy CA cert (mitmproxy-ca-cert.pem)\" default answer \"$CERT_DEFAULT\" with title \"Otterling proxy setup\")") || {
  echo "Cancelled." >&2
  exit 1
}
if [ ! -f "$CERT" ]; then
  /usr/bin/osascript -e "display dialog \"No file at: $CERT\n\nCopy the CA cert from the server first (see the comment at the top of this script), then re-run.\" with title \"Otterling proxy setup\" buttons {\"OK\"} default button 1" >/dev/null 2>&1 || true
  echo "CA cert not found at $CERT" >&2
  exit 1
fi

PASSWORD=$(/usr/bin/osascript -e 'text returned of (display dialog "PROXY_PASSWORD (from filter-server/.env on your server)" default answer "" with hidden answer with title "Otterling proxy setup")') || {
  echo "Cancelled." >&2
  exit 1
}
if [ -z "$PASSWORD" ]; then
  echo "No proxy password entered. Nothing was changed." >&2
  exit 1
fi

# Password goes through a private temp file, never inline in the elevated shell string, so it never
# appears in `ps` -- same reasoning as install_lock_profile.command's token handling.
PWFILE=$(mktemp /tmp/otterling_proxy_pw.XXXXXX)
chmod 600 "$PWFILE"
printf '%s' "$PASSWORD" > "$PWFILE"
unset PASSWORD

STATE_DIR="/Library/Application Support/FocusLock"

# The elevated commands go into a real script file, run via `do shell script "/bin/bash <path>"`,
# rather than being hand-quoted inline into the osascript -e argument. Hand-quoting a bash string
# INTO an AppleScript string literal INTO a shell command is three escaping layers deep, and a
# previous version of this script got it wrong: it escaped $CERT/$PWFILE for the bash-assignment
# layer, but that escaping was consumed (collapsed to bare quotes) by the time the result reached
# the outer `osascript -e "..."` string, so any embedded `"` landed unescaped in the middle of what
# AppleScript expected to be one string literal -- a guaranteed syntax error (-2740), not an
# occasional one. Writing a script file means the ONLY thing embedded in the AppleScript layer is
# this file's own path, which we generated ourselves via mktemp and know is quote/backslash-free.
SCRIPT_FILE=$(mktemp /tmp/otterling_proxy_setup.XXXXXX.sh)
chmod 700 "$SCRIPT_FILE"
cat > "$SCRIPT_FILE" <<EOF
#!/bin/bash
set -euo pipefail
/usr/bin/security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain "$CERT"
/bin/mkdir -p "$STATE_DIR"
# 0711, not 0700: this directory also holds proxy_ca.pem (deliberately world-readable, see
# Constants.swift's proxyCACertPath doc comment) alongside root-only files like proxy_password.
# '--x' for group/other lets any process open a file by its known name without granting directory
# listing -- 0700 here would make proxy_ca.pem unreadable regardless of its own file mode, since a
# non-root process can't even traverse into the directory to reach it.
/bin/chmod 711 "$STATE_DIR"
/bin/cp "$PWFILE" "$STATE_DIR/proxy_password"
/bin/chmod 600 "$STATE_DIR/proxy_password"
/usr/sbin/chown root:wheel "$STATE_DIR/proxy_password"
/bin/cp "$CERT" "$STATE_DIR/proxy_ca.pem"
/bin/chmod 644 "$STATE_DIR/proxy_ca.pem"
/usr/sbin/chown root:wheel "$STATE_DIR/proxy_ca.pem"
echo OK
EOF
chmod 500 "$SCRIPT_FILE"

echo "Requesting administrator authorization..."
if ! OUTPUT=$(/usr/bin/osascript -e "do shell script \"/bin/bash '${SCRIPT_FILE}'\" with administrator privileges" 2>&1); then
  rm -f "$PWFILE" "$SCRIPT_FILE"
  echo "$OUTPUT"
  echo "Authorization was cancelled, or setup failed -- see output above." >&2
  exit 1
fi
rm -f "$PWFILE" "$SCRIPT_FILE"
echo "$OUTPUT"
echo "Done. The mitmproxy CA is trusted, the proxy password is provisioned, and the CA cert is"
echo "available at ${STATE_DIR}/proxy_ca.pem for CLI tools (Claude Code, npm, pip, etc.)."
echo "Now run:  otterlingctl enable-proxy        (browsers only)"
echo "     or:  otterlingctl enable-proxy --force (lock all web to the proxy)"
