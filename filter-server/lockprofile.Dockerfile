# Screenshot NSFW classification (POST /screenshot-classify, nsfw_image_classifier.py) runs
# entirely through onnx_nsfw_pipeline.py's two-stage Falconsai/NudeNet ONNX pipeline -- no LLM
# subprocess involved, so this image only needs onnxruntime/numpy/Pillow on top of the stock
# python:3.12-slim image. Inert (no-op, classification unavailable) until an operator drops real
# .onnx model files into the /models volume mounted below.
#
# lockprofile_service.py runs as root in this container (no USER directive, same as the stock
# image it replaces); it drops to an unprivileged account below since none of its routes need root
# (this service listens on 8091, an unprivileged port).
FROM python:3.12-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    useradd --uid 1001 --create-home --home-dir /home/lockprofile --shell /usr/sbin/nologin lockprofile && \
    pip install --no-cache-dir google-auth requests onnxruntime numpy Pillow

# Runs unprivileged. Unlike dns-classifier (which must stay root to bind port 53), this service
# listens on 8091 -- an unprivileged port -- so root bought nothing while this container serves
# routes that are reachable without a guardian session and writes to a mounted volume.
#
# DEPLOY NOTE: the ./lockprofile-data host directory must be readable AND writable by uid 1001
# (this account, pinned explicitly above so it doesn't shift on image rebuilds). On an existing
# deployment run, once:
#     sudo chown -R 1001:1001 filter-server/lockprofile-data
# Without that the service starts and then fails every write (alerts, settings, screenshots).
USER lockprofile
