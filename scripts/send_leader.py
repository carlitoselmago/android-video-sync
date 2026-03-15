#!/usr/bin/env python3
"""Broadcast test leader sync packets for the Android follower app."""

from __future__ import annotations

import argparse
import os
import signal
import socket
import sys
import time


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Broadcast follower sync packets over UDP.",
    )
    parser.add_argument(
        "--destination",
        "--address",
        dest="destination",
        default="255.255.255.255",
        help="Broadcast address to send to.",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=1666,
        help="UDP port to send to.",
    )
    parser.add_argument(
        "--filename",
        default="video.mp4",
        help="Filename to include in the sync packet. Basename is used by default.",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=1.0,
        help="Seconds between packets.",
    )
    parser.add_argument(
        "--start-seconds",
        type=float,
        default=0.0,
        help="Initial playback time in seconds.",
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=None,
        help="Optional loop duration in seconds. If provided, playback wraps at this duration.",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    running = True

    def stop_handler(_signum: int, _frame: object) -> None:
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, stop_handler)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, stop_handler)

    start_monotonic = time.monotonic()
    filename = os.path.basename(args.filename)

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(("0.0.0.0", args.port))
        sock.connect((args.destination, args.port))

        print(
            f"Broadcasting to {args.destination}:{args.port} as {filename!r} "
            f"every {args.interval:.3f}s",
            flush=True,
        )

        while running:
            elapsed = args.start_seconds + (time.monotonic() - start_monotonic)
            if args.duration and args.duration > 0:
                elapsed %= args.duration

            payload = f"{elapsed}%{filename}"
            sock.send(payload.encode("utf-8"))
            print(payload, flush=True)
            time.sleep(max(args.interval, 0.01))

    print("Stopped.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
