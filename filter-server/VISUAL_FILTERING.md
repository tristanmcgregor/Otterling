# Visual (image-level) NSFW filtering -- research note

**Status: research only. Nothing here is implemented.** `mitm_nsfw_addon.py` today is domain/
path/title-keyword rules, no image inspection at all (see the main [README](README.md)). This
note compares lighter alternatives to full Canopy-style in-page image blanking, so a future pass
has an actual recommendation to build from instead of starting cold.

## Where would this even run? (the question that decides everything else)

Otterling's current architecture already decrypts HTTPS server-side, in `mitmproxy`
(`mitm_nsfw_addon.py`) -- that's the entire reason the proxy pivot exists (see the top-level
README's Phase 7). The Android app itself never sees plaintext HTTP bytes for anything routed
through the tunnel; it only relays encrypted TLS between the client and the proxy. That one fact
rules out most of the "obvious" on-device options unless something bigger changes first:

- **A classifier running in the Android app** could only see plaintext image bytes if the app
  itself did the TLS termination (moving MITM decryption on-device) -- a materially bigger
  architecture change than "add a model," with its own new costs (CPU/battery for continuous
  decryption on a phone instead of a home server, private key material living on-device instead
  of only on the server, etc.). Not evaluated further here; out of scope unless the proxy
  architecture itself changes.
- **A classifier running in the mitmproxy addon** (server-side, same process as the existing
  domain/path rules) already has the plaintext image bytes for free, for exactly the same traffic
  the addon already filters -- no new decryption path, no new trust boundary. This is the only
  option that fits the current architecture without a rewrite, so it's the one actually evaluated
  in depth below.

This also directly answers the bypass-compatibility question the plan asked about: **a visual
filter here inherits exactly the same limitation the existing text/domain filter already has.**
Apps in `VpnBypassManager.DEFAULT_BYPASS_PACKAGES` (YouTube, banking apps) never send traffic
through mitmproxy at all -- bypassed for the same certificate-pinning reason described in the main
README -- so no proxy-side image classifier, however good, will ever see their images. Ordinary
browser traffic (Chrome, Firefox, in-app WebViews that don't pin) goes through the proxy and would
get images classified. This isn't a gap specific to visual filtering; it's the existing, accepted
trade-off extended to a new content type.

## Options considered

### 1. Proxy-side image classifier (recommended direction)

Hook `mitm_nsfw_addon.py`'s existing `response()` method (same place the title-keyword check
already runs) for responses with `content-type: image/*`, size-capped (e.g. skip anything over a
couple of MB to bound per-request latency/CPU on a home server with no GPU), run a small local
NSFW/SFW image classifier against the decoded image, and block (403, same `BLOCK_PAGE`) above some
confidence threshold.

**Prior art already in this codebase:** `app/src/main/java/app/otterling/focus/ImageMatcher.kt`
already does on-device MediaPipe image *embedding* (MobileNet-V3, bundled
`assets/mobilenet_embedder.tflite`, fully offline) for habit photo-proof verification -- proof the
"small local vision model, no network, bundled asset" pattern already works well in this project.
It is **not** directly reusable here, though: an embedder produces a similarity vector between two
images (is this the same scene as a reference photo?), not an NSFW/SFW classification of a single
arbitrary image. A genuine NSFW classifier is a different, purpose-trained model.

Concrete candidate: [`opennsfw2`](https://github.com/bhky/opennsfw2) (MIT-licensed, actively
maintained Keras/TF re-implementation of Yahoo's original `open_nsfw`), which exports to a
lightweight model runnable via TFLite/ONNX on CPU. Runs directly inside the Python mitmproxy addon
process -- no new service, no new container.

- **Pros:** Fits the existing architecture with no new trust boundary; fully local (no third-party
  network calls, matching this project's "no network calls beyond our own blocklist/update host"
  posture); works for exactly the traffic the rest of the filter already covers.
- **Cons:** General-purpose NSFW classifiers have a well-documented, non-trivial false-positive
  rate on swimwear/beach photos, breastfeeding, some medical/health imagery, art/statues, and
  certain skin-tone/lighting conditions -- this is a known limitation of the whole model category,
  not a specific implementation bug. Every image response adds classifier latency to page load
  (mitigated by the size cap, but still real). CPU-only inference on a modest home server needs
  actual benchmarking under realistic image volume before committing to a default-on rollout.

### 2. Browser/WebView accessibility heuristics

Reuse the existing `AccessibilityService` (already powers `UrlPathBlockEnforcer`'s path rules) to
inspect a WebView/browser's accessibility node tree -- e.g. image `alt` text, surrounding link
text, `img src` URLs -- for keyword matches, without touching pixel data at all.

- **Weaker than option 1, and weaker than what this filter already does.** The accessibility tree
  doesn't expose decoded pixels, only text/URL metadata that the domain/path/title-keyword rules
  already inspect via a more reliable path (the actual HTTP response, not a UI tree that may or may
  not expose useful `alt` text -- most images on the web have no meaningful alt text at all). This
  option doesn't add real visual signal; it mostly re-derives a strictly worse version of rules
  that already exist server-side.
- **Ironic dead end for the exact apps that matter most for this trade-off:** banking apps
  routinely mark their sensitive views `FLAG_SECURE`, which blocks both screenshots *and*
  meaningful accessibility content extraction for those views -- the same apps already bypassed
  for certificate pinning would also resist this approach even if it worked in general.
- Not recommended as a serious visual-filtering path; only worth revisiting if a very specific,
  narrow gap shows up in practice that domain/path/title rules don't cover.

### 3. Third-party cloud moderation APIs

Google Cloud Vision SafeSearch, AWS Rekognition, Azure Content Moderator, Sightengine, Hive, etc.

- **Pros:** Materially higher accuracy than anything realistically self-hosted on a home server --
  these are well-funded, continuously-retrained services.
- **Cons, and why not recommended for this project specifically:** Sends actual image bytes from
  family browsing to a third-party vendor for every image -- a real privacy regression against
  this project's explicit direction so far (`ImageMatcher` is deliberately "on-device, no-network";
  the whole point of self-hosting `filter-server/` is not routing family traffic through anyone
  else's infrastructure). Also adds real per-image latency (a round trip to an external API before
  a page can finish loading) and ongoing per-image cost that scales with normal browsing volume
  (a single web page can easily have dozens of images). Only worth reconsidering if a local model,
  after real evaluation, turns out clearly insufficient -- and even then, a locally-hosted stronger
  open model should be tried first.

## Recommendation

Pursue **option 1 (proxy-side local classifier)** if/when this gets built, not options 2 or 3.

## Recommended first spike (not a production rollout)

1. Add `opennsfw2` (or an equivalent small, permissively-licensed, locally-runnable model) to the
   mitmproxy container's dependencies.
2. Hook it into `mitm_nsfw_addon.py`'s `response()`, gated to `content-type: image/*` and a size
   cap, alongside the existing HTML title-keyword check.
3. Run it in **shadow mode first**: log the classifier's score and what it *would* have blocked
   (reusing the existing `otterling_blocked_reason` metadata pattern) without actually returning a
   403, against real family traffic for a period.
4. Review the shadow-mode logs for false positives/negatives before picking a default confidence
   threshold or ever shipping it as an enforced block -- the existing addon's whole design
   philosophy is "deliberately narrow to keep false positives low" (see README); a new content type
   this prone to false positives deserves the same caution the domain-cache-scoping fix in this
   same pass was applied for, not a same-day hard launch.
5. Only after that review, decide whether to enable it as a real block and at what threshold.

This is a spike-sized effort (days, not weeks) to get real data, not a commitment to ship visual
filtering at all -- the shadow-mode data itself might show the false-positive rate is too high to
be worth it, and that's a legitimate outcome of doing the spike.
