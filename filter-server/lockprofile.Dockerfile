# Adds the Claude Code CLI on top of the stock python:3.12-slim image so
# nsfw_image_classifier.py (screenshot NSFW classification for the
# /screenshot-classify route in lockprofile_service.py) can shell out to
# `claude -p` -- see mitmproxy.Dockerfile's header comment for why
# (subscription auth instead of a metered ANTHROPIC_API_KEY). Same base image
# and same CLI version as dns-classifier.Dockerfile, which this mirrors.
#
# Also installs onnxruntime/numpy/Pillow for onnx_nsfw_pipeline.py, the two-stage
# Falconsai/NudeNet ONNX pipeline nsfw_image_classifier.py tries before falling
# back to `claude -p` -- see that module's docstring. Inert (no-op) until an
# operator drops real .onnx model files into the /models volume mounted below.
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
    useradd --create-home --home-dir /home/claude-runner --shell /usr/sbin/nologin claude-runner && \
    useradd --create-home --home-dir /home/lockprofile --shell /usr/sbin/nologin lockprofile && \
    pip install --no-cache-dir google-auth requests onnxruntime numpy Pillow

# Runs unprivileged. Unlike dns-classifier (which must stay root to bind port 53), this service
# listens on 8091 -- an unprivileged port -- so root bought nothing while this container serves
# routes that are reachable without a guardian session and writes to a mounted volume.
#
# The `claude -p` call still drops further, to claude-runner, via ai_classifier's privilege-drop
# path; that only engages when the process is root, so under this USER it is simply unused and the
# CLI runs as lockprofile instead -- which satisfies its own refusal-to-run-as-root check.
#
# DEPLOY NOTE: the ./lockprofile-data host directory must be readable AND writable by uid 1001
# (this account). On an existing deployment run, once:
#     sudo chown -R 1001:1001 filter-server/lockprofile-data
# Without that the service starts and then fails every write (alerts, settings, screenshots).
USER lockprofile
