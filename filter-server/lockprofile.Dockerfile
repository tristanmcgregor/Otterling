# Adds the Claude Code CLI on top of the stock python:3.12-slim image so
# nsfw_image_classifier.py (screenshot NSFW classification for the
# /screenshot-classify route in lockprofile_service.py) can shell out to
# `claude -p` -- see mitmproxy.Dockerfile's header comment for why
# (subscription auth instead of a metered ANTHROPIC_API_KEY). Same base image
# and same CLI version as dns-classifier.Dockerfile, which this mirrors.
#
# lockprofile_service.py runs as root in this container (no USER directive,
# same as the stock image it replaces), and `claude -p --permission-mode
# bypassPermissions` refuses to run as root/via sudo -- so, exactly like
# dns_classify_mux.py, the classifier drops privileges for just the `claude`
# subprocess call (ai_classifier.py's CLAUDE_RUNNER_USER) to this dedicated
# unprivileged account.
FROM python:3.12-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates gnupg && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    npm install -g @anthropic-ai/claude-code@2.1.197 && \
    rm -rf /var/lib/apt/lists/* && \
    useradd --create-home --home-dir /home/claude-runner --shell /usr/sbin/nologin claude-runner
