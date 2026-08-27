# Adds the Claude Code CLI on top of the stock mitmproxy image so
# mitm_nsfw_addon.py's AI classifier (ai_classifier.py) can shell out to
# `claude -p` -- authenticated via a subscription OAuth token
# (CLAUDE_CODE_OAUTH_TOKEN), not a metered ANTHROPIC_API_KEY. Installed at
# build time, not container start, so a restart never depends on
# registry.npmjs.org being reachable. Node/npm version pinned to match the
# host's own working `claude` install (verified via `node --version` /
# `npm ls -g @anthropic-ai/claude-code` on the host that built this).
#
# The base image's entrypoint already drops from root to the built-in
# `mitmproxy` user (uid 1000) before exec'ing mitmdump, so the addon runs
# non-root already -- `claude -p --permission-mode bypassPermissions`
# refuses to run as root/via sudo (confirmed by
# /var/lib/otterling/ci/anthropic_review_stream.py, which hit this exact
# restriction for the release-review pipeline's own claude -p call), and
# this container's runtime user is never root, so no privilege-drop dance
# is needed here (contrast dns-classifier.Dockerfile, which does).
# Pinned rather than `latest` -- see docker-compose.yml's adguardhome comment.
FROM mitmproxy/mitmproxy:11.0.2

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates gnupg && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    npm install -g @anthropic-ai/claude-code@2.1.197 && \
    rm -rf /var/lib/apt/lists/*
