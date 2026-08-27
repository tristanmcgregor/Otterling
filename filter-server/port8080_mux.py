#!/usr/bin/env python3
"""TCP mux on :8080 — CONNECT goes to mitmproxy, everything else to a co-tenant service.

Otterling's app defaults to proxy port 8080; another process on this host already owned that port.
This keeps both working on the port the phone expects. Per filter-server/README.md this is no
longer needed for Otterling's own traffic (phones use 8090 directly) and is retained only for the
unrelated co-tenant.

SECURITY — READ BEFORE SETTING MUX_BARTH_PORT:

This process terminates the client's TCP connection and opens a fresh one to the chosen upstream.
Two consequences that are easy to miss:

  1. It launders the source IP. Whatever it forwards to sees this host, not the real client, so any
     upstream that makes per-device decisions by source IP (mitm_nsfw_addon.py's website rules and
     browsing budgets do exactly that) collapses every device into one identity.

  2. It re-publishes whatever it points at. `MUX_BARTH_PORT` used to default to 8091 -- which is
     the lock-profile service, bound to 127.0.0.1 in docker-compose.yml specifically so that Caddy
     is the only way in. Caddy is where the guardian-session gate (`forward_auth`) lives, so
     forwarding to 8091 from a public listener handed anyone holding the device bearer token full
     guardian authority: read the plaintext Guardian PIN, PATCH any device's settings, disable
     filtering fleet-wide. That default is now removed, the listener binds loopback, and a
     collision with the lock-profile port is refused outright.

The lock-profile service now also enforces the device/guardian split itself (see route_policy.py),
so this is no longer the only thing standing between :8080 and guardian control -- but a public
listener in front of a loopback-only service is wrong regardless of what the service checks.
"""

from __future__ import annotations

import asyncio
import logging
import os
import signal

# Loopback by default. Binding every interface is opt-in via MUX_LISTEN_HOST, so exposing this
# has to be a decision someone typed rather than a default nobody revisited.
LISTEN_HOST = os.environ.get("MUX_LISTEN_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("MUX_LISTEN_PORT", "8080"))
MITM_HOST = os.environ.get("MUX_MITM_HOST", "127.0.0.1")
MITM_PORT = int(os.environ.get("MUX_MITM_PORT", "8090"))
BARTH_HOST = os.environ.get("MUX_BARTH_HOST", "127.0.0.1")
# No default: the previous one pointed at the lock-profile service. Must be set explicitly.
BARTH_PORT_RAW = os.environ.get("MUX_BARTH_PORT", "")
# Ports this mux must never forward to, whatever it is told. Read from the environment where
# available so a non-default LOCKPROFILE_PORT is still covered.
FORBIDDEN_UPSTREAM_PORTS = {int(os.environ.get("LOCKPROFILE_PORT", "8091")), 8091}
BARTH_PORT = 0  # set by _resolve_upstream_port() before the server starts
PEEK_BYTES = 8  # enough for b"CONNECT "

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("port8080_mux")


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except (asyncio.CancelledError, ConnectionResetError, BrokenPipeError, OSError):
        pass
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def handle(client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter) -> None:
    peer = client_writer.get_extra_info("peername")
    upstream_writer = None
    try:
        peek = await client_reader.readexactly(PEEK_BYTES)
        if peek.upper().startswith(b"CONNECT"):
            host, port = MITM_HOST, MITM_PORT
            kind = "mitm"
        else:
            host, port = BARTH_HOST, BARTH_PORT
            kind = "barth"

        upstream_reader, upstream_writer = await asyncio.open_connection(host, port)
        upstream_writer.write(peek)
        await upstream_writer.drain()
        log.info("%s -> %s:%s (%s)", peer, host, port, kind)

        await asyncio.gather(
            pipe(client_reader, upstream_writer),
            pipe(upstream_reader, client_writer),
        )
    except asyncio.IncompleteReadError:
        log.info("%s disconnected before peek", peer)
    except Exception as exc:
        log.warning("%s handler error: %s", peer, exc)
    finally:
        try:
            client_writer.close()
            await client_writer.wait_closed()
        except Exception:
            pass
        if upstream_writer is not None:
            try:
                upstream_writer.close()
                await upstream_writer.wait_closed()
            except Exception:
                pass


def _resolve_upstream_port() -> int:
    """Refuses to run rather than forward to something that must not be published. Exiting is the
    right failure here: the co-tenant this mux fronts losing its port is a visible, recoverable
    outage, whereas silently exposing the lock-profile service is neither."""
    if not BARTH_PORT_RAW:
        raise SystemExit(
            "MUX_BARTH_PORT is not set. It has no default on purpose -- the previous default (8091) "
            "was the lock-profile service, which is bound to loopback so that Caddy is the only "
            "path in. Set it to the port of the co-tenant service this mux is meant to front."
        )
    try:
        port = int(BARTH_PORT_RAW)
    except ValueError:
        raise SystemExit(f"MUX_BARTH_PORT is not a number: {BARTH_PORT_RAW!r}")
    if port in FORBIDDEN_UPSTREAM_PORTS:
        raise SystemExit(
            f"MUX_BARTH_PORT={port} is the lock-profile service. Forwarding to it from this "
            "listener bypasses Caddy's guardian-session gate. Refusing to start."
        )
    if port == LISTEN_PORT:
        raise SystemExit(f"MUX_BARTH_PORT={port} is this mux's own listen port -- that would loop.")
    return port


async def main() -> None:
    global BARTH_PORT
    BARTH_PORT = _resolve_upstream_port()
    if LISTEN_HOST not in ("127.0.0.1", "localhost", "::1"):
        log.warning(
            "listening on %s (not loopback) -- every non-CONNECT byte is forwarded to %s:%s with "
            "the real client IP replaced by this host's. Confirm that upstream neither makes "
            "per-source-IP decisions nor expects to be unreachable from outside.",
            LISTEN_HOST, BARTH_HOST, BARTH_PORT,
        )
    server = await asyncio.start_server(handle, LISTEN_HOST, LISTEN_PORT)
    addrs = ", ".join(str(s.getsockname()) for s in server.sockets or [])
    log.info(
        "listening on %s ; CONNECT -> %s:%s ; else -> %s:%s",
        addrs,
        MITM_HOST,
        MITM_PORT,
        BARTH_HOST,
        BARTH_PORT,
    )

    stop = asyncio.Event()

    def _stop(*_args: object) -> None:
        stop.set()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, _stop)

    async with server:
        await stop.wait()
        server.close()
        await server.wait_closed()


if __name__ == "__main__":
    asyncio.run(main())
