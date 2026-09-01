# Adds the Claude Code CLI on top of the stock python:3.12-slim image so
# dns_classify_mux.py's AI classifier (ai_classifier.py, shared with
# mitm_nsfw_addon.py) can shell out to `claude -p` -- see
# mitmproxy.Dockerfile's header comment for why (subscription auth instead
# of a metered ANTHROPIC_API_KEY).
#
# Unlike the mitmproxy container, dns_classify_mux.py itself must keep
# running as root (it binds UDP/TCP port 53, a privileged port). But
# `claude -p --permission-mode bypassPermissions` refuses to run as root/via
# sudo. So ai_classifier.py drops privileges for just the `claude` subprocess
# call (Python's subprocess.run(user=...)), to this dedicated unprivileged
# account -- CLAUDE_RUNNER_USER in ai_classifier.py must match the username
# created here.
FROM python:3.12-slim@sha256:2fe5997d249a808b8eeea52c58a1dbffbba28754dc11699ef5c029f2d818ce79

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates gnupg && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    npm install -g @anthropic-ai/claude-code@2.1.197 && \
    rm -rf /var/lib/apt/lists/* && \
    useradd --create-home --home-dir /home/claude-runner --shell /usr/sbin/nologin claude-runner
