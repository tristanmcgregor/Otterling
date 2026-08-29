# Sourced (not executed) by the other build_agent_*.sh scripts, right after config.env.
#
# When this account has proxy/firewall force-through content-filtering enabled on itself (see
# ProxyEnforcer.swift), PFBlocker drops any direct :80/:443 connection that doesn't go through
# mitmproxy -- and ShellProxyEnvManager.swift keeps a managed HTTPS_PROXY/HTTP_PROXY (+ CA cert)
# block in this account's .zshrc/.zprofile/.bash_profile so interactive shells pick it up
# automatically. A LaunchAgent is spawned directly by launchd, though, never through a shell that
# sources those files -- so without this, every git/curl call below silently had a chunk of its
# connections dropped by this Mac's own enforcement, with no error beyond a plain multi-minute
# hang before "Couldn't connect to server". Confirmed live: this was mistaken for an unrelated
# ISP/routing problem for a while before this file existed.
#
# The raw password (Constants.swift's proxyPasswordPath) is root-only (0600) and this account has
# no reason to read it directly -- reusing the *already-provisioned* rc-file block instead means
# this always matches whatever ShellProxyEnvManager currently has live (on, off, or a rotated
# password) without this script needing its own copy of that logic or that secret. When
# enforcement is off, no such block exists anywhere and this is a silent no-op: direct connections,
# exactly like before this file existed.
for _otterling_proxy_rc in "$HOME/.zshrc" "$HOME/.zprofile" "$HOME/.bash_profile"; do
  [[ -f "$_otterling_proxy_rc" ]] || continue
  _otterling_proxy_block="$(sed -n '/# BEGIN OTTERLING PROXY/,/# END OTTERLING PROXY/p' "$_otterling_proxy_rc")"
  if [[ -n "$_otterling_proxy_block" ]]; then
    eval "$_otterling_proxy_block"
    break
  fi
done
unset _otterling_proxy_rc _otterling_proxy_block
