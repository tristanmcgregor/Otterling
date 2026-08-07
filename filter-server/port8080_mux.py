#!/usr/bin/env python3
"""TCP mux on :8080 — CONNECT goes to mitmproxy, everything else to Bartholomew.

Otterling's app defaults to proxy port 8080; Bartholomew already owned that port.
This keeps both working on the public/LAN port the phone expects.
"""

from __future__ import annotations

import asyncio
import logging
import os
import signal

LISTEN_HOST = os.environ.get("MUX_LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("MUX_LISTEN_PORT", "8080"))
MITM_HOST = os.environ.get("MUX_MITM_HOST", "127.0.0.1")
MITM_PORT = int(os.environ.get("MUX_MITM_PORT", "8090"))
BARTH_HOST = os.environ.get("MUX_BARTH_HOST", "127.0.0.1")
BARTH_PORT = int(os.environ.get("MUX_BARTH_PORT", "8091"))
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


async def main() -> None:
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
