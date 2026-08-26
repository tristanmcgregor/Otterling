#!/bin/bash
# One-time setup so the Mac can be content-filtered through the filter-server's mitmproxy (the same
# proxy the phone tunnels through). Does two root things:
#   1. Trusts the mitmproxy CA certificate in the System keychain, so intercepted HTTPS doesn't throw
#      cert errors. (Firefox uses its OWN cert store -- if you use Firefox, also import the CA under
#      Firefox > Settings > Privacy & Security > Certificates > View Certificates > Import.)
#   2. Writes the proxy password to /Library/Application Support/FocusLock/proxy_password (root,0600)
#      so ProxyEnforcer can set an authenticated system proxy. WITHOUT this file, proxy enforcement
#      stays inert (fail-open) even if you turn it on -- an authenticated proxy with no password would
#      407 every request.
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

esc_cert=${CERT//\\/\\\\}; esc_cert=${esc_cert//\"/\\\"}
esc_pwfile=${PWFILE//\\/\\\\}; esc_pwfile=${esc_pwfile//\"/\\\"}

# One elevated shell does both root actions. `security add-trusted-cert` installs the CA as a trusted
# root; the password file is written 0600 next to the daemon's own state.
ELEVATED="/usr/bin/security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain \"${esc_cert}\" && /bin/mkdir -p \"${STATE_DIR}\" && /bin/chmod 700 \"${STATE_DIR}\" && /bin/cp \"${esc_pwfile}\" \"${STATE_DIR}/proxy_password\" && /bin/chmod 600 \"${STATE_DIR}/proxy_password\" && /usr/sbin/chown root:wheel \"${STATE_DIR}/proxy_password\" && echo OK"

echo "Requesting administrator authorization..."
if ! OUTPUT=$(/usr/bin/osascript -e "do shell script \"${ELEVATED}\" with administrator privileges" 2>&1); then
  rm -f "$PWFILE"
  echo "$OUTPUT"
  echo "Authorization was cancelled, or setup failed -- see output above." >&2
  exit 1
fi
rm -f "$PWFILE"
echo "$OUTPUT"
echo "Done. The mitmproxy CA is trusted and the proxy password is provisioned."
echo "Now run:  otterlingctl enable-proxy        (browsers only)"
echo "     or:  otterlingctl enable-proxy --force (lock all web to the proxy)"
